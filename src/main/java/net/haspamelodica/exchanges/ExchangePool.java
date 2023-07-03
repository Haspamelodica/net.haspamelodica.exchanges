package net.haspamelodica.exchanges;

import java.io.IOException;

public interface ExchangePool extends AutoCloseable
{
	public Exchange createNewExchange() throws IOException;
	@Override
	public void close() throws IOException;
}
