package net.haspamelodica.streammultiplexer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Objects;

public class MultiplexedOutputStream extends OutputStream implements WrappedMultiplexedOutputStream
{
	private final GenericStreamMultiplexer<?, ?>	multiplexer;
	private final int								streamID;

	private final Object	lock;
	private int				off;
	private int				len;
	private byte[]			buf;
	private State			state;

	MultiplexedOutputStream(GenericStreamMultiplexer<?, ?> multiplexer, int streamID, StreamMultiplexer.State state) throws ClosedException
	{
		this(multiplexer, streamID, switch(state)
		{
			case OPEN -> State.NOT_WRITING;
			case CLOSED -> throw new ClosedException();
			case GLOBAL_EOF -> State.EOF;
			case IO_EXCEPTION -> State.IO_EXCEPTION;
		});
	}
	MultiplexedOutputStream(GenericStreamMultiplexer<?, ?> multiplexer, int streamID)
	{
		this(multiplexer, streamID, State.NOT_WRITING);
	}
	private MultiplexedOutputStream(GenericStreamMultiplexer<?, ?> multiplexer, int streamID, State state)
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
	public MultiplexedOutputStream getWrappedStream()
	{
		return this;
	}

	@Override
	public void write(int b) throws IOException
	{
		write(new byte[] {(byte) b}, 0, 1);
	}

	/**
	 *
	 */
	@Override
	public void write(byte[] buf, int off, int len) throws UnexpectedResponseException, ClosedException, EOFException, InterruptedIOException, IOException
	{
		Objects.checkFromIndexSize(off, len, buf.length);
		if(len == 0)
			return;

		synchronized(lock)
		{
			switch(state)
			{
				case NOT_WRITING -> waitForReadyBytesChecked(buf, off, len);
				case WAITING_FOR_AVAILABLE_BYTES -> writeImmediately(buf, off, len);
				case WAITING_FOR_READY_BYTES, WRITE_FINISHED_THEN_NOT_WRITING, WRITE_FINISHED_THEN_WAITING_FOR_AVAILABLE_BYTES -> throw new IOException("Another thread is currently writing");
				case EOF -> throwEOF();
				case IO_EXCEPTION -> multiplexer.throwIOException();
				case CLOSED -> throwClosed();
			};
		}
	}

	private void writeImmediately(byte[] buf, int off, int len) throws UnexpectedResponseException, ClosedException, EOFException, InterruptedIOException, IOException
	{
		int lenToWrite = Math.min(len, this.len);

		multiplexer.writeBytes(streamID, buf, off, lenToWrite);
		off += lenToWrite;
		len -= lenToWrite;
		// the read request is now finished, whether this.len is now 0 or not

		if(len == 0)
		{
			state = State.NOT_WRITING;
			return;
		}

		waitForReadyBytesChecked(buf, off, len);
	}

	private void waitForReadyBytesChecked(byte[] buf, int off, int len) throws UnexpectedResponseException, ClosedException, EOFException, InterruptedIOException, IOException
	{
		this.buf = buf;
		this.off = off;
		this.len = len;
		this.state = State.WAITING_FOR_READY_BYTES;

		for(;;)
		{
			try
			{
				lock.wait();
			} catch(InterruptedException e)
			{
				throw new InterruptedIOException();
			}

			switch(state)
			{
				case NOT_WRITING, WAITING_FOR_AVAILABLE_BYTES -> throw new IllegalStateException("Output stream in impossible state");
				case WAITING_FOR_READY_BYTES ->
				{
					continue;
				}
				case WRITE_FINISHED_THEN_WAITING_FOR_AVAILABLE_BYTES ->
				{
					state = State.WAITING_FOR_AVAILABLE_BYTES;
				}
				case WRITE_FINISHED_THEN_NOT_WRITING ->
				{
					state = State.NOT_WRITING;
				}
				case EOF -> throwEOF();
				case IO_EXCEPTION -> multiplexer.throwIOException();
				case CLOSED -> throwClosed();
			};
			return;
		}
	}

	void recordReadyForReceiving(int len) throws UnexpectedResponseException, IOException
	{
		synchronized(lock)
		{
			if(len <= 0)
				throw new UnexpectedResponseException("ready bytes len <= 0");

			switch(state)
			{
				case NOT_WRITING ->
				{
					this.len = len;
					state = State.WAITING_FOR_AVAILABLE_BYTES;
				}
				case WAITING_FOR_AVAILABLE_BYTES, WRITE_FINISHED_THEN_WAITING_FOR_AVAILABLE_BYTES -> throw new UnexpectedResponseException("Two reads occurred");
				case WAITING_FOR_READY_BYTES ->
				{
					int lenToWrite = Math.min(len, this.len);

					multiplexer.writeBytesSynchronized(streamID, buf, off, lenToWrite);
					off += lenToWrite;
					this.len -= lenToWrite;

					// the read request is now finished, whether len is now 0 or not
					if(this.len == 0)
					{
						// don't keep unneccessary reference
						this.buf = null;
						state = State.WRITE_FINISHED_THEN_NOT_WRITING;
						lock.notify();
					}
				}
				case WRITE_FINISHED_THEN_NOT_WRITING ->
				{
					// Another read request could occur before the writing thread gets the lock and resets state to NOT_WRITING.
					// So, we need to handle this case specially.
					this.len = len;
					state = State.WRITE_FINISHED_THEN_WAITING_FOR_AVAILABLE_BYTES;
				}
				case EOF -> throw new UnexpectedResponseException("Got data although we are EOF");
				case IO_EXCEPTION -> multiplexer.throwIOException();
				case CLOSED ->
				{
					// ignore, we already sent the EOF
				}
			}
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

	private <R> R throwEOF() throws EOFException
	{
		throw new EOFException("The corresponding input stream was closed");
	}

	private <R> R throwClosed() throws ClosedException
	{
		throw new ClosedException();
	}

	@Override
	public void close() throws IOException
	{
		if(closeWithoutSendingEOF())
			multiplexer.writeOutputEOFSynchronized(streamID);
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

	private static enum State
	{
		NOT_WRITING,
		WAITING_FOR_AVAILABLE_BYTES,
		WAITING_FOR_READY_BYTES,
		WRITE_FINISHED_THEN_WAITING_FOR_AVAILABLE_BYTES,
		WRITE_FINISHED_THEN_NOT_WRITING,
		EOF,
		IO_EXCEPTION,
		CLOSED;
	}
}
