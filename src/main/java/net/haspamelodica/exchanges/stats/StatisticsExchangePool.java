package net.haspamelodica.exchanges.stats;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import net.haspamelodica.exchanges.ExchangePool;

public interface StatisticsExchangePool extends ExchangePool
{
	@Override
	public StatisticsExchange createNewExchange() throws IOException;

	public static StatisticsExchangePool wrap(ExchangePool exchangePool, PrintStream statsPrintOut, String prefix)
	{
		return new StatisticsExchangePool()
		{
			private final List<StatisticsExchange> exchanges = new ArrayList<>();

			@Override
			public StatisticsExchange createNewExchange() throws IOException
			{
				String prefixWithExchangeId = (prefix != null ? prefix : "") + "#" + exchanges.size();

				StatisticsExchange exchange = exchangePool.createNewExchange().wrapStatistics(statsPrintOut, prefixWithExchangeId);
				exchanges.add(exchange);
				return exchange;
			}

			@Override
			public void close() throws IOException
			{
				try
				{
					exchangePool.close();
				} finally
				{
					exchanges.forEach(StatisticsExchange::printStatisticsOnce);
				}
			}
		};
	}
}
