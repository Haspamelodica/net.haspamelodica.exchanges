package net.haspamelodica.exchanges.util;

import java.io.IOException;

public record AutoCloseablePair<A extends IOAutoCloseable, B extends IOAutoCloseable>(A a, B b) implements IOAutoCloseable
{
	@Override
	public void close() throws IOException
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
