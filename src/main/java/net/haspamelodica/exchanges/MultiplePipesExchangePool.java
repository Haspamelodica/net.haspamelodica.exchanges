package net.haspamelodica.exchanges;

import java.util.concurrent.SynchronousQueue;

import net.haspamelodica.exchanges.util.AutoCloseablePair;

public class MultiplePipesExchangePool extends SimpleExchangePool
{
	private final Client client;

	public MultiplePipesExchangePool()
	{
		this.client = new Client();
	}

	@Override
	protected Exchange createExchangeInterruptible() throws InterruptedException
	{
		AutoCloseablePair<Exchange, Exchange> pipe = Exchange.openPiped();
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
