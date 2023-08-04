package net.haspamelodica.exchanges.pipes;

import java.util.concurrent.SynchronousQueue;
import java.util.function.Supplier;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.ExchangePool;
import net.haspamelodica.exchanges.SimpleExchangePool;
import net.haspamelodica.exchanges.util.AutoCloseablePair;

public class PipesExchangePool extends SimpleExchangePool
{
	private final Supplier<AutoCloseablePair<Exchange, Exchange>> openPiped;

	private final Client client;

	public PipesExchangePool()
	{
		this(Exchange::openPiped);
	}
	public PipesExchangePool(Supplier<AutoCloseablePair<Exchange, Exchange>> openPiped)
	{
		this.openPiped = openPiped;
		this.client = new Client();
	}

	@Override
	protected Exchange createExchangeInterruptible() throws InterruptedException
	{
		AutoCloseablePair<Exchange, Exchange> pipe = openPiped.get();
		addCloseAction(pipe::close);
		client.put(pipe.a());
		return pipe.b();
	}

	public ExchangePool getClient()
	{
		return client;
	}

	private static class Client extends SimpleExchangePool
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
}
