package net.haspamelodica.streammultiplexer;

import java.io.DataInputStream;

public class MultiplexedDataInputStream extends DataInputStream implements WrappedMultiplexedInputStream
{
	private final MultiplexedInputStream wrappedStream;

	public MultiplexedDataInputStream(MultiplexedInputStream in)
	{
		super(in);
		this.wrappedStream = in;
	}

	@Override
	public MultiplexedInputStream getWrappedStream()
	{
		return wrappedStream;
	}
}
