package net.haspamelodica.streammultiplexer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Returned streams are partly thread-safe: External synchronization is only neccessary to make sure
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
public class GenericStreamMultiplexer<IN extends WrappedMultiplexedInputStream, OUT extends WrappedMultiplexedOutputStream> implements AutoCloseable
{
	static final boolean DEBUG = false;

	private static final int SIGN_BIT = Integer.MIN_VALUE;

	private final DataInputStream	rawIn;
	private final Object			rawOutLock;
	private final DataOutputStream	rawOut;

	private final Function<MultiplexedInputStream, IN>		inWrapper;
	private final Function<MultiplexedOutputStream, OUT>	outWrapper;

	private final Thread readerThread;

	private final List<IN>	inputStreams;
	private final List<OUT>	outputStreams;

	private final AtomicReference<State>		state;
	private final AtomicReference<IOException>	ioException;

	public GenericStreamMultiplexer(InputStream rawIn, OutputStream rawOut,
			Function<MultiplexedInputStream, IN> inWrapper, Function<MultiplexedOutputStream, OUT> outWrapper)
	{
		this.rawIn = new DataInputStream(rawIn);
		this.rawOutLock = new Object();
		this.rawOut = new DataOutputStream(rawOut);

		this.inWrapper = inWrapper;
		this.outWrapper = outWrapper;

		this.readerThread = new Thread(this::readerThread);

		this.inputStreams = new ArrayList<>();
		this.outputStreams = new ArrayList<>();

		this.state = new AtomicReference<>(State.OPEN);
		this.ioException = new AtomicReference<>();

		// no synchronization neccessary yet
		outputStreams.add(outWrapper.apply(new MultiplexedOutputStream(this, 0)));

		readerThread.start();
	}

