package net.haspamelodica.exchanges;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import net.haspamelodica.exchanges.util.AutoCloseablePair;

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

	/**
	 * For fifos, this method should be used instead of {@link Files#newInputStream(Path, java.nio.file.OpenOption...)}
	 * because of a bug in the JDK: https://bugs.openjdk.org/browse/JDK-8233451
	 */
	public static Exchange openFifos(boolean inFirst, Path inPath, Path outPath) throws IOException
	{
		InputStream in;
		OutputStream out;
		if(inFirst)
		{
			in = openFifoInput(inPath);
			try
			{
				out = openFifoOutput(outPath);
			} catch(IOException | RuntimeException e)
			{
				try
				{
					in.close();
				} catch(IOException | RuntimeException e2)
				{
					e.addSuppressed(e2);
				}
				throw e;
			}
		} else
		{
			out = openFifoOutput(outPath);
			try
			{
				in = openFifoInput(inPath);
			} catch(IOException | RuntimeException e)
			{
				try
				{
					out.close();
				} catch(IOException | RuntimeException e2)
				{
					e.addSuppressed(e2);
				}
				throw e;
			}
		}

		return new Exchange(in, out);
	}

	public Exchange wrapBuffered()
	{
		return new Exchange(new BufferedInputStream(in), new BufferedOutputStream(out));
	}

	public DataExchange wrapData()
	{
		return DataExchange.from(this);
	}

	public static AutoCloseablePair<Exchange, Exchange> openPiped()
	{
		// java.io.Piped[In|Out]putStream's don't work with multiple threads (they cause random IOExceptions with message "Pipe broken").
		@SuppressWarnings("resource")
		Pipe pipe1 = new Pipe();
		@SuppressWarnings("resource")
		Pipe pipe2 = new Pipe();
		return new AutoCloseablePair<>(
				new Exchange(pipe1.in(), pipe2.out()),
				new Exchange(pipe2.in(), pipe1.out()));
	}

	/**
	 * For fifos, this method should be used instead of {@link Files#newInputStream(Path, java.nio.file.OpenOption...)}
	 * because of a bug in the JDK: https://bugs.openjdk.org/browse/JDK-8233451
	 */
	public static InputStream openFifoInput(Path path) throws IOException
	{
		InputStream realIn = Files.newInputStream(path);
		return new InputStream()
		{
			@Override
			public int read(byte[] b, int off, int len) throws IOException
			{
				return realIn.read(b, off, len);
			}
			@Override
			public int read() throws IOException
			{
				return realIn.read();
			}
		};
	}
	/**
	 * For fifos, this method should be used instead of {@link Files#newInputStream(Path, java.nio.file.OpenOption...)}
	 * because of a bug in the JDK: https://bugs.openjdk.org/browse/JDK-8233451
	 */
	public static OutputStream openFifoOutput(Path path) throws IOException
	{
		OutputStream realOut = Files.newOutputStream(path);
		return new OutputStream()
		{
			@Override
			public void write(byte[] b, int off, int len) throws IOException
			{
				realOut.write(b, off, len);
			}
			@Override
			public void write(int b) throws IOException
			{
				realOut.write(b);
			}
		};
	}
}
