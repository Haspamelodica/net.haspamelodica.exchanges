package net.haspamelodica.exchanges.util;

import java.io.IOException;

public interface IOAutoCloseable extends AutoCloseable
{
	@Override
	public void close() throws IOException;
}
