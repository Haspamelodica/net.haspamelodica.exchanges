package net.haspamelodica.exchanges.multiplexed;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.ExchangePool;
import net.haspamelodica.exchanges.util.ClosedException;

/**
 * Returned streams are partly thread-safe: External synchronization is only necessary to make sure
 * only one thread accesses the same stream at a time.
 * <p>
 * Writing to an input stream for which the corresponding output stream on the other end doesn't exist
 * is invalid and causes the connection to be shut down (but not closed).
 * The output stream with ID 0 always exists, but streams with higher IDs only exist as soon as {@link #getOut(int)} is first called for that ID.
 * <p>
 * A write only finishes after a corresponding read capable of handling all written bytes has been called on the corresponding input stream.
 * <p>
 * An output stream can only be closed if the corresponding input stream exists. (Unlike output stream 0, input stream 0 is not guaranteed to exist.)
 * Since a write only finishes after a corresponding read, this can only become a problem if no bytes are written to a stream.
 */
public class MultiplexedExchangePool implements ExchangePool
{
	static final boolean DEBUG = false;

	private static final int SIGN_BIT = Integer.MIN_VALUE;

	private final Exchange			rawExchange;
	private final DataInputStream	rawIn;
	private final Object			rawOutLock;
	private final DataOutputStream	rawOut;

	private final Thread										readerThread;
	private final AtomicReference<List<MultiplexedExchange>>	exchangesById;

	private final BlockingQueue<MultiplexedExchange> readyExchanges;

	private final AtomicReference<State>		state;
	private final AtomicReference<IOException>	ioException;

	public MultiplexedExchangePool(Exchange rawExchange)
	{
		this.rawExchange = rawExchange;
		this.rawIn = new DataInputStream(rawExchange.in());
		this.rawOutLock = new Object();
		this.rawOut = new DataOutputStream(rawExchange.out());

		this.readerThread = new Thread(this::readerThread, "Multiplexer Reader");
		this.exchangesById = new AtomicReference<>(new ArrayList<>());
		readerThread.setDaemon(true);

		this.readyExchanges = new LinkedBlockingQueue<>();

		this.state = new AtomicReference<>(State.OPEN);
		this.ioException = new AtomicReference<>();

		readerThread.start();
	}


	/**
	 * readerThread must never wait for a write, otherwise deadlocks can occur
	 * because the readerThreads of both sides could both be waiting
	 * for the other to accept the write they're waiting for.
	 * <p>
	 * readerThread synchronizes on {@link #state}.
	 * Because of this, none of the writeXYZ methods must be called while this lock is held.
	 */
	private void readerThread()
	{
		List<MultiplexedExchange> exchangesById = this.exchangesById.get();

		try
		{
			while(state.get() == State.OPEN)
			{
				int exchangeId = rawIn.readInt();
				if(exchangeId == 0)
					// make sure we aren't modifying exchangesById while close() runs
					synchronized(state)
					{
						if(state.get() != State.OPEN)
							break;
						MultiplexedExchange exchange = new MultiplexedExchange(this, exchangesById.size() + 1);
						exchangesById.add(exchange);
						readyExchanges.add(exchange);
						continue;
					}

				int len = rawIn.readInt();

				if((exchangeId & SIGN_BIT) != 0)
					recordReadyForReceiving(getExchange(exchangesById, exchangeId & ~SIGN_BIT), len);
				else
					recordReceivedData(getExchange(exchangesById, exchangeId), len);
			}
		} catch(IOException e)
		{
			// synchronized because we need to modify ioException conditionally as well
			synchronized(state)
			{
				// only if the multiplexer was open, exceptions (including EOF) have an effect
				if(state.get() == State.OPEN)
				{
					if(e instanceof EOFException)
					{
						state.set(State.GLOBAL_EOF);
						exchangesById.forEach(MultiplexedExchange::eofReached);
					} else
					{
						ioException.set(e);
						state.set(State.IO_EXCEPTION);
						exchangesById.forEach(MultiplexedExchange::ioExceptionThrown);
					}
				}
			}
		}
	}

	private MultiplexedExchange getExchange(List<MultiplexedExchange> exchangesById, int exchangeId) throws UnexpectedResponseException
	{
		if(exchangeId <= 0 || exchangeId > exchangesById.size())
			throw new UnexpectedResponseException("Illegal exchange ID: " + exchangeId);

		return exchangesById.get(exchangeId - 1);
	}

	private void recordReadyForReceiving(MultiplexedExchange exchange, int len) throws UnexpectedResponseException, IOException
	{
		if(DEBUG)
			debugOut(exchange.id(), "Receiving " + (len != 0 ? len + " bytes ready" : "EOF"));
		if(len == 0)
			exchange.outEofReached();
		else
			exchange.recordReadyForReceiving(len);
	}
	private void recordReceivedData(MultiplexedExchange exchange, int len) throws UnexpectedResponseException, IOException
	{
		if(DEBUG)
			debugIn(exchange.id(), "Receiving " + (len != 0 ? len + " bytes" : "EOF"));
		if(len == 0)
			exchange.inEofReached();
		else
			exchange.recordReceivedData(len, rawIn);
	}

