package net.haspamelodica.exchanges.multiplexed;

import java.io.DataInputStream;
import java.io.IOException;

import net.haspamelodica.exchanges.Exchange;

public class MultiplexedExchange
{
	private final int						id;
	private final MultiplexedInputStream	in;
	private final MultiplexedOutputStream	out;

	MultiplexedExchange(MultiplexedExchangePool multiplexer, int id)
	{
		if(id < 0)
			throw new IllegalArgumentException("negative ID: " + id);
		this.id = id;
		this.in = new MultiplexedInputStream(multiplexer, id);
		this.out = new MultiplexedOutputStream(multiplexer, id);
	}

	private MultiplexedExchange()
	{
		this.id = -1;
		this.in = null;
		this.out = null;
	}
	static MultiplexedExchange createSentry()
	{
		return new MultiplexedExchange();
	}

	boolean isSentry()
	{
		return id < 0;
	}
	int id()
	{
		return id;
	}

	Exchange toExchange()
	{
		return new Exchange(in, out);
	}

	void outEofReached()
	{
		out.eofReached();
	}

	void recordReadyForReceiving(int len) throws UnexpectedResponseException, IOException
	{
		out.recordReadyForReceiving(len);
	}

	void inEofReached()
	{
		in.eofReached();
	}

	void recordReceivedData(int len, DataInputStream rawIn) throws UnexpectedResponseException, IOException
	{
		in.recordReceivedData(len, rawIn);
	}

	void eofReached()
	{
		inEofReached();
		outEofReached();
	}

	void ioExceptionThrown()
	{
		in.ioExceptionThrown();
		out.ioExceptionThrown();
	}

	void closeWithoutSendingEOF()
	{
		in.closeWithoutSendingEOF();
		out.closeWithoutSendingEOF();
	}
}
