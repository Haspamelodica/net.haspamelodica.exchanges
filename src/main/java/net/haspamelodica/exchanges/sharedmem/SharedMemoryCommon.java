package net.haspamelodica.exchanges.sharedmem;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.util.AutoCloseableByteBuffer;
import net.haspamelodica.exchanges.util.ClosedException;

/**
 * Memory layout: The first eight bytes are (int-sized) data for the reader and writer, respectively;
 * the data after that is a ringbuffer.
 * <p>
 * The LSBits (mask 0x7FFF_FFFF) of the reader / writer data are the reader / writer position in the ringbuffer;
 * the MSBit (mask 0x8000_0000) of the data is set if the OTHER side requests a notification over the {@link #slowExchange}.
 * So, the MSBit of the reader data is set if the writer requests a notification, and the other way around.
 * The reasoning behind this is that after the busy wait fails for one side, it can do one last CAX on the other side's length,
 * and this way atomically check if there's data now as well as request a notification if there's not.
 * <p>
 * Assumption: There's at most one thread reading and one thread writing at any given time.
 * "Reading" and "writing" means "being in one of the read/write method variants".
 * <p>
 * Invariant: The notification request bit of the reader/writer is only set during a read/write.
 * So, at the start of a read/write, the read/write can assume its notification request bit is unset.
 */
// public, not package-private:
// only util class for SharedMemory[In|Out]putStream, but also contains BUFSIZE_OVERHEAD, which is interesting for users.
// Instead, members are made package-private individually.
public class SharedMemoryCommon
{
	static final int	OFFSET_READER_DATA	= 0;
	static final int	OFFSET_WRITER_DATA	= 4;
	static final int	OFFSET_DATA_START	= 8;

	public static final boolean	DEBUG_SLOW_EXCHANGE_FOR_SHAREDMEM	= false;
	public static final int		BUFSIZE_OVERHEAD					= OFFSET_DATA_START;

	// MIN_VALUE is 0x8000_0000, but 0x8000_0000 feels more hardcoded and arbitrary
	static final int	REQ_NOTIF_BIT	= Integer.MIN_VALUE;
	static final int	POS_MASK		= ~REQ_NOTIF_BIT;

	private static final long DEFAULT_BUSY_WAIT_TIMEOUT_NANOS = 1_000_000; // 1ms

