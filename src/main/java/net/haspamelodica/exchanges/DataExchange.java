package net.haspamelodica.exchanges;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record DataExchange(DataInputStream in, DataOutputStream out) implements AutoCloseable
{
	@Override
	public void close() throws IOException
	{
		try
		{
			in.close();
		} finally
		{
			out.close();
		}
	}

	public static DataExchange from(Exchange exchange)
	{
		return new DataExchange(new DataInputStream(exchange.in()), new DataOutputStream(exchange.out()));
	}
}
