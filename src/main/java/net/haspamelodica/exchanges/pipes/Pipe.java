package net.haspamelodica.exchanges.pipes;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.Semaphore;

import net.haspamelodica.exchanges.Exchange;

public class Pipe implements AutoCloseable
{
	private final Semaphore	writingReadySemaphore;
	private final Semaphore	readingReadySemaphore;
	private final Semaphore	readingFinishedSemaphore;

	private boolean	closed;
	private boolean	writtenSingleValue;
	private byte	writtenValue;
	private byte[]	writtenBuf;
	private int		writtenOff;
	private int		writtenLen;

	public Pipe()
	{
		writingReadySemaphore = new Semaphore(1);
		readingReadySemaphore = new Semaphore(0);
		readingFinishedSemaphore = new Semaphore(0);
	}

	public InputStream in()
	{
		return new InputStream()
		{
			@Override
			public int read() throws IOException
			{
				if(acquireIOAndCheckClosed(readingReadySemaphore))
					return -1;

				if(writtenSingleValue)
				{
					int result = writtenValue & 0xFF;
					writingReadySemaphore.release();
					return result;
				}

				int oldWrittenLen = writtenLen;
				if(oldWrittenLen > 1)
				{
					writtenLen = oldWrittenLen - 1;
					int result = writtenBuf[writtenOff ++] & 0xFF;
					readingReadySemaphore.release();
					return result;
				}

				int result = writtenBuf[writtenOff] & 0xFF;
				writtenBuf = null;
				readingFinishedSemaphore.release();
				return result;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException
			{
				Objects.checkFromIndexSize(off, len, b.length);
				if(len == 0)
					return 0;
				// from here: len > 1

				if(acquireIOAndCheckClosed(readingReadySemaphore))
					return -1;

				if(writtenSingleValue)
				{
					b[off] = writtenValue;
					writingReadySemaphore.release();
					return 1;
				}

				int oldWrittenLen = writtenLen;
				if(oldWrittenLen > len)
				{
					writtenLen = oldWrittenLen - len;
					System.arraycopy(writtenBuf, writtenOff, b, off, len);
					writtenOff += len;
					readingReadySemaphore.release();
					return len;
				}

				System.arraycopy(writtenBuf, writtenOff, b, off, oldWrittenLen);
				writtenBuf = null;
				readingFinishedSemaphore.release();
				return oldWrittenLen;
			}

			@Override
			public void close()
			{
				Pipe.this.close();
			}
		};
	}

	public OutputStream out()
	{
		return new OutputStream()
		{
			@Override
			public void write(int b) throws IOException
			{
				if(acquireIOAndCheckClosed(writingReadySemaphore))
					throw new EOFException();
				writtenSingleValue = true;
				writtenValue = (byte) b;
				readingReadySemaphore.release();

				// no need to wait for readingFinished
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException
			{
				Objects.checkFromIndexSize(off, len, b.length);
				if(acquireIOAndCheckClosed(writingReadySemaphore))
					throw new EOFException();

				if(len == 0)
				{
					writingReadySemaphore.release();
					return;
				}
				// from here: len > 0

				writtenSingleValue = false;
				writtenBuf = b;
				writtenOff = off;
				writtenLen = len;
				readingReadySemaphore.release();

				// Make sure we're only returning when all bytes have been read.
				// This is neccessary because otherwise the caller could modify the buffer.
				if(acquireIOAndCheckClosed(readingFinishedSemaphore))
				{
					writtenBuf = null;
					throw new EOFException();
				}
				writingReadySemaphore.release();
			}

			@Override
			public void close()
			{
				Pipe.this.close();
			}
		};
	}

	private boolean acquireIOAndCheckClosed(Semaphore semaphore) throws InterruptedIOException
	{
		if(closed)
			return true;

		try
		{
			semaphore.acquire();
		} catch(InterruptedException e)
		{
			throw new InterruptedIOException();
		}
		if(closed)
		{
			semaphore.release();
			return true;
		}

		return false;
	}

	public Exchange asExchange()
	{
		return Exchange.of(in(), out(), this::close);
	}

	@Override
	public void close()
	{
		closed = true;
		writtenBuf = null;
		writingReadySemaphore.release();
		readingReadySemaphore.release();
		readingFinishedSemaphore.release();
	}
}
