package net.haspamelodica.exchanges;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import net.haspamelodica.exchanges.pipes.Pipe;
import net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon;
import net.haspamelodica.exchanges.sharedmem.SharedMemoryInputStream;
import net.haspamelodica.exchanges.sharedmem.SharedMemoryOutputStream;
import net.haspamelodica.exchanges.stats.StatisticsExchange;
import net.haspamelodica.exchanges.util.AutoCloseableByteBuffer;
import net.haspamelodica.exchanges.util.AutoCloseablePair;
import net.haspamelodica.exchanges.util.IORunnable;

public interface Exchange extends AutoCloseable
{
	public static final boolean DEBUG_SLOW_EXCHANGE_FOR_SHAREDMEM_PIPE = false;

	public static final int DEFAULT_SHAREDMEM_BUFSIZE = 4096;

	public InputStream in();
	public OutputStream out();

	@Override
	public void close() throws IOException;

	public static Exchange of(InputStream in, OutputStream out)
	{
		return new ExchangeImpl(in, out, () ->
		{
			try
			{
				in.close();
			} finally
			{
				out.close();
			}
		});
	}

	public static Exchange of(InputStream in, OutputStream out, IORunnable closeAction)
	{
		return new ExchangeImpl(in, out, closeAction);
	}

	/**
	 * For fifos, this method should be used instead of {@link Files#newInputStream(Path, java.nio.file.OpenOption...)}
	 * and {@link Files#newOutputStream(Path, java.nio.file.OpenOption...)}
	 * because of a bug in the JDK: https://bugs.openjdk.org/browse/JDK-8233451.
	 * See also {@link #openFifoInput(Path)} and {@link #openFifoOutput(Path)}.
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

		return of(in, out);
	}

	public static AutoCloseablePair<Exchange, Exchange> openPipedNoSharedMemory()
	{
		// java.io.Piped[In|Out]putStream's don't work with multiple threads (they cause random IOExceptions with message "Pipe broken").
		@SuppressWarnings("resource")
		Pipe pipe1 = new Pipe();
		@SuppressWarnings("resource")
		Pipe pipe2 = new Pipe();
		return new AutoCloseablePair<>(
				of(pipe1.in(), pipe2.out()),
				of(pipe2.in(), pipe1.out()));
	}

	public static AutoCloseablePair<Exchange, Exchange> openPiped()
	{
		return openPiped(DEFAULT_SHAREDMEM_BUFSIZE);
	}
	public static AutoCloseablePair<Exchange, Exchange> openPiped(int bufsize)
	{
		Exchange pipe1 = openSharedMemoryPipe(bufsize);
		Exchange pipe2 = openSharedMemoryPipe(bufsize);
		return new AutoCloseablePair<>(
				of(pipe1.in(), pipe2.out()),
				of(pipe2.in(), pipe1.out()));
	}

	private static Exchange openSharedMemoryPipe(int bufsize)
	{
		AutoCloseablePair<Exchange, Exchange> slowPipe = openPipedNoSharedMemory();
		int bufsizeWithOverhead = SharedMemoryCommon.BUFSIZE_OVERHEAD + bufsize;
		AutoCloseableByteBuffer buf = AutoCloseableByteBuffer.wrapNoCloseAction(ByteBuffer.allocateDirect(bufsizeWithOverhead));

		// We can't create the input stream in the same thread as the output stream because they wait on each other.
		AtomicReference<SharedMemoryInputStream> inRef = new AtomicReference<>();
		AtomicReference<IOException> inCreationIOExceptionRef = new AtomicReference<>();
		AtomicReference<RuntimeException> inCreationRuntimeExceptionRef = new AtomicReference<>();
		Thread inCreatorThread = new Thread(() ->
		{
			try
			{
				Exchange slowExchange = slowPipe.a();
				if(DEBUG_SLOW_EXCHANGE_FOR_SHAREDMEM_PIPE)
					slowExchange = slowExchange.wrapStatistics(System.err, "slow a");
				inRef.set(new SharedMemoryInputStream(slowExchange, buf));
			} catch(IOException e)
			{
				inCreationIOExceptionRef.set(e);
			} catch(RuntimeException e)
			{
				inCreationRuntimeExceptionRef.set(e);
			}
		});
		inCreatorThread.setDaemon(true);
		inCreatorThread.start();

		SharedMemoryOutputStream out;
		try
		{
			Exchange slowExchange = slowPipe.b();
			if(DEBUG_SLOW_EXCHANGE_FOR_SHAREDMEM_PIPE)
				slowExchange = slowExchange.wrapStatistics(System.err, "slow b");
			out = new SharedMemoryOutputStream(slowExchange, buf);
		} catch(IOException e)
		{
			// This means that the slowPipe failed, which should not be possible.
			throw new UncheckedIOException(e);
		}

		try
		{
			inCreatorThread.join();
		} catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return closeThenThrow(out, new RuntimeException("Interrupted during creation of shared memory pipe"));
		}

		SharedMemoryInputStream in = inRef.get();
		if(in != null)
			return Exchange.of(in, out);

		IOException inCreationIOException = inCreationIOExceptionRef.get();
		if(inCreationIOException != null)
			return closeThenThrow(out, new UncheckedIOException(inCreationIOException));

		// this ref should not be null; if it is, that's also not a huge problem.
		return closeThenThrow(out, new RuntimeException("Error while creating input sharedmem input stream", inCreationRuntimeExceptionRef.get()));
	}
	private static <R> R closeThenThrow(SharedMemoryOutputStream out, RuntimeException thrown)
	{
		try
		{
			out.close();
		} catch(IOException e1)
		{
			thrown.addSuppressed(e1);
		}

		throw thrown;
	}

	public default Exchange wrapBuffered()
	{
		return of(new BufferedInputStream(in()), new BufferedOutputStream(out()), closeAction());
	}

	public default StatisticsExchange wrapStatistics()
	{
		return wrapStatistics(null);
	}
	public default StatisticsExchange wrapStatistics(PrintStream statsPrintOut)
	{
		return wrapStatistics(statsPrintOut, null);
	}
	public default StatisticsExchange wrapStatistics(PrintStream statsPrintOut, String prefix)
	{
		return StatisticsExchange.wrap(this, statsPrintOut, prefix);
	}

	public default DataExchange wrapData()
	{
		return DataExchange.from(this);
	}

	public default IORunnable closeAction()
	{
		return this::close;
	}

	/**
	 * For fifos, this method should be used instead of {@link Files#newInputStream(Path, java.nio.file.OpenOption...)}
	 * because of a bug in the JDK: https://bugs.openjdk.org/browse/JDK-8233451.
	 * See also {@link #openFifoOutput(Path)}.
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
			@Override
			public void close() throws IOException
			{
				realIn.close();
			}
		};
	}
	/**
	 * For fifos, this method should be used instead of {@link Files#newOutputStream(Path, java.nio.file.OpenOption...)}
	 * because of a bug in the JDK: https://bugs.openjdk.org/browse/JDK-8233451.
	 * See also {@link #openFifoInput(Path)}.
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
			@Override
			public void flush() throws IOException
			{
				realOut.flush();
			}
			@Override
			public void close() throws IOException
			{
				realOut.close();
			}
		};
	}
}
