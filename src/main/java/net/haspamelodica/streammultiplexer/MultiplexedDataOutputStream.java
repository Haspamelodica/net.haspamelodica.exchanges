package net.haspamelodica.streammultiplexer;

import java.io.DataOutputStream;
import java.io.OutputStream;

public class MultiplexedDataOutputStream extends DataOutputStream implements WrappedMultiplexedOutputStream
{
	private final MultiplexedOutputStream wrappedStream;

	public MultiplexedDataOutputStream(MultiplexedOutputStream out)
	{
		this(out, out);
	}
	public MultiplexedDataOutputStream(MultiplexedOutputStream wrappedStream, OutputStream out)
	{
		super(out);
		this.wrappedStream = wrappedStream;
	}

	@Override
	public MultiplexedOutputStream getWrappedStream()
	{
		return wrappedStream;
	}
}
