package net.haspamelodica.exchanges.multiplexed;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;

public class MultiplexedInputStream extends InputStream
{
	private final MultiplexedExchangePool	multiplexer;
	private final int						exchangeId;

	private final Object	lock;
	private int				off;
	private int				len;
	private byte[]			buf;
	private State			state;

	MultiplexedInputStream(MultiplexedExchangePool multiplexer, int exchangeId, MultiplexedExchangePool.State state) throws ClosedException
	{
		this(multiplexer, exchangeId, switch(state)
		{
			case OPEN -> State.NOT_READING;
			case CLOSED -> throw new ClosedException();
			case GLOBAL_EOF -> State.EOF;
			case IO_EXCEPTION -> State.IO_EXCEPTION;
		});
	}
	private MultiplexedInputStream(MultiplexedExchangePool multiplexer, int exchangeId, State state)
	{
		this.multiplexer = multiplexer;
		this.exchangeId = exchangeId;

		this.lock = new Object();
		this.state = state;
	}

	@Override
	public int read() throws IOException
	{
		byte[] buf = new byte[1];
		int len = read(buf, 0, 1);
		if(len == 1)
			return buf[0] & 0xFF;
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
				case WAITING_FOR_RESPONSE, BYTES_READY, BYTES_READY_THEN_EOF -> throw new IOException("Another thread is currently reading");
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

		multiplexer.writeReadyForReceiving(exchangeId, len);

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
				case NOT_READING -> throw new IllegalStateException("Input stream in impossible state");
				case WAITING_FOR_RESPONSE ->
				{
					continue;
				}
				case BYTES_READY ->
				{
					state = State.NOT_READING;
					return this.len;
				}
				case BYTES_READY_THEN_EOF ->
				{
					state = State.EOF;
					return this.len;
				}
				case EOF ->
				{
					return -1;
				}
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
					if(MultiplexedExchangePool.DEBUG)
						System.err.println("Skipped " + len + " bytes");
				} else
					throw new UnexpectedResponseException("Currently not waiting for a response");
			}
			if(len > this.len)
				throw new UnexpectedResponseException("received data len > ready len");
			// realRead is only not len if EOF happens.
			int realRead = in.readNBytes(this.buf, this.off, len);
			if(MultiplexedExchangePool.DEBUG)
				System.err.println("Read " + realRead + " bytes: " + Arrays.toString(Arrays.copyOfRange(buf, off, off + realRead)));
			this.len = realRead;
			// don't keep unnecessary reference
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

			// don't keep unnecessary reference
			this.buf = null;
			state = state == State.BYTES_READY ? State.BYTES_READY_THEN_EOF : State.EOF;
			lock.notify();
		}
	}
	void ioExceptionThrown()
	{
		synchronized(lock)
		{
			if(state == State.CLOSED)
				return;

			// don't keep unnecessary reference
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
			multiplexer.writeInputEOF(exchangeId);
	}
	boolean closeWithoutSendingEOF()
	{
		synchronized(lock)
		{
			if(state == State.CLOSED)
				return false;

			// don't keep unnecessary reference
			buf = null;
			state = State.CLOSED;
			lock.notify();
			return true;
		}
	}

	private static enum State
	{
		NOT_READING,
		WAITING_FOR_RESPONSE,
		BYTES_READY,
		BYTES_READY_THEN_EOF,
		EOF,
		IO_EXCEPTION,
		CLOSED;
	}
}