	private void readerThread()
	{
		try
		{
			while(state.get() == State.OPEN)
			{
				int streamID = rawIn.readInt();
				int len = rawIn.readInt();

				if((streamID & SIGN_BIT) != 0)
					recordReadyForReceiving(streamID & ~SIGN_BIT, len);
				else
					recordReceivedData(streamID, len);
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
						iterateAllStreams(MultiplexedInputStream::eofReached, MultiplexedOutputStream::eofReached);
					} else
					{
						ioException.set(e);
						state.set(State.IO_EXCEPTION);
						iterateAllStreams(MultiplexedInputStream::ioExceptionThrown, MultiplexedOutputStream::ioExceptionThrown);
					}
				}
			}
		}
	}

	public IN getIn(int streamID) throws ClosedException
	{
		return getOrCreateElementThreadsafe(inputStreams, streamID, MultiplexedInputStream::new, inWrapper);
	}

	public OUT getOut(int streamID) throws ClosedException
	{
		return getOrCreateElementThreadsafe(outputStreams, streamID, MultiplexedOutputStream::new, outWrapper);
	}

	private void recordReadyForReceiving(int streamID, int len) throws UnexpectedResponseException, IOException
	{
		if(DEBUG)
			debugOut(streamID, "Receiving " + len + " bytes ready");
		MultiplexedOutputStream outputStream = getExistingStreamThreadsafe(outputStreams, streamID, "output").getWrappedStream();
		if(len == 0)
			outputStream.eofReached();
		else
			outputStream.recordReadyForReceiving(len);
	}
	private void recordReceivedData(int streamID, int len) throws UnexpectedResponseException, IOException
	{
		if(DEBUG)
			debugIn(streamID, "Receiving " + (len != 0 ? len + " bytes" : "EOF"));
		MultiplexedInputStream inputStream = getExistingStreamThreadsafe(inputStreams, streamID, "input").getWrappedStream();
		if(len == 0)
			inputStream.eofReached();
		else
			inputStream.recordReceivedData(len, rawIn);
	}

	void notifyReadyForReceiving(int streamID, int len) throws IOException
	{
		synchronized(rawOutLock)
		{
			if(DEBUG)
				debugIn(streamID, "Sending " + len + " bytes ready");
			rawOut.writeInt(streamID | SIGN_BIT);
			rawOut.writeInt(len);
		}
	}
	void writeBytes(int streamID, byte[] buf, int off, int len) throws IOException
	{
		synchronized(rawOutLock)
		{
			if(DEBUG)
				debugOut(streamID, "Sending " + len + " bytes: " + Arrays.toString(Arrays.copyOfRange(buf, off, off + len)));
			rawOut.writeInt(streamID);
			rawOut.writeInt(len);
			rawOut.write(buf, off, len);
		}
	}
	void writeOutputEOF(int streamID) throws IOException
	{
		synchronized(rawOutLock)
		{
			if(DEBUG)
				debugOut(streamID, "Sending EOF");
			rawOut.writeInt(streamID);
			rawOut.writeInt(0);
		}
	}
	void writeInputEOF(int streamID) throws IOException
	{
		synchronized(rawOutLock)
		{
			if(DEBUG)
				debugIn(streamID, "Sending EOF");
			rawOut.writeInt(streamID | SIGN_BIT);
			rawOut.writeInt(0);
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
	 * Closes this multiplexer and all of its children streams.
	 * Does not close rawIn and rawOut provided in the constructor.
	 */
	public void close()
	{
		State oldState;
		// neccessary to be synchronized because readerThread
		synchronized(state)
		{
			oldState = state.getAndSet(State.CLOSED);
		}
		if(oldState != State.OPEN)
		{
			if(oldState == State.IO_EXCEPTION)
				// clean old reference
				ioException.set(null);
			return;
		}

		iterateAllStreams(MultiplexedInputStream::closeWithoutSendingEOF, MultiplexedOutputStream::closeWithoutSendingEOF);

		readerThread.interrupt();
	}

	private void iterateAllStreams(Consumer<MultiplexedInputStream> inputStreamAction, Consumer<MultiplexedOutputStream> outputStreamAction)
	{
		synchronized(inputStreams)
		{
			for(IN inputStream : inputStreams)
				if(inputStream != null)
					inputStreamAction.accept(inputStream.getWrappedStream());
		}
		synchronized(outputStreams)
		{
			for(OUT outputStream : outputStreams)
				if(outputStream != null)
					outputStreamAction.accept(outputStream.getWrappedStream());
		}
	}

	private <S, E> E getOrCreateElementThreadsafe(List<E> list, int streamID, StreamConstructor<S> newStream, Function<S, E> streamWrapper) throws ClosedException
	{
		if(streamID < 0)
			throw new IllegalArgumentException("Illegal ID, must be >= 0: " + streamID);
		synchronized(list)
		{
			while(streamID >= list.size())
				list.add(null);

			E element = list.get(streamID);
			if(element != null)
				return element;

			element = streamWrapper.apply(newStream.newStream(this, streamID, state.get()));
			list.set(streamID, element);
			return element;
		}
	}

	private static <E> E getExistingStreamThreadsafe(List<E> list, int id, String streamTypeForErrors) throws UnexpectedResponseException
	{
		synchronized(list)
		{
			if(id >= 0 && id < list.size())
			{
				E e = list.get(id);
				if(e != null)
					return e;
			}
			throw new UnexpectedResponseException("Nonexistant " + streamTypeForErrors + " stream with ID " + id + " requested");
		}
	}

	static interface OpenStreamConstructor<E>
	{
		public E newStream(GenericStreamMultiplexer<?, ?> multiplexer, int streamID);
	}
	static interface StreamConstructor<E>
	{
		public E newStream(GenericStreamMultiplexer<?, ?> multiplexer, int streamID, State state) throws ClosedException;
	}

	private void debugIn(int streamID, String message) throws UnexpectedResponseException
	{
		IN existingStreamThreadsafe = getExistingStreamThreadsafe(inputStreams, streamID, "debug");
		int eventID = existingStreamThreadsafe.getWrappedStream().nextDebugEventID();
		debug(streamID, eventID, "In ", message);
	}
	private void debugOut(int streamID, String message) throws UnexpectedResponseException
	{
		OUT existingStreamThreadsafe = getExistingStreamThreadsafe(outputStreams, streamID, "debug");
		int eventID = existingStreamThreadsafe.getWrappedStream().nextDebugEventID();
		debug(streamID, eventID, "Out", message);
	}
	private void debug(int streamID, int eventID, String inOrOut, String message)
	{
		System.err.println("Stream#" + streamID + " " + inOrOut + " #" + eventID + ": " + message);
	}

	static enum State
	{
		OPEN,
		GLOBAL_EOF,
		IO_EXCEPTION,
		CLOSED;
	}
}
