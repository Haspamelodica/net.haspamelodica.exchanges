package net.haspamelodica.streammultiplexer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiplexedInputStream extends InputStream implements WrappedMultiplexedInputStream
{
	private final GenericStreamMultiplexer<?, ?>	multiplexer;
	private final int								streamID;

	private final Object	lock;
	private int				off;
	private int				len;
	private byte[]			buf;
	private State			state;

	MultiplexedInputStream(GenericStreamMultiplexer<?, ?> multiplexer, int streamID, GenericStreamMultiplexer.State state) throws ClosedException
	{
		this(multiplexer, streamID, switch(state)
		{
			case OPEN -> State.NOT_READING;
			case CLOSED -> throw new ClosedException();
			case GLOBAL_EOF -> State.EOF;
			case IO_EXCEPTION -> State.IO_EXCEPTION;
		});
	}
	private MultiplexedInputStream(GenericStreamMultiplexer<?, ?> multiplexer, int streamID, State state)
	{
		this.multiplexer = multiplexer;
		this.streamID = streamID;

		this.lock = new Object();
		this.state = state;
	}

	@Override
	public int getStreamID()
	{
		return streamID;
	}

	@Override
	public MultiplexedInputStream getWrappedStream()
	{
		return this;
	}

	@Override
	public int read() throws IOException
	{
		byte[] buf = new byte[1];
		int len = read(buf, 0, 1);
		if(len == 1)
			return buf[0];
		return -1;
	}
	@Override
	public int read(byte[] buf, int off, int len) throws UnexpectedResponseException, ClosedException, InterruptedIOException, IOException
	{
		Objects.checkFromIndexSize(off, len, buf.length);
		if(len == 0)
			return 0;

		synchronized(lock)
		{
			return switch(state)
			{
				case NOT_READING -> readChecked(buf, off, len);
				case WAITING_FOR_RESPONSE, BYTES_READY -> throw new IOException("Another thread is currently reading");
				case EOF -> -1;
				case IO_EXCEPTION -> multiplexer.throwIOException();
				case CLOSED -> throwClosed();
			};
		}
	}

	private int readChecked(byte[] buf, int off, int len) throws UnexpectedResponseException, ClosedException, InterruptedIOException, IOException
	{
		this.buf = buf;
		this.off = off;
		this.len = len;
		this.state = State.WAITING_FOR_RESPONSE;

		multiplexer.notifyReadyForReceiving(streamID, len);

		for(;;)
		{
			try
			{
				lock.wait();
			} catch(InterruptedException e)
			{
				throw new InterruptedIOException();
			}

			return switch(state)
			{
				case NOT_READING -> throw new IllegalStateException("Input stream in impossible state");
				case WAITING_FOR_RESPONSE ->
				{
					continue;
				}
				case BYTES_READY ->
				{
					state = State.NOT_READING;
					yield this.len;
				}
				case EOF -> -1;
				case IO_EXCEPTION -> multiplexer.throwIOException();
				case CLOSED -> throwClosed();
			};
		}
	}

	void recordReceivedData(int len, InputStream in) throws UnexpectedResponseException, IOException
	{
		synchronized(lock)
		{
			if(len <= 0)
				throw new UnexpectedResponseException("received data len <= 0");

			if(state != State.WAITING_FOR_RESPONSE)
			{
				// Special case: This input stream got closed during a read,
				// but the output end already prepared data.
				if(state == State.CLOSED)
				// In this case, skip data to keep stream valid.
				{
					in.skipNBytes(len);
					if(GenericStreamMultiplexer.DEBUG)
						System.err.println("Skipped " + len + " bytes");
				} else
					throw new UnexpectedResponseException("Currently not waiting for a response");
			}
			if(len > this.len)
				throw new UnexpectedResponseException("received data len > ready len");
			// realRead is only not len if EOF happens.
			int realRead = in.readNBytes(this.buf, this.off, len);
			if(GenericStreamMultiplexer.DEBUG)
				System.err.println("Read " + realRead + " bytes: " + Arrays.toString(Arrays.copyOfRange(buf, off, off + realRead)));
			this.len = realRead;
			// don't keep unneccessary reference
			this.buf = null;
			state = realRead == -1 ? State.EOF : State.BYTES_READY;
			lock.notify();
		}
	}

	void eofReached()
	{
		synchronized(lock)
		{
			if(state == State.CLOSED)
				return;

			// don't keep unneccessary reference
			this.buf = null;
			state = State.EOF;
			lock.notify();
		}
	}
	void ioExceptionThrown()
	{
		synchronized(lock)
		{
			if(state == State.CLOSED)
				return;

			// don't keep unneccessary reference
			this.buf = null;
			state = State.IO_EXCEPTION;
			lock.notify();
		}
	}

	private <R> R throwClosed() throws ClosedException
	{
		throw new ClosedException();
	}

	@Override
	public void close() throws IOException
	{
		if(closeWithoutSendingEOF())
			multiplexer.writeInputEOF(streamID);
	}
	boolean closeWithoutSendingEOF()
	{
		synchronized(lock)
		{
			if(state == State.CLOSED)
				return false;

			// don't keep unneccessary reference
			buf = null;
			state = State.CLOSED;
			lock.notify();
			return true;
		}
	}

	final AtomicInteger nextDebugEventID = new AtomicInteger();
	int nextDebugEventID()
	{
		return nextDebugEventID.incrementAndGet();
	}

	private static enum State
	{
		NOT_READING,
		WAITING_FOR_RESPONSE,
		BYTES_READY,
		EOF,
		IO_EXCEPTION,
		CLOSED;
	}
}
