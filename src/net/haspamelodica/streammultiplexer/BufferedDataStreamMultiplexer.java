package net.haspamelodica.streammultiplexer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class BufferedDataStreamMultiplexer extends DataStreamMultiplexer
{
	public BufferedDataStreamMultiplexer(InputStream rawIn, OutputStream rawOut)
	{
		super(new BufferedInputStream(rawIn), new BufferedOutputStream(rawOut),
				in -> new MultiplexedDataInputStream(in, new BufferedInputStream(in)),
				out -> new MultiplexedDataOutputStream(out, new BufferedOutputStream(out)));
	}
}
