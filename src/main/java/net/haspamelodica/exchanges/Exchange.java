package net.haspamelodica.exchanges;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.haspamelodica.exchanges.util.AutoClosablePair;

public record Exchange(InputStream in, OutputStream out) implements AutoCloseable
{
	@Override
	public void close() throws IOException
	{
		try
		{
			in.close();
		} finally
		{
			out.close();
		}
	}

	public Exchange wrapBuffered()
	{
		return new Exchange(new BufferedInputStream(in), new BufferedOutputStream(out));
	}

	public DataExchange wrapData()
	{
		return DataExchange.from(this);
	}

	public static AutoClosablePair<Exchange, Exchange> openPiped()
	{
		// java.io.Piped[In|Out]putStream's don't work with multiple threads (they cause random IOExceptions with message "Pipe broken").
		@SuppressWarnings("resource")
		Pipe pipe1 = new Pipe();
		@SuppressWarnings("resource")
		Pipe pipe2 = new Pipe();
		return new AutoClosablePair<>(
				new Exchange(pipe1.in(), pipe2.out()),
				new Exchange(pipe2.in(), pipe1.out()));
	}
}
