package net.haspamelodica.exchanges.fifos;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.SimpleExchangePool;

public class FifosExchangePoolServer extends SimpleExchangePool
{
	private final Path			fifosDir;
	private final AtomicInteger	nextExchangeId;

	private final OutputStream controlOut;

	public FifosExchangePoolServer(Path fifosDir, Path controlFifo) throws IOException
	{
		this.fifosDir = fifosDir;
		this.nextExchangeId = new AtomicInteger();
		this.controlOut = Exchange.openFifoOutput(controlFifo);
	}

	@Override
	protected Exchange createExchangeInterruptible() throws IOException, InterruptedException
	{
		int id = nextExchangeId.getAndIncrement();
		String serverToClientFifoName = id + "_s2c";
		String clientToServerFifoName = id + "_c2s";

		Path serverToClientFifo = fifosDir.resolve(serverToClientFifoName);
		Path clientToServerFifo = fifosDir.resolve(clientToServerFifoName);

		if(!Files.exists(serverToClientFifo))
			mkfifo(fifosDir, serverToClientFifoName);
		if(!Files.exists(clientToServerFifo))
			mkfifo(fifosDir, clientToServerFifoName);
		controlOut.write(0);
		controlOut.flush();

		return Exchange.openFifos(true, clientToServerFifo, serverToClientFifo);
	}

	@Override
	public void close() throws IOException
	{
		// Closing control stream first to make sure threads blocked in createExchangeInterruptible wake up
		try
		{
			controlOut.close();
		} finally
		{
			super.close();
		}
	}

	//TODO this is EXTREMELY ugly! Is there really no way to create a fifo in Java?
	public static void mkfifo(Path dir, String name) throws IOException, InterruptedException
	{
		Process mkfifo = new ProcessBuilder("mkfifo", "-m=0666", name)
				.directory(dir.toFile())
				.redirectInput(Redirect.PIPE)
				.redirectOutput(Redirect.DISCARD)
				.redirectError(Redirect.DISCARD)
				.start();
		// don't cause deadlocks if mkfifo should, for some weird reason, try to read from its stdin
		mkfifo.getInputStream().close();

		int exitCode = mkfifo.waitFor();
		if(exitCode != 0)
			throw new IOException("mkfifo process failed");
	}
}
