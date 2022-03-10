package net.haspamelodica.streammultiplexer;

import java.io.DataInputStream;
import java.io.InputStream;

public class MultiplexedDataInputStream extends DataInputStream implements WrappedMultiplexedInputStream
{
	private final MultiplexedInputStream wrappedStream;

	public MultiplexedDataInputStream(MultiplexedInputStream in)
	{
		this(in, in);
	}
	public MultiplexedDataInputStream(MultiplexedInputStream wrappedStream, InputStream in)
	{
		super(in);
		this.wrappedStream = wrappedStream;
	}

	@Override
	public MultiplexedInputStream getWrappedStream()
	{
		return wrappedStream;
	}
}
