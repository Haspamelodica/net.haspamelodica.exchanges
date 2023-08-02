package net.haspamelodica.exchanges.util;

import java.io.IOException;

@FunctionalInterface
public interface IORunnable
{
	public void run() throws IOException;
}
