package net.haspamelodica.exchanges.util;

public record AutoCloseablePair<A extends AutoCloseable, B extends AutoCloseable>(A a, B b) implements AutoCloseable
{
	@Override
	public void close() throws Exception
	{
		try
		{
			a.close();
		} finally
		{
			b.close();
		}
	}
}
