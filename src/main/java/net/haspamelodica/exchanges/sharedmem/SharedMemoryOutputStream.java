package net.haspamelodica.exchanges.sharedmem;

import static net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.OFFSET_READER_DATA;
import static net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.OFFSET_WRITER_DATA;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.Positions;
import net.haspamelodica.exchanges.util.AutoCloseableByteBuffer;
import net.haspamelodica.exchanges.util.ClosedException;

public class SharedMemoryOutputStream extends OutputStream
{
	private final SharedMemoryCommon sharedmem;

	public SharedMemoryOutputStream(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem) throws IOException
	{
		this.sharedmem = new SharedMemoryCommon(slowExchange, autoCloseableSharedmem, true);
	}
	public SharedMemoryOutputStream(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem, long busyWaitTimeoutNanos) throws IOException
	{
		this.sharedmem = new SharedMemoryCommon(slowExchange, autoCloseableSharedmem, true, busyWaitTimeoutNanos);
	}

	@Override
	public void write(int b) throws IOException
	{
		int writerPos = ensureNotFull().ownPos();

		// First, write the byte. Order is important because otherwise the other side might read too soon.
		sharedmem.setDataByte(writerPos, (byte) b);
		sharedmem.updatePosHandlingNotificationRequest(OFFSET_WRITER_DATA, (writerPos + 1) % sharedmem.bufsize());
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException
	{
		Objects.checkFromIndexSize(off, len, b.length);

		int nextOff = off;
		int remaining = len;
		while(remaining != 0)
		{
			int written = writeChunk(b, nextOff, remaining);
			nextOff += written;
			remaining -= written;
		}
	}

	private int writeChunk(byte[] b, int off, int len) throws IOException
	{
		Positions positions = ensureNotFull();

		int writerPos = positions.ownPos();
		int readerPos = positions.otherPos();

		// First, write the bytes. Order is important because otherwise the other side might read too soon.
		int writtenBytes;
		int newWriterPos;
		if(writerPos < readerPos)
		{
			// no wraparound; just write. -1 because we are not allowed to make writerPos==readerPos,
			// because that means the ringbuffer is empty.
			writtenBytes = Math.min(len, readerPos - writerPos - 1);
			sharedmem.setDataBytes(writerPos, b, off, writtenBytes);
			newWriterPos = writerPos + writtenBytes;
		} else
		{
			// wraparound or empty. We can at least write up to the end.
			int firstPortionSize = sharedmem.bufsize() - writerPos;
			// First check if len is smaller than the portion from writerPos to the end.
			if(len < firstPortionSize)
			{
				// That is the case, so we'll only need to write a part of that portion.
				sharedmem.setDataBytes(writerPos, b, off, len);
				writtenBytes = len;
				newWriterPos = writerPos + writtenBytes;
			} else if(readerPos == 0)
			{
				// len is big enough to fill at least the entire first portion, but readerPos==0.
				// So, we can only fill up to the second-to-last byte,
				// because otherwise writerPos would need to be equal to readerPos.				
				sharedmem.setDataBytes(writerPos, b, off, firstPortionSize - 1);
				writtenBytes = firstPortionSize - 1;
				newWriterPos = sharedmem.bufsize() - 1;
			} else
			{
				// len is big enough to fill at least the entire first portion. So, start by writing that portion.
				sharedmem.setDataBytes(writerPos, b, off, firstPortionSize);
				// Now, the writer pos is at bufsize, which means it's at 0.
				// See how many more bytes len has left.
				int secondPortionLen = Math.min(len - firstPortionSize, readerPos - 1);
				if(secondPortionLen != 0)
					sharedmem.setDataBytes(0, b, off + firstPortionSize, secondPortionLen);
				writtenBytes = firstPortionSize + secondPortionLen;
				newWriterPos = secondPortionLen;
			}
		}

		sharedmem.updatePosHandlingNotificationRequest(OFFSET_WRITER_DATA, newWriterPos);
		return writtenBytes;
	}

	private Positions ensureNotFull() throws ClosedException, IOException, EOFException
	{
		// The buffer is only full if writerPos is just before readerPos. All other combinations mean there's still room.
		// So, writerPos+1 is the only forbidden value for readerPos.
		Positions positions = sharedmem.ensureValidPositions(OFFSET_WRITER_DATA, OFFSET_READER_DATA, 1);
		if(positions == null)
			throw new EOFException();

		// Here, readerPos is valid, which means that there's at least one byte space, so we're ready to perform the write!
		return positions;
	}

	@Override
	public void close() throws IOException
	{
		sharedmem.close();
	}
}
