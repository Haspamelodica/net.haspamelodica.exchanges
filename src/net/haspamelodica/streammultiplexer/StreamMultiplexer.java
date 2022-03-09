package net.haspamelodica.streammultiplexer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
public class StreamMultiplexer implements AutoCloseable
{
	private static final int SIGN_BIT = Integer.MIN_VALUE;

	private final DataInputStream	rawIn;
	private final Object			rawOutLock;
	private final DataOutputStream	rawOut;

	private final Thread readerThread;

	private final List<MultiplexedInputStream>	inputStreams;
	private final List<MultiplexedOutputStream>	outputStreams;

	private final AtomicReference<State>		state;
	private final AtomicReference<IOException>	ioException;

	public StreamMultiplexer(InputStream rawIn, OutputStream rawOut)
	{
		this.rawIn = new DataInputStream(rawIn);
		this.rawOutLock = new Object();
		this.rawOut = new DataOutputStream(rawOut);

		this.readerThread = new Thread(this::readerThread);

		this.inputStreams = new ArrayList<>();
		this.outputStreams = new ArrayList<>();

		this.state = new AtomicReference<>(State.OPEN);
		this.ioException = new AtomicReference<>();

		// no synchronization neccessary yet
		outputStreams.add(new MultiplexedOutputStream(this, 0));

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

	public InputStream getIn(int streamID) throws ClosedException
	{
		return getOrCreateElementThreadsafe(inputStreams, streamID, MultiplexedInputStream::new);
	}

	public OutputStream getOut(int streamID) throws ClosedException
	{
		return getOrCreateElementThreadsafe(outputStreams, streamID, MultiplexedOutputStream::new);
	}

	private void recordReadyForReceiving(int streamID, int len) throws UnexpectedResponseException, IOException
	{
		MultiplexedOutputStream outputStream = getExistingStreamThreadsafe(outputStreams, streamID, "output");
		if(len == 0)
			outputStream.eofReached();
		else
			outputStream.recordReadyForReceiving(len);
	}
	private void recordReceivedData(int streamID, int len) throws UnexpectedResponseException, IOException
	{
		MultiplexedInputStream inputStream = getExistingStreamThreadsafe(inputStreams, streamID, "input");
		if(len == 0)
			inputStream.eofReached();
		else
			inputStream.recordReceivedData(len, rawIn);
	}

	void notifyReadyForReceiving(int streamID, int len) throws IOException
	{
		synchronized(rawOutLock)
		{
			rawOut.writeInt(streamID | SIGN_BIT);
			rawOut.writeInt(len);
		}
	}
	void writeBytes(int streamID, byte[] buf, int off, int len) throws IOException
	{
		synchronized(rawOutLock)
		{
			writeBytesSynchronized(streamID, buf, off, len);
		}
	}
	void writeBytesSynchronized(int streamID, byte[] buf, int off, int len) throws IOException
	{
		rawOut.writeInt(streamID);
		rawOut.writeInt(len);
		rawOut.write(buf, off, len);
	}
	void writeOutputEOFSynchronized(int streamID) throws IOException
	{
		rawOut.writeInt(streamID);
		rawOut.writeInt(0);
	}
	void writeInputEOFSynchronized(int streamID) throws IOException
	{
		rawOut.writeInt(streamID | SIGN_BIT);
		rawOut.writeInt(0);
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
	public void close() throws IOException
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
		rawIn.close();
		rawOut.close();
	}

	private void iterateAllStreams(Consumer<MultiplexedInputStream> inputStreamAction, Consumer<MultiplexedOutputStream> outputStreamAction)
	{
		synchronized(inputStreams)
		{
			for(MultiplexedInputStream inputStream : inputStreams)
				if(inputStream != null)
					inputStreamAction.accept(inputStream);
		}
		synchronized(outputStreams)
		{
			for(MultiplexedOutputStream outputStream : outputStreams)
				if(outputStream != null)
					outputStreamAction.accept(outputStream);
		}
	}

	private <E> E getOrCreateElementThreadsafe(List<E> list, int streamID, StreamCreator<E> newStream) throws ClosedException
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

			element = newStream.newStream(this, streamID, state.get());
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

	private static interface StreamCreator<E>
	{
		public E newStream(StreamMultiplexer multiplexer, int streamID, State state) throws ClosedException;
	}

	static enum State
	{
		OPEN,
		GLOBAL_EOF,
		IO_EXCEPTION,
		CLOSED;
	}
}
