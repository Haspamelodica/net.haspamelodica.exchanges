package net.haspamelodica.exchanges;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class SimpleExchangePool implements ExchangePool
{
	private final List<Exchange>	handedOutExchanges;
	private final AtomicBoolean		closed;
	private final Set<Thread>		threadsWaitingForNewExchange;

	public SimpleExchangePool()
	{
		this.handedOutExchanges = Collections.synchronizedList(new ArrayList<>());
		this.closed = new AtomicBoolean();
		this.threadsWaitingForNewExchange = Collections.synchronizedSet(new HashSet<>());
	}

	public Exchange createNewExchange() throws IOException
	{
		if(Thread.interrupted())
			throw new InterruptedIOException();

		threadsWaitingForNewExchange.add(Thread.currentThread());
		if(closed.get())
			throw new IOException("Closed");
		try
		{
			Exchange exchange;
			try
			{
				exchange = createExchangeInterruptible();
			} catch(InterruptedException e)
			{
				if(closed.get())
					throw new IOException("Closed");
				throw new InterruptedIOException();
			}
			handedOutExchanges.add(exchange);
			return exchange;
		} finally
		{
			threadsWaitingForNewExchange.remove(Thread.currentThread());

			if(Thread.interrupted())
				throw new InterruptedIOException();
		}
	}

	protected abstract Exchange createExchangeInterruptible() throws IOException, InterruptedException;

	public void close() throws IOException
	{
		closed.set(true);
		threadsWaitingForNewExchange.forEach(Thread::interrupt);
		try
		{
			handedOutExchanges.forEach(t ->
			{
				try
				{
					t.close();
				} catch(IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});
		} catch(UncheckedIOException e)
		{
			throw e.getCause();
		}
	}
}
