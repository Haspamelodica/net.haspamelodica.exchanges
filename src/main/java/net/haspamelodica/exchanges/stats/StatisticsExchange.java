package net.haspamelodica.exchanges.stats;

import java.io.IOException;
import java.io.PrintStream;

import net.haspamelodica.exchanges.Exchange;

public interface StatisticsExchange extends Exchange
{
	@Override
	public StatisticsInputStream in();
	@Override
	public StatisticsOutputStream out();

	public void printStatistics();
	public void printStatisticsOnce();

	public static StatisticsExchange wrap(Exchange exchange, PrintStream statsPrintOut, String prefix)
	{
		StatisticsInputStream in = new StatisticsInputStream(exchange.in());
		StatisticsOutputStream out = new StatisticsOutputStream(exchange.out());

		return new StatisticsExchange()
		{
			private boolean hasPrintedStats;

			@Override
			public StatisticsInputStream in()
			{
				return in;
			}

			@Override
			public StatisticsOutputStream out()
			{
				return out;
			}

			@Override
			public void close() throws IOException
			{
				try
				{ // No need to explicitly close in and out - Statistics[In|Out]putStreams don't care about being closed
					exchange.close();
				} finally
				{
					printStatisticsOnce();
				}
			}

			@Override
			public void printStatisticsOnce()
			{
				if(!hasPrintedStats)
					printStatistics();
			}

			@Override
			public void printStatistics()
			{
				if(statsPrintOut == null)
					return;

				String result = prefix != null ? prefix + ": " : "";
				result += "in " + in.getOpCount() + "/" + in.getByteCount() + "/" + in.getErrorCount();
				result += "; out " + out.getOpCount() + "/" + out.getByteCount() + "/" + out.getFlushCount() + "/" + out.getErrorCount();
				statsPrintOut.println(result);
				hasPrintedStats = true;
			}
		};
	}
}
