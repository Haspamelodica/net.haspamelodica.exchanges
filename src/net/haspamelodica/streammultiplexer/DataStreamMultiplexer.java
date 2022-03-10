package net.haspamelodica.streammultiplexer;

import java.io.InputStream;
import java.io.OutputStream;

public class DataStreamMultiplexer extends GenericStreamMultiplexer<MultiplexedDataInputStream, MultiplexedDataOutputStream>
{
	public DataStreamMultiplexer(InputStream rawIn, OutputStream rawOut)
	{
		super(rawIn, rawOut, MultiplexedDataInputStream::new, MultiplexedDataOutputStream::new);
	}
}
