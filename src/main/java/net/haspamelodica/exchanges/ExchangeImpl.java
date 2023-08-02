package net.haspamelodica.exchanges;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.haspamelodica.exchanges.util.IORunnable;

public record ExchangeImpl(InputStream in, OutputStream out, IORunnable closeAction) implements Exchange
{
	@Override
	public void close() throws IOException
	{
		closeAction.run();
	}
}
