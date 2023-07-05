package net.haspamelodica.exchanges;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import net.haspamelodica.exchanges.util.AutoCloseablePair;

public class MultiplePipesExchangePool implements ExchangePool
{
	private final Client									client;
	private final InterruptibleCloseableExchangeSupplier	exchangeSupplier;

	public MultiplePipesExchangePool()
	{
		this.client = new Client();
		this.exchangeSupplier = new InterruptibleCloseableExchangeSupplier()
		{
			@Override
			protected Exchange createExchangeInterruptible() throws InterruptedException
			{
				AutoCloseablePair<Exchange, Exchange> pipe = Exchange.openPiped();
				client.put(pipe.a());
				return pipe.b();
			}
		};
	}

	@Override
	public Exchange createNewExchange() throws IOException
	{
		return exchangeSupplier.createNewExchange();
	}

	@Override
	public void close() throws IOException
	{
		exchangeSupplier.close();
	}

	public ExchangePool getClient()
	{
		return client;
	}

	private static class Client extends InterruptibleCloseableExchangeSupplier implements ExchangePool
	{
		private final SynchronousQueue<Exchange> clientExchanges;

		public Client()
		{
			this.clientExchanges = new SynchronousQueue<>();
		}

		public void put(Exchange exchange) throws InterruptedException
		{
			clientExchanges.put(exchange);
		}

		@Override
		protected Exchange createExchangeInterruptible() throws InterruptedException
		{
			return clientExchanges.take();
		}
	}

	private static abstract class InterruptibleCloseableExchangeSupplier
	{
		private final List<Exchange>	handedOutExchanges;
		private final AtomicBoolean		closed;
		private final Set<Thread>		threadsWaitingForNewExchange;

		public InterruptibleCloseableExchangeSupplier()
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

		protected abstract Exchange createExchangeInterruptible() throws InterruptedException;

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
}
