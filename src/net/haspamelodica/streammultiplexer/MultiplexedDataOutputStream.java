package net.haspamelodica.streammultiplexer;

import java.io.DataOutputStream;

public class MultiplexedDataOutputStream extends DataOutputStream implements WrappedMultiplexedOutputStream
{
	private final MultiplexedOutputStream wrappedStream;

	public MultiplexedDataOutputStream(MultiplexedOutputStream out)
	{
		super(out);
		this.wrappedStream = out;
	}

	@Override
	public MultiplexedOutputStream getWrappedStream()
	{
		return wrappedStream;
	}
}
