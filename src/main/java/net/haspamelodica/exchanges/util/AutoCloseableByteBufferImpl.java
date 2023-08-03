package net.haspamelodica.exchanges.util;

import java.io.IOException;
import java.nio.ByteBuffer;

public record AutoCloseableByteBufferImpl(ByteBuffer byteBuffer, IORunnable closeAction) implements AutoCloseableByteBuffer
{
	@Override
	public void close() throws IOException
	{
		if(closeAction != null)
			closeAction.run();
	}
}
