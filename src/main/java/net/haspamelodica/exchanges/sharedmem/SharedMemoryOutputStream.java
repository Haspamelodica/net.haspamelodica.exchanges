package net.haspamelodica.exchanges.sharedmem;

import static net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.OFFSET_READER_DATA;
import static net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.OFFSET_WRITER_DATA;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.sharedmem.SharedMemoryCommon.Positions;
import net.haspamelodica.exchanges.util.AutoCloseableByteBuffer;

public class SharedMemoryOutputStream extends OutputStream
{
	private final SharedMemoryCommon sharedmem;

	public SharedMemoryOutputStream(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem) throws IOException
	{
		this.sharedmem = new SharedMemoryCommon(slowExchange, autoCloseableSharedmem);
	}
	public SharedMemoryOutputStream(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem, long busyWaitTimeoutNanos) throws IOException
	{
		this.sharedmem = new SharedMemoryCommon(slowExchange, autoCloseableSharedmem, busyWaitTimeoutNanos);
	}

	@Override
	public void write(int b) throws IOException
	{
		// The buffer is only full if writerPos is just before readerPos. All other combinations mean there's still room.
		// So, readerPos-1 is the only forbidden value (modulo bufsize), thus -1 is the only forbidden delta.
		// However, the given value must be positive, so we choose bufsize-1 instead.
		Positions positions = sharedmem.ensureValidPositions(OFFSET_WRITER_DATA, OFFSET_READER_DATA, sharedmem.bufsize() - 1);
		if(positions == null)
			throw new EOFException();

		int writerPos = positions.ownPos();

		// Here, readerPos is valid, which means that there's at least one byte space, so we're ready to perform the read!
		// First, write the byte. Order is important because otherwise the other side might read too soon.
		sharedmem.setDataByte(writerPos ++, (byte) b);
		sharedmem.updatePosHandlingNotificationRequest(OFFSET_WRITER_DATA, writerPos);
	}

	@Override
	public void flush() throws IOException
	{
		//TODO we probably need to do something here.
		// However, we can't just blindly send a "bytes ready notification",
		// because if the slow exchange's write only returns once the corresponding read finishes,
		// that will mean we might wait until the _next_ read call,
		// which might (depending on how we're used) never come until this flush call finishes.
		// We could also try to wait until some bytes have been consumed,
		// because that would prove the other side isn't currently waiting for our "bytes ready notification".
		// but we can't do that forever
	}

	@Override
	public void close() throws IOException
	{
		sharedmem.close();
	}
}
