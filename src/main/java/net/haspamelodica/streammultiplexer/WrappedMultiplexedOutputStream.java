package net.haspamelodica.streammultiplexer;

public interface WrappedMultiplexedOutputStream
{
	public MultiplexedOutputStream getWrappedStream();
	public default int getStreamID()
	{
		return getWrappedStream().getStreamID();
	}
}
