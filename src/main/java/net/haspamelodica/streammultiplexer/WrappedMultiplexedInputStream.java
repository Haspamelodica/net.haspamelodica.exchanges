package net.haspamelodica.streammultiplexer;

public interface WrappedMultiplexedInputStream
{
	public MultiplexedInputStream getWrappedStream();
	public default int getStreamID()
	{
		return getWrappedStream().getStreamID();
	}
}
