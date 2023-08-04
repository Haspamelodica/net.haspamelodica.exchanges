package net.haspamelodica.exchanges.sharedmem;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.ExchangePool;
import net.haspamelodica.exchanges.SimpleExchangePool;
import net.haspamelodica.exchanges.util.AutoCloseableByteBuffer;

public class SharedMemoryExchangePool extends SimpleExchangePool
{
	public static final int DEFAULT_BUFSIZE_PER_EXCHANGE_DIRECTION = 4096;

	private final ExchangePool	slowExchangePool;
	private final FileChannel	sharedFileChannel;
	private final boolean		isServer;
	private final int			bufsizePerExchangeDirectionIncludingOverhead;
	private final AtomicInteger	nextMappingPosition;

	public SharedMemoryExchangePool(ExchangePool slowExchangePool, Path sharedFile, boolean isServer,
			OpenOption... extraOpenOptions) throws IOException
	{
		this(slowExchangePool, sharedFile, isServer, DEFAULT_BUFSIZE_PER_EXCHANGE_DIRECTION, extraOpenOptions);
	}
	public SharedMemoryExchangePool(ExchangePool slowExchangePool, Path sharedFile, boolean isServer, int bufsizePerExchangeDirection,
			OpenOption... extraOpenOptions) throws IOException
	{
		this(slowExchangePool, FileChannel.open(sharedFile, Stream.concat(Stream.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
				Stream.of(extraOpenOptions)).toArray(OpenOption[]::new)), isServer, bufsizePerExchangeDirection);
	}
	public SharedMemoryExchangePool(ExchangePool slowExchangePool, FileChannel sharedFileChannel, boolean isServer, int bufsizePerExchangeDirection)
	{
		this.slowExchangePool = slowExchangePool;
		this.sharedFileChannel = sharedFileChannel;
		this.isServer = isServer;
		this.bufsizePerExchangeDirectionIncludingOverhead = SharedMemoryCommon.BUFSIZE_OVERHEAD + bufsizePerExchangeDirection;
		this.nextMappingPosition = new AtomicInteger();
		addCloseAction(slowExchangePool::close);
		addCloseAction(sharedFileChannel::close);
	}

	@Override
	protected Exchange createExchangeInterruptible() throws IOException, InterruptedException
	{
		// the null value will never be used, but makes the compiler happy
		SharedMemoryInputStream in = null;
		if(isServer)
			in = new SharedMemoryInputStream(slowExchangePool.createNewExchange(), nextMapping());
		SharedMemoryOutputStream out = new SharedMemoryOutputStream(slowExchangePool.createNewExchange(), nextMapping());
		if(!isServer)
			in = new SharedMemoryInputStream(slowExchangePool.createNewExchange(), nextMapping());
		return Exchange.ofNoExtraCloseAction(in, out);
	}

	private AutoCloseableByteBuffer nextMapping() throws IOException
	{
		return AutoCloseableByteBuffer.wrapNoCloseAction(sharedFileChannel.map(MapMode.READ_WRITE,
				nextMappingPosition.getAndAdd(bufsizePerExchangeDirectionIncludingOverhead), bufsizePerExchangeDirectionIncludingOverhead));
	}
}
