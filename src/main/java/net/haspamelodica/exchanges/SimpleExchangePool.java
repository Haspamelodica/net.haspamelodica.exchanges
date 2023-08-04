package net.haspamelodica.exchanges;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.haspamelodica.exchanges.util.IORunnable;

public abstract class SimpleExchangePool implements ExchangePool
{
	private final List<Exchange>	handedOutExchanges;
	private final AtomicBoolean		closed;
	private final Set<Thread>		threadsWaitingForNewExchange;
	private final List<IORunnable>	closeActions;

	public SimpleExchangePool()
	{
		this.handedOutExchanges = Collections.synchronizedList(new ArrayList<>());
		this.closed = new AtomicBoolean();
		this.threadsWaitingForNewExchange = Collections.synchronizedSet(new HashSet<>());
		this.closeActions = Collections.synchronizedList(new ArrayList<>());
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

	protected void addCloseAction(IORunnable closeAction)
	{
		closeActions.add(closeAction);
	}

	public void close() throws IOException
	{
		closed.set(true);
		threadsWaitingForNewExchange.forEach(Thread::interrupt);
		//TODO we should make sure all are closed even if one close fails
		for(Exchange exchage : handedOutExchanges)
			exchage.close();
		for(IORunnable closeAction : closeActions)
			closeAction.run();
	}
}
