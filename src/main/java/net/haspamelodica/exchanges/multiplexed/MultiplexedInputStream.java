package net.haspamelodica.exchanges.multiplexed;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

public class MultiplexedInputStream extends InputStream
{
	private final MultiplexedExchangePool	multiplexer;
	private final int						exchangeId;

	private final AtomicReference<State>	state;
	private final Semaphore					waitingForResponseSemaphore;

	public MultiplexedInputStream(MultiplexedExchangePool multiplexer, int exchangeId)
	{
		this.multiplexer = multiplexer;
		this.exchangeId = exchangeId;

		this.state = new AtomicReference<>(new State(State.Kind.IDLE, -1, -1, null));
		this.waitingForResponseSemaphore = new Semaphore(0);
	}

	@Override
	public int read() throws IOException
	{
		byte[] buf = new byte[1];
		int len = read(buf, 0, 1);
		if(len == 1)
			return buf[0] & 0xFF;
		return -1;
	}
	@Override
	public int read(byte[] buf, int off, int len) throws UnexpectedResponseException, ClosedException, InterruptedIOException, IOException
	{
		Objects.checkFromIndexSize(off, len, buf.length);
		if(len == 0)
			return 0;

		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case IDLE -> new State(State.Kind.WAITING_FOR_RESPONSE, off, len, buf);
			case WAITING_FOR_RESPONSE, WAITING_FOR_RESPONSE_IN_READ, BYTES_READY, BYTES_READY_THEN_EOF, EOF, IO_EXCEPTION, CLOSED, CLOSED_DURING_WAIT_FOR_RESPONSE -> state;
		});
		return switch(oldState.kind())
		{
			case IDLE -> notifyReadyAndWaitForRead(len);
			case WAITING_FOR_RESPONSE, WAITING_FOR_RESPONSE_IN_READ, BYTES_READY, BYTES_READY_THEN_EOF -> throw new IOException("Another thread is currently reading");
			case EOF -> -1;
			case IO_EXCEPTION -> multiplexer.throwIOException();
			case CLOSED, CLOSED_DURING_WAIT_FOR_RESPONSE -> throwClosed();
		};
	}

	private int notifyReadyAndWaitForRead(int len) throws UnexpectedResponseException, ClosedException, InterruptedIOException, IOException
	{
		multiplexer.writeReadyForReceiving(exchangeId, len);

		try
		{
			waitingForResponseSemaphore.acquire();
		} catch(InterruptedException e)
		{
			//TODO clean up state?
			throw new InterruptedIOException();
		}

		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case BYTES_READY -> new State(State.Kind.IDLE, -1, -1, null);
			case BYTES_READY_THEN_EOF -> new State(State.Kind.EOF, -1, -1, null);
			// We got shut down in the meantime; keep that state.
			// Keep CLOSED_DURING_WAIT_FOR_RESPONSE as well:
			// Even if we don't wait for it any longer, the other side might still send an response.
			case EOF, IO_EXCEPTION, CLOSED, CLOSED_DURING_WAIT_FOR_RESPONSE -> state;
			// spurious wakeup is impossible with semaphores, so this means somehow the semaphore got released unexpectedly
			case WAITING_FOR_RESPONSE, WAITING_FOR_RESPONSE_IN_READ -> state;
			case IDLE -> throw new IllegalStateException("impossible state; this is a bug");
		});
		return switch(oldState.kind())
		{
			case BYTES_READY -> oldState.len();
			case BYTES_READY_THEN_EOF -> oldState.len();
			case EOF -> -1;
			case IO_EXCEPTION -> multiplexer.throwIOException();
			case CLOSED, CLOSED_DURING_WAIT_FOR_RESPONSE -> throwClosed();
			case WAITING_FOR_RESPONSE, WAITING_FOR_RESPONSE_IN_READ -> throw new IllegalStateException("spurious wakeup of semaphore; this is a bug");
			case IDLE -> throw new IllegalStateException("impossible state; this is a bug");
		};
	}

	void recordReceivedData(int len, InputStream in) throws UnexpectedResponseException, IOException
	{
		if(len <= 0)
			throw new UnexpectedResponseException("received data len <= 0");

		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case WAITING_FOR_RESPONSE -> new State(State.Kind.WAITING_FOR_RESPONSE_IN_READ, -1, -1, null);
			// illegal response because not waiting for response
			case IDLE, BYTES_READY, BYTES_READY_THEN_EOF -> state;
			// Special side; see below.
			case CLOSED_DURING_WAIT_FOR_RESPONSE -> new State(State.Kind.CLOSED, -1, -1, null);
			// we got shut down in the meantime; keep that state
			case EOF, IO_EXCEPTION, CLOSED -> state;
			case WAITING_FOR_RESPONSE_IN_READ -> throw new IllegalStateException("two threads tried to record received data; this is a bug");
		});

		switch(oldState.kind())
		{
			case WAITING_FOR_RESPONSE ->
			{
				// nothing to do; continue after this switch.
			}
			// Special case: This input stream got closed during a read,
			// but the output end already prepared data.
			// In this case, skip data to keep stream valid.
			// This might throw an EOFException, which is correct here and doesn't require any special handling.
			// In particular, the state is already correct (CLOSED, but not CLOSED_DURING_WAIT_FOR_RESPONSE).
			case CLOSED_DURING_WAIT_FOR_RESPONSE ->
			{
				in.skipNBytes(len);
				return;
			}
			// CLOSED causes this exception as well:
			// if we would be waiting for a response, we would be in CLOSED_DURING_WAIT_FOR_RESPONSE instead.
			case IDLE, BYTES_READY, BYTES_READY_THEN_EOF, CLOSED -> throw new UnexpectedResponseException("Currently not waiting for a response");
			case EOF -> throw new UnexpectedResponseException("Got data although we are EOF");
			case IO_EXCEPTION -> multiplexer.throwIOException();
			case WAITING_FOR_RESPONSE_IN_READ -> throw new IllegalStateException("impossible state; this is a bug");
		}

		if(len > oldState.len())
			throw new UnexpectedResponseException("received data len > ready len");

		int realRead = in.readNBytes(oldState.buf(), oldState.off(), len);
		if(MultiplexedExchangePool.DEBUG)
			System.err.println("Read " + realRead + " bytes: " + Arrays.toString(
					Arrays.copyOfRange(oldState.buf(), oldState.off(), oldState.off() + realRead)));

		if(realRead != len)
			// This means EOF.
			// We don't need to clean up the state; the handling from MultiplexedExchangePool is enough.
			throw new EOFException();

		oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case WAITING_FOR_RESPONSE_IN_READ -> new State(State.Kind.BYTES_READY, -1, len, null);
			// we got shut down in the meantime; keep that state
			case IO_EXCEPTION, CLOSED_DURING_WAIT_FOR_RESPONSE -> state;
			case EOF -> throw new IllegalStateException("became EOF during read; this is a bug");
			// CLOSED causes this exception as well:
			// if we would be waiting for a response, we would be in CLOSED_DURING_WAIT_FOR_RESPONSE instead.
			case IDLE, WAITING_FOR_RESPONSE, BYTES_READY, BYTES_READY_THEN_EOF, CLOSED -> throw new IllegalStateException("impossible state; this is a bug");
		});

		switch(oldState.kind())
		{
			case WAITING_FOR_RESPONSE_IN_READ -> waitingForResponseSemaphore.release();
			case CLOSED_DURING_WAIT_FOR_RESPONSE ->
			{
				// ignore; nothing to do. We already woke up waitingForResponseSemaphore in close().
			}
			case IO_EXCEPTION -> multiplexer.throwIOException();
			case IDLE, WAITING_FOR_RESPONSE, BYTES_READY, BYTES_READY_THEN_EOF, EOF, CLOSED -> throw new IllegalStateException("impossible state; this is a bug");
		}
	}

	void eofReached()
	{
		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case IDLE, WAITING_FOR_RESPONSE -> new State(State.Kind.EOF, -1, -1, null);
			case BYTES_READY -> new State(State.Kind.BYTES_READY_THEN_EOF, -1, state.len(), null);
			case BYTES_READY_THEN_EOF, EOF, IO_EXCEPTION, CLOSED, CLOSED_DURING_WAIT_FOR_RESPONSE -> state;
			case WAITING_FOR_RESPONSE_IN_READ -> throw new IllegalStateException("becoming EOF during read; this is a bug");
		});
		if(oldState.kind() == State.Kind.WAITING_FOR_RESPONSE || oldState.kind() == State.Kind.WAITING_FOR_RESPONSE_IN_READ)
			waitingForResponseSemaphore.release();
	}
	void ioExceptionThrown()
	{
		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case IDLE, WAITING_FOR_RESPONSE, WAITING_FOR_RESPONSE_IN_READ, BYTES_READY -> new State(State.Kind.IO_EXCEPTION, -1, -1, null);
			case BYTES_READY_THEN_EOF, EOF, IO_EXCEPTION, CLOSED, CLOSED_DURING_WAIT_FOR_RESPONSE -> state;
		});
		if(oldState.kind() == State.Kind.WAITING_FOR_RESPONSE || oldState.kind() == State.Kind.WAITING_FOR_RESPONSE_IN_READ)
			waitingForResponseSemaphore.release();
	}

	private <R> R throwClosed() throws ClosedException
	{
		throw new ClosedException();
	}

	@Override
	public void close() throws IOException
	{
		if(closeWithoutSendingEOF())
			multiplexer.writeInputEOF(exchangeId);
	}
	boolean closeWithoutSendingEOF()
	{
		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case WAITING_FOR_RESPONSE, WAITING_FOR_RESPONSE_IN_READ -> new State(State.Kind.CLOSED_DURING_WAIT_FOR_RESPONSE, -1, -1, null);
			case IDLE, BYTES_READY, BYTES_READY_THEN_EOF, EOF, IO_EXCEPTION -> new State(State.Kind.CLOSED, -1, -1, null);
			case CLOSED, CLOSED_DURING_WAIT_FOR_RESPONSE -> state;
		});
		if(oldState.kind() == State.Kind.WAITING_FOR_RESPONSE || oldState.kind() == State.Kind.WAITING_FOR_RESPONSE_IN_READ)
			waitingForResponseSemaphore.release();
		return oldState.kind() != State.Kind.CLOSED && oldState.kind() != State.Kind.CLOSED_DURING_WAIT_FOR_RESPONSE;
	}

	private static record State(Kind kind, int off, int len, byte[] buf)
	{
		private static enum Kind
		{
			IDLE,
			WAITING_FOR_RESPONSE,
			WAITING_FOR_RESPONSE_IN_READ,
			BYTES_READY,
			BYTES_READY_THEN_EOF,
			EOF,
			IO_EXCEPTION,
			CLOSED,
			CLOSED_DURING_WAIT_FOR_RESPONSE;
		}
	}
}