	private static final VarHandle INT_HANDLE = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());

	private final ByteBuffer	sharedmem;
	private final int			bufsize;

	private final Exchange	slowExchange;
	private final long		busyWaitTimeoutNanos;

	private final AutoCloseableByteBuffer	autoCloseableSharedmem;
	private final AtomicBoolean				closed;
	private boolean							eof;

	SharedMemoryCommon(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem, boolean isWriter) throws IOException
	{
		this(slowExchange, autoCloseableSharedmem, isWriter, DEFAULT_BUSY_WAIT_TIMEOUT_NANOS);
	}
	SharedMemoryCommon(Exchange slowExchange, AutoCloseableByteBuffer autoCloseableSharedmem, boolean isWriter, long busyWaitTimeoutNanos) throws IOException
	{
		this.sharedmem = autoCloseableSharedmem.byteBuffer();
		this.bufsize = sharedmem.capacity() - OFFSET_DATA_START;
		if(bufsize <= 0)
			throw new IllegalArgumentException("Buffer too small");

		this.slowExchange = DEBUG_SLOW_EXCHANGE_FOR_SHAREDMEM ? slowExchange.wrapStatistics(System.err, "slow") : slowExchange;
		this.busyWaitTimeoutNanos = busyWaitTimeoutNanos;

		this.autoCloseableSharedmem = autoCloseableSharedmem;
		this.closed = new AtomicBoolean();

		initAndSynchronize(isWriter);
	}

	int bufsize()
	{
		return bufsize;
	}

	/**
	 * Initializes both positions and waits until the other side is ready too.
	 */
	private void initAndSynchronize(boolean isWriter) throws IOException
	{
		if(isWriter)
		{
			getAndSetInt(OFFSET_READER_DATA, 0);
			getAndSetInt(OFFSET_WRITER_DATA, 0);
			sendNotification();
		} else
			waitForNotificationOrEOF();
	}

	static record Positions(int ownPos, int otherPos)
	{}
	/**
	 * This method assumes the {@link #REQ_NOTIF_BIT} is not set for the data at the given byte offset,
	 * and ensures it's unset again when it returns.
	 * Also, it assumes that the side sending the notification also unsets the {@link #REQ_NOTIF_BIT}.
	 * <p>
	 * The given forbidden delta must be positive.
	 * <p>
	 * Ensures that the other position is "valid",
	 * which means that the difference from the own position to it is not the given forbidden delta;
	 * first with a busy wait and if that fails, by requesting a notification.
	 * <p>
	 * This method handles being closed and EOF; an EOF is handled by returning null.
	 */
	Positions ensureValidPositions(int ownDataByteOffset, int otherDataByteOffset, int forbiddenPosDelta)
			throws ClosedException, IOException
	{
		// We have to do this even if the fast path would succeed because we mustn't return any data after we get closed.
		// That is not true for EOF, but this method only returns EOF after a previous EOF; it doesn't check for EOF by itself.
		if(checkNotClosedAndGetPreviousEof())
			return null;

		// The notification bit of our data might get set to 1 by the other side, but the pos will only be changed by us.
		// So, readerPos will stay up-to-date (assuming that there's only one thread currently reading).
		int ownPos = getInt(ownDataByteOffset) & POS_MASK;

		// We know the REQ_NOTIF_BIT can't be set because of the invariant described in SharedMemoryCommon.
		OptionalInt otherPosOpt = ensureValidPos(otherDataByteOffset, (ownPos + forbiddenPosDelta) % bufsize());
		if(otherPosOpt.isEmpty())
			return null;

		return new Positions(ownPos, otherPosOpt.getAsInt());
	}

	/**
	 * This method assumes the {@link #REQ_NOTIF_BIT} is not set for the data at the given byte offset,
	 * and ensures it's unset again when it returns.
	 * Also, it assumes that the side sending the notification also unsets the {@link #REQ_NOTIF_BIT}.
	 * <p>
	 * Ensures that the pos in the data at the given offset is not equal to the given forbidden value;
	 * first with a busy wait, and if that fails, by requesting a notification from the other side.
	 * If an EOF occurs, an empty optional is returned,
	 * otherwise, the read pos is returned.
	 */
	private OptionalInt ensureValidPos(int byteOffset, int forbiddenPosValue) throws IOException
	{
		int pos = busyWaitForPos(byteOffset, forbiddenPosValue);

		// The REQ_NOTIF_BIT does not mean a notification request here;
		// instead, that bit is set by busyWaitForPosAssumeReqNotifBitUnset and means that the busy wait timed out.
		if((pos & REQ_NOTIF_BIT) == 0)
			// The REQ_NOTIF_BIT is not set, so the busy wait succeeded and we're done!
			return OptionalInt.of(pos);

		// Busy wait timed out. Atomically do the following:
		// - Check one last time if the pos changed.
		// - If there's not, the fast path failed, so request a notification.
		// This can be done by a single CAX, with the expected value being the old pos without the REQ_NOTIF_BIT,
		// and the new value being the old pos with the REQ_NOTIF_BIT.
		pos = caxInt(byteOffset, pos & POS_MASK, pos);

		if(pos != forbiddenPosValue)
			// The pos is now not the forbidden value anymore, which means we can continue with the fastpath.
			// Also, this means that the pos changed, so the CAX will have failed and thus not have set the REQ_NOTIF_BIT.
			return OptionalInt.of(pos);

		// The pos is still the forbidden value, so the fastpath failed.
		// The CAX will have set the REQ_NOTIF_BIT already, so we can immediately wait for the notification.
		if(waitForNotificationOrEOF())
			return OptionalInt.empty();

		// The notification has arrived! We don't even have to unset the REQ_NOTIF_BIT,
		// because the other side will have done that for us.
		// However, we do have to re-read the value.
		return OptionalInt.of(getInt(byteOffset));
	}

	/**
	 * This method assumes the {@link #REQ_NOTIF_BIT} is not set for the data at the given byte offset.
	 * <p>
	 * Repeatedly reads the pos at the given offset and, if the pos is not equal to the given forbidden value, returns that pos.
	 * If the {@link #busyWaitTimeoutNanos} elapses without the predicate getting fulfilled,
	 * returns the last read pos with the {@link #REQ_NOTIF_BIT} set.
	 */
	private int busyWaitForPos(int byteOffset, int forbiddenPosValue)
	{
		long start = System.nanoTime();
		for(;;)
		{
			int data = getInt(byteOffset);
			// Because REQ_NOTIF_BIT is not set (as per method contract), we know that data == pos.
			if(data != forbiddenPosValue)
				return data;
			if(System.nanoTime() - start >= busyWaitTimeoutNanos)
				return data | REQ_NOTIF_BIT;
		}
	}

	void updatePosHandlingNotificationRequest(int byteOffset, int newPos) throws IOException
	{
		// Atomically update the pos and check if the other side requested a notification.
		// No need for CAX; we want to unset the REQ_NOTIF_BIT either way, so getAndSet is enough.
		if((getAndSetInt(byteOffset, newPos % bufsize()) & REQ_NOTIF_BIT) != 0)
			sendNotification();
	}

	byte getDataByte(int byteOffsetInData)
	{
		// It's not possible to create a VarHandle for byte.
		return sharedmem.get(byteOffsetInData + OFFSET_DATA_START);
	}
	void setDataByte(int byteOffsetInData, byte value)
	{
		// It's not possible to create a VarHandle for byte.
		sharedmem.put(byteOffsetInData + OFFSET_DATA_START, value);
	}
	void getDataBytes(int byteOffsetInData, byte[] buf, int off, int len)
	{
		// It's not possible to create a VarHandle for byte.
		sharedmem.get(byteOffsetInData + OFFSET_DATA_START, buf, off, len);
	}
	void setDataBytes(int byteOffsetInData, byte[] buf, int off, int len)
	{
		// It's not possible to create a VarHandle for byte.
		sharedmem.put(byteOffsetInData + OFFSET_DATA_START, buf, off, len);
	}

	private int getInt(int byteOffset)
	{
		return (int) INT_HANDLE.getVolatile(sharedmem, byteOffset);
	}
	private int getAndSetInt(int byteOffset, int newValue)
	{
		return (int) INT_HANDLE.getAndSet(sharedmem, byteOffset, newValue);
	}
	private int caxInt(int byteOffset, int expectedValue, int newValue)
	{
		return (int) INT_HANDLE.compareAndExchange(sharedmem, byteOffset, expectedValue, newValue);
	}

	private void sendNotification() throws IOException
	{
		slowExchange.out().write(0);
		slowExchange.out().flush();
	}
	private boolean waitForNotificationOrEOF() throws IOException
	{
		int read = slowExchange.in().read();
		if(read == 0)
			return false;

		// EOF on slowExchange means EOF.
		if(read >= 0)
			throw new IOException("Illegal notification byte: " + read);

		// Now, we know that read < 0 and thus EOF has been reached.
		eof = true;
		return true;
	}

	private boolean checkNotClosedAndGetPreviousEof() throws ClosedException
	{
		if(closed.get())
			throw new ClosedException();
		return eof;
	}

	void close() throws IOException
	{
		if(closed.getAndSet(true))
			return;

		autoCloseableSharedmem.close();
		slowExchange.close();
	}
}
