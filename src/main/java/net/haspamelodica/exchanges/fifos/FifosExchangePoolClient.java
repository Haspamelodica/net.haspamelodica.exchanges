package net.haspamelodica.exchanges.fifos;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.SimpleExchangePool;

public class FifosExchangePoolClient extends SimpleExchangePool
{
	private final Path			fifosDir;
	private final AtomicInteger	nextExchangeId;

	private final InputStream controlIn;

	public FifosExchangePoolClient(Path fifosDir, Path controlFifo) throws IOException
	{
		this.fifosDir = fifosDir;
		this.nextExchangeId = new AtomicInteger();
		this.controlIn = Exchange.openFifoInput(controlFifo);
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

		return Exchange.openFifos(false, fifosDir.resolve(serverToClientFifoName), fifosDir.resolve(clientToServerFifoName));
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
