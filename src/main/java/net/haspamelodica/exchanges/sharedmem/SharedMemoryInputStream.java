package net.haspamelodica.exchanges.sharedmem;

import static net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.OFFSET_READER_DATA;
import static net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.OFFSET_WRITER_DATA;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.Positions;
import net.haspamelodica.exchanges.util.AutoCloseableByteBuffer;

public class SharedMemoryInputStream extends InputStream
{
	private final SharedMemoryCommon sharedmem;

	public SharedMemoryInputStream(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem) throws IOException
	{
		this.sharedmem = new SharedMemoryCommon(slowExchange, autoCloseableSharedmem);
	}
	public SharedMemoryInputStream(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem, long busyWaitTimeoutNanos) throws IOException
	{
		this.sharedmem = new SharedMemoryCommon(slowExchange, autoCloseableSharedmem, busyWaitTimeoutNanos);
	}

	@Override
	public int read() throws IOException
	{
		// The buffer is only empty if readerPos == writerPos. All other combinations mean there's data available;
		// even those with readerPos > writerPos. So, readerPos is the only forbidden value, thus 0 is the only forbidden delta.
		Positions positions = sharedmem.ensureValidPositions(OFFSET_READER_DATA, OFFSET_WRITER_DATA, 0);
		if(positions == null)
			return -1;

		int readerPos = positions.ownPos();

		// Here, writerPos is valid, which means that there's at least one byte available, so we're ready to perform the read!
		// First, read the byte. Order is important because otherwise the byte might get overwritten.
		int read = sharedmem.getDataByte(readerPos ++) & 0xFF;
		sharedmem.updatePosHandlingNotificationRequest(OFFSET_READER_DATA, readerPos);
		return read;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException
	{
		Objects.checkFromIndexSize(off, len, b.length);
		if(len == 0)
			return 0;

		int read = read();
		if(read < 0)
			return -1;

		b[off] = (byte) read;
		return 1;
	}

	@Override
	public void close() throws IOException
	{
		sharedmem.close();
	}
}
