package net.haspamelodica.streammultiplexer;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Function;

public class DataStreamMultiplexer extends GenericStreamMultiplexer<MultiplexedDataInputStream, MultiplexedDataOutputStream>
{
	public DataStreamMultiplexer(InputStream rawIn, OutputStream rawOut)
	{
		this(rawIn, rawOut, MultiplexedDataInputStream::new, MultiplexedDataOutputStream::new);
	}
	public DataStreamMultiplexer(InputStream rawIn, OutputStream rawOut,
			Function<MultiplexedInputStream, MultiplexedDataInputStream> inWrapper,
			Function<MultiplexedOutputStream, MultiplexedDataOutputStream> outWrapper)
	{
		super(rawIn, rawOut, inWrapper, outWrapper);
	}
}
