package net.haspamelodica.streammultiplexer;

import java.io.InputStream;
import java.io.OutputStream;

public class StreamMultiplexer extends GenericStreamMultiplexer<MultiplexedInputStream, MultiplexedOutputStream>
{
	public StreamMultiplexer(InputStream rawIn, OutputStream rawOut)
	{
		super(rawIn, rawOut, in -> in, out -> out);
	}
}