	@Override
	public Exchange createNewExchange() throws IOException
	{
		if(state.get() != State.OPEN)
			return throwCreateNewExchangeNotOpen();

		writeNewExchangeReady();

		MultiplexedExchange exchange;
		try
		{
			exchange = readyExchanges.take();
		} catch(InterruptedException e)
		{
			// The other side thinks creating the exchange succeeded,
			// so it'll now have an exchange which will never read or write anything.
			// That's probably the best thing we can do;
			// this seems to be an instance of the Two General's Problem, which is unsolvable.
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while creating new exchange", e);
		}

		if(!exchange.isSentry())
			return exchange.asExchange();

		// exchange.isSentry() means the multiplexer shut down (closed, IOException, or EOF).
		// This will happen if the multiplexer is closed while createNewExchange
		// is after the synchronized block at the start, but before the other side responded.
		// Forward the information to other threads being in the same situation by re-enqueuing the sentry exchange.
		readyExchanges.add(exchange);
		return throwCreateNewExchangeNotOpen();
	}

	private Exchange throwCreateNewExchangeNotOpen() throws EOFException, UnexpectedResponseException, ClosedException, IOException
	{
		return switch(state.get())
		{
			case OPEN -> throw new IllegalStateException("Got null exchange, but multiplexer still open");
			case GLOBAL_EOF -> throw new EOFException();
			case IO_EXCEPTION -> throwIOException();
			case CLOSED -> throw new IOException("Closed");
		};
	}

	/**
	 * This must not be called while the caller holds {@link #state}; see {@link #readerThread}.
	 */
	void writeNewExchangeReady() throws IOException
	{
		synchronized(rawOutLock)
		{
			if(DEBUG)
				debug(-1, null, "Sending new stream ready");
			rawOut.writeInt(0);
			rawOut.flush();
		}
	}
	/**
	 * This must not be called while the caller holds {@link #state}; see {@link #readerThread}.
	 */
	void writeReadyForReceiving(int exchangeId, int len) throws IOException
	{
		synchronized(rawOutLock)
		{
			if(DEBUG)
				debugIn(exchangeId, "Sending " + len + " bytes ready");
			rawOut.writeInt(exchangeId | SIGN_BIT);
			rawOut.writeInt(len);
			rawOut.flush();
		}
	}
	/**
	 * This must not be called while the caller holds {@link #state}; see {@link #readerThread}.
	 */
	void writeBytes(int exchangeId, byte[] buf, int off, int len) throws IOException
	{
		synchronized(rawOutLock)
		{
			if(DEBUG)
				debugOut(exchangeId, "Sending " + len + " bytes: " + Arrays.toString(Arrays.copyOfRange(buf, off, off + len)));
			rawOut.writeInt(exchangeId);
			rawOut.writeInt(len);
			rawOut.write(buf, off, len);
			rawOut.flush();
		}
	}
	/**
	 * This must not be called while the caller holds {@link #state}; see {@link #readerThread}.
	 */
	void writeOutputEOF(int exchangeId) throws IOException
	{
		synchronized(rawOutLock)
		{
			if(DEBUG)
				debugOut(exchangeId, "Sending EOF");
			rawOut.writeInt(exchangeId);
			rawOut.writeInt(0);
			rawOut.flush();
		}
	}
	/**
	 * This must not be called while the caller holds {@link #state}; see {@link #readerThread}.
	 */
	void writeInputEOF(int exchangeId) throws IOException
	{
		synchronized(rawOutLock)
		{
			if(DEBUG)
				debugIn(exchangeId, "Sending EOF");
			rawOut.writeInt(exchangeId | SIGN_BIT);
			rawOut.writeInt(0);
			rawOut.flush();
		}
	}

	<R> R throwIOException() throws UnexpectedResponseException, ClosedException, IOException
	{
		IOException exception = ioException.get();

		if(UnexpectedResponseException.class.isAssignableFrom(exception.getClass()))
			throw new UnexpectedResponseException(exception.getMessage(), exception);
		if(ClosedException.class.isAssignableFrom(exception.getClass()))
			throw new ClosedException(exception.getMessage(), exception);

		throw new IOException(exception.getMessage(), exception);
	}

	@Override
	/**
	 * Closes this exchange pool and all of its created exchanges.
	 * Also closes the rawExchange provided in the constructor.
	 */
	public void close() throws IOException
	{
		try
		{
			// First, try to do an orderly shutdown:
			// set state to CLOSED, make createNewExchange impossible,
			// notify the reader thread, and close all created exchanges.
			// necessary to be synchronized because of the exception handling in of readerThread
			// and the way new exchanges are created
			synchronized(state)
			{
				State oldState = state.getAndSet(State.CLOSED);
				if(oldState != State.OPEN)
				{
					if(oldState == State.IO_EXCEPTION)
						// clean old reference
						ioException.set(null);
					return;
				}
				// notify createNewExchange this multiplexer is closed
				if(oldState != State.CLOSED)
					// The BlockingQueue we're using (LinkedBlockingQueue) does not support null entries.
					// So, we have to use sentry objects instead.
					readyExchanges.add(MultiplexedExchange.createSentry());

				readerThread.interrupt();
				exchangesById.get().forEach(MultiplexedExchange::closeWithoutSendingEOF);
			}
		} finally
		{
			// Then, close rawExchange.
			// Note that at this point it's still possible some thread reads from rawIn or writes to rawOut.
			rawExchange.close();
		}
	}

	private void debugIn(int exchangeId, String message) throws UnexpectedResponseException
	{
		debug(exchangeId, "In ", message);
	}
	private void debugOut(int exchangeId, String message) throws UnexpectedResponseException
	{
		debug(exchangeId, "Out", message);
	}
	private void debug(int exchangeId, String inOrOut, String message)
	{
		System.err.println((exchangeId >= 0 ? "Stream#" + exchangeId + " " : "") + (inOrOut != null ? inOrOut + ": " : "") + message);
	}

	static enum State
	{
		OPEN,
		GLOBAL_EOF,
		IO_EXCEPTION,
		CLOSED;
	}
}
