package net.haspamelodica.exchanges.stats;

import java.io.IOException;
import java.io.InputStream;

public class StatisticsInputStream extends InputStream
{
	private final InputStream in;

	private int	opCount;
	private int	byteCount;
	private int	errorCount;

	public StatisticsInputStream(InputStream in)
	{
		this.in = in;
	}

	public int getOpCount()
	{
		return opCount;
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
	public int read() throws IOException
	{
		int result;
		try
		{
			result = in.read();
		} catch(IOException | RuntimeException e)
		{
			errorCount ++;
			throw e;
		}

		opCount ++;
		if(result >= 0)
			byteCount ++;
		return result;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException
	{
		int result;
		try
		{
			result = in.read(b, off, len);
		} catch(IOException | RuntimeException e)
		{
			errorCount ++;
			throw e;
		}

		opCount ++;
		if(result >= 0)
			byteCount += result;
		return result;
	}
}
