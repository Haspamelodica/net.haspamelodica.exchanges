package net.haspamelodica.exchanges;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

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

	public static AutoClosablePair<Exchange, Exchange> openPiped() throws IOException
	{
		// java.io.Piped[In|Out]putStream's don't work with multiple threads (they cause random IOExceptions with message "Pipe broken").
		AtomicReference<Socket> sock1Ref = new AtomicReference<>();
		AtomicReference<IOException> sock1IOExceptionRef = new AtomicReference<>();
		Thread sock1Thread;
		Socket sock2;
		try(ServerSocket server = new ServerSocket())
		{
			server.bind(null);
			sock1Thread = new Thread(() ->
			{
				try
				{
					sock1Ref.set(new Socket(InetAddress.getLocalHost(), server.getLocalPort()));
				} catch(IOException e)
				{
					sock1IOExceptionRef.set(e);
				}
			});
			sock1Thread.start();
			sock2 = server.accept();
		}
		try
		{
			sock1Thread.join();
		} catch(InterruptedException e)
		{
			throw new InterruptedIOException(e.getMessage());
		}
		Socket sock1 = sock1Ref.get();
		if(sock1 == null)
		{
			IOException sock1IOException = sock1IOExceptionRef.get();
			if(sock1IOException != null)
				throw sock1IOException;
			throw new IOException("Unknown error while creating piped exchanges");
		}
		return new AutoClosablePair<>(
				new Exchange(sock1.getInputStream(), sock1.getOutputStream()),
				new Exchange(sock2.getInputStream(), sock2.getOutputStream()));
	}
}
