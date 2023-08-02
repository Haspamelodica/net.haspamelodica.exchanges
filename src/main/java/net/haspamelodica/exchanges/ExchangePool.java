package net.haspamelodica.exchanges;

import java.io.IOException;
import java.io.PrintStream;

import net.haspamelodica.exchanges.stats.StatisticsExchangePool;

public interface ExchangePool extends AutoCloseable
{
	public Exchange createNewExchange() throws IOException;
	@Override
	public void close() throws IOException;

	public default StatisticsExchangePool wrapStatistics()
	{
		return wrapStatistics(null);
	}
	public default StatisticsExchangePool wrapStatistics(PrintStream statsPrintOut)
	{
		return wrapStatistics(statsPrintOut, null);
	}
	public default StatisticsExchangePool wrapStatistics(PrintStream autoPrintStreamOnClose, String prefix)
	{
		return StatisticsExchangePool.wrap(this, autoPrintStreamOnClose, prefix);
	}
}
