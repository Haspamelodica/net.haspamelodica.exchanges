package net.haspamelodica.exchanges.sharedmem;

import static net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.OFFSET_READER_DATA;
import static net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.OFFSET_WRITER_DATA;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.Positions;
import net.haspamelodica.exchanges.util.AutoCloseableByteBuffer;
import net.haspamelodica.exchanges.util.ClosedException;

public class SharedMemoryInputStream extends InputStream
{
	private final SharedMemoryCommon sharedmem;

	public SharedMemoryInputStream(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem) throws IOException
	{
		this.sharedmem = new SharedMemoryCommon(slowExchange, autoCloseableSharedmem, false);
	}
	public SharedMemoryInputStream(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem, long busyWaitTimeoutNanos) throws IOException
	{
		this.sharedmem = new SharedMemoryCommon(slowExchange, autoCloseableSharedmem, false, busyWaitTimeoutNanos);
	}

	@Override
	public int read() throws IOException
	{
		Positions positions = ensureNotEmpty();
		if(positions == null)
			return -1;

		int readerPos = positions.ownPos();

		// Here, writerPos is valid, which means that there's at least one byte available, so we're ready to perform the read!
		// First, read the byte. Order is important because otherwise the byte might get overwritten.
		int read = sharedmem.getDataByte(readerPos) & 0xFF;
		sharedmem.updatePosHandlingNotificationRequest(OFFSET_READER_DATA, (readerPos + 1) % sharedmem.bufsize());
		return read;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException
	{
		Objects.checkFromIndexSize(off, len, b.length);
		if(len == 0)
			return 0;

		Positions positions = ensureNotEmpty();
		if(positions == null)
			return -1;

		int readerPos = positions.ownPos();
		int writerPos = positions.otherPos();

		// First, read the bytes; then, send notification. Order is important because otherwise the byte might get overwritten.
		int readBytes;
		int newReaderPos;
		if(readerPos < writerPos)
		{
			// no wraparound; just read
			readBytes = Math.min(len, writerPos - readerPos);
			sharedmem.getDataBytes(readerPos, b, off, readBytes);
			newReaderPos = readerPos + readBytes;
		} else
		{
			// wraparound
			int firstPortionSize = sharedmem.bufsize() - readerPos;
			// First check if len is too small for the portion from readerPos to the end.
			if(len < firstPortionSize)
			{
				// That is the case, so we'll only need to read a part of that portion.
				sharedmem.getDataBytes(readerPos, b, off, len);
				readBytes = len;
				newReaderPos = readerPos + readBytes;
			} else
			{
				// len is big enough to fit at least the entire first portion. So, start by reading that portion.
				sharedmem.getDataBytes(readerPos, b, off, firstPortionSize);
				// Now, the reader pos is at bufsize, which means it's at 0.
				// See how many more bytes fit into len.
				int secondPortionLen = Math.min(len - firstPortionSize, writerPos);
				if(secondPortionLen != 0)
					sharedmem.getDataBytes(0, b, off + firstPortionSize, secondPortionLen);
				readBytes = firstPortionSize + secondPortionLen;
				newReaderPos = secondPortionLen;
			}
		}

		sharedmem.updatePosHandlingNotificationRequest(OFFSET_READER_DATA, newReaderPos);
		return readBytes;
	}
	private Positions ensureNotEmpty() throws ClosedException, IOException
	{
		// The buffer is only empty if readerPos == writerPos. All other combinations mean there's data available;
		// even those with readerPos > writerPos.
		// So, readerPos is the only forbidden value for writerPos, thus 0 is the only forbidden delta.
		Positions positions = sharedmem.ensureValidPositions(OFFSET_READER_DATA, OFFSET_WRITER_DATA, 0);

		// Here, either positions==null meaning EOF; or writerPos is valid,
		// which means that there's at least one byte available, so we're ready to perform the read!
		return positions;
	}

	@Override
	public void close() throws IOException
	{
		sharedmem.close();
	}
}
