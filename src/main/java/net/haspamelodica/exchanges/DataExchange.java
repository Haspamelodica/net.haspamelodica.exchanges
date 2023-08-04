package net.haspamelodica.exchanges;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import net.haspamelodica.exchanges.util.IOAutoCloseable;

public record DataExchange(DataInputStream in, DataOutputStream out) implements IOAutoCloseable
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
