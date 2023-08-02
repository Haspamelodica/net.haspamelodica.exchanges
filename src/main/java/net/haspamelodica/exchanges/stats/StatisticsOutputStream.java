package net.haspamelodica.exchanges.stats;

import java.io.IOException;
import java.io.OutputStream;

public class StatisticsOutputStream extends OutputStream
{
	private final OutputStream out;

	private int	opCount;
	private int	flushCount;
	private int	byteCount;
	private int	errorCount;

	public StatisticsOutputStream(OutputStream out)
	{
		this.out = out;
	}

	public int getOpCount()
	{
		return opCount;
	}
	public int getFlushCount()
	{
		return flushCount;
	}
	public int getByteCount()
	{
		return byteCount;
	}
	public int getErrorCount()
	{
		return errorCount;
	}

	@Override
	public void write(int b) throws IOException
	{
		try
		{
			out.write(b);
		} catch(IOException | RuntimeException e)
		{
			errorCount ++;
			throw e;
		}

		opCount ++;
		byteCount ++;
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException
	{
		try
		{
			out.write(b, off, len);
		} catch(IOException | RuntimeException e)
		{
			errorCount ++;
			throw e;
		}

		opCount ++;
		byteCount += len;
	}

	@Override
	public void flush() throws IOException
	{
		try
		{
			out.flush();
		} catch(IOException | RuntimeException e)
		{
			errorCount ++;
			throw e;
		}

		flushCount ++;
	}
}
