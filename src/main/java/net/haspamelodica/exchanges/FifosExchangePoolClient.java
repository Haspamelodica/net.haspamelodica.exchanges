package net.haspamelodica.exchanges;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class FifosExchangePoolClient extends SimpleExchangePool
{
	private final Path			fifosDir;
	private final AtomicInteger	nextExchangeId;

	private final InputStream controlIn;

	public FifosExchangePoolClient(Path fifosDir, Path controlFifo) throws IOException
	{
		this.fifosDir = fifosDir;
		this.nextExchangeId = new AtomicInteger();
		this.controlIn = Files.newInputStream(controlFifo);
	}

	@Override
	protected Exchange createExchangeInterruptible() throws IOException
	{
		int read = controlIn.read();
		if(read < 0)
			throw new EOFException("Control stream EOF; probably the server closed");
		if(read != 0)
			throw new IOException("Unexpected response from server: " + read);

		int id = nextExchangeId.getAndIncrement();
		String serverToClientFifoName = id + "_s2c";
		String clientToServerFifoName = id + "_c2s";

		InputStream in = Files.newInputStream(fifosDir.resolve(serverToClientFifoName));
		OutputStream out;
		try
		{
			out = Files.newOutputStream(fifosDir.resolve(clientToServerFifoName));
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
		return new Exchange(in, out);
	}

	@Override
	public void close() throws IOException
	{
		// Closing control stream first to make sure threads blocked in createExchangeInterruptible wake up
		try
		{
			controlIn.close();
		} finally
		{
			super.close();
		}
	}
}
