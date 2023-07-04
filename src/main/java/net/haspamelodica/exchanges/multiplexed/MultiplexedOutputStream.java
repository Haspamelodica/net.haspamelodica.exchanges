package net.haspamelodica.exchanges.multiplexed;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

public class MultiplexedOutputStream extends OutputStream
{
	private final MultiplexedExchangePool	multiplexer;
	private final int						exchangeId;

	private final AtomicReference<State>	state;
	private final Semaphore					waitingForReadyBytesSemaphore;

	public MultiplexedOutputStream(MultiplexedExchangePool multiplexer, int exchangeId)
	{
		this.multiplexer = multiplexer;
		this.exchangeId = exchangeId;

		this.state = new AtomicReference<>(new State(State.Kind.IDLE, -1));
		this.waitingForReadyBytesSemaphore = new Semaphore(0);
	}

	@Override
	public void write(int b) throws IOException
	{
		write(new byte[] {(byte) b}, 0, 1);
	}

	@Override
	public void write(byte[] buf, int off, int len) throws UnexpectedResponseException, ClosedException, EOFException, InterruptedIOException, IOException
	{
		Objects.checkFromIndexSize(off, len, buf.length);
		if(len == 0)
			return;

		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case IDLE -> new State(State.Kind.WAITING_FOR_READY_BYTES, -1);
			case WAITING_FOR_AVAILABLE_BYTES -> new State(State.Kind.WRITING, -1);
			case WAITING_FOR_READY_BYTES, JUST_GOT_READY_BYTES, WRITING, WRITING_AND_GOT_NEXT_READY_BYTES, EOF, IO_EXCEPTION, CLOSED -> state;
		});
		switch(oldState.kind())
		{
			case IDLE -> writeChecked(buf, off, len, 0);
			case WAITING_FOR_AVAILABLE_BYTES -> writeChecked(buf, off, len, oldState.readyBytes());
			case WAITING_FOR_READY_BYTES, JUST_GOT_READY_BYTES, WRITING, WRITING_AND_GOT_NEXT_READY_BYTES -> throw new IOException("Another thread is currently writing");
			case EOF -> throwEOF();
			case IO_EXCEPTION -> multiplexer.throwIOException();
			case CLOSED -> throwClosed();
		}
	}

	private void writeChecked(byte[] buf, int off, int len, int readyBytes)
			throws UnexpectedResponseException, ClosedException, EOFException, InterruptedIOException, IOException
	{
		boolean immediateWriteStateUpdateUnneccessaryWhenLenNotNull = false;
		for(;;)
		{
			if(readyBytes != 0)
			{
				int lenToWrite = Math.min(len, readyBytes);

				multiplexer.writeBytes(exchangeId, buf, off, lenToWrite);
				off += lenToWrite;
				len -= lenToWrite;
				// The read request is now finished, regardless of whether readyBytes > len or not.
				// This is because MultiplexedInputStream will let read return after any number of bytes have been read.

				if(len == 0)
				{
					// write request finished
					state.getAndUpdate(state -> switch(state.kind())
					{
						case WRITING -> new State(State.Kind.IDLE, -1);
						case WRITING_AND_GOT_NEXT_READY_BYTES -> new State(State.Kind.WAITING_FOR_AVAILABLE_BYTES, state.readyBytes());
						// we got shut down in the meantime; keep that state, but don't throw since the write finished
						case EOF, IO_EXCEPTION, CLOSED -> state;
						case IDLE, WAITING_FOR_AVAILABLE_BYTES, WAITING_FOR_READY_BYTES, JUST_GOT_READY_BYTES -> throw new IllegalStateException("impossible state; this is a bug");
					});
					return;
				}

				if(!immediateWriteStateUpdateUnneccessaryWhenLenNotNull)
				{
					State oldState = state.getAndUpdate(state -> switch(state.kind())
					{
						case WRITING -> new State(State.Kind.WAITING_FOR_READY_BYTES, -1);
						case WRITING_AND_GOT_NEXT_READY_BYTES -> new State(State.Kind.WRITING, -1);
						// we got shut down in the meantime; keep that state
						case EOF, IO_EXCEPTION, CLOSED -> state;
						case IDLE, WAITING_FOR_AVAILABLE_BYTES, WAITING_FOR_READY_BYTES, JUST_GOT_READY_BYTES -> throw new IllegalStateException("impossible state; this is a bug");
					});

					switch(oldState.kind())
					{
						case WRITING ->
						{
							// loop body will continue normally
						}
						case WRITING_AND_GOT_NEXT_READY_BYTES ->
						{
							readyBytes = oldState.readyBytes();
							// skip waiting and directly go to the next loop iteration,
							// which starts with checking readyBytes
							continue;
						}
						case EOF -> throwEOF();
						case IO_EXCEPTION -> multiplexer.throwIOException();
						case CLOSED -> throwClosed();
						case IDLE, WAITING_FOR_AVAILABLE_BYTES, WAITING_FOR_READY_BYTES, JUST_GOT_READY_BYTES -> throw new IllegalStateException("impossible state; this is a bug");
					}
				}
			}

			try
			{
				waitingForReadyBytesSemaphore.acquire();
			} catch(InterruptedException e)
			{
				//TODO clean up state?
				throw new InterruptedIOException();
			}

			int lenFinal = len;
			State oldState = state.getAndUpdate(state -> switch(state.kind())
			{
				// If the read request will finish, we can't directly set to IDLE / WAITING_FOR_AVAILABLE_BYTES,
				// because another writing thread could be faster than us.
				// If the read request won't be finished now, we can directly set the state to WAITING_FOR_READY_BYTES,
				// because other writing threads won't be allowed writing in either case.
				case JUST_GOT_READY_BYTES -> new State(lenFinal > state.readyBytes() ? State.Kind.WAITING_FOR_READY_BYTES : State.Kind.WRITING, -1);
				// we got shut down in the meantime; keep that state
				case EOF, IO_EXCEPTION, CLOSED -> state;
				// spurious wakeup is impossible with semaphores, so this means somehow the semaphore got released unexpectedly
				case WAITING_FOR_READY_BYTES -> state;
				case IDLE, WAITING_FOR_AVAILABLE_BYTES, WRITING, WRITING_AND_GOT_NEXT_READY_BYTES -> throw new IllegalStateException("impossible state; this is a bug");
			});
			switch(oldState.kind())
			{
				case JUST_GOT_READY_BYTES ->
				{
					// loop will continue as normal.
					readyBytes = oldState.readyBytes();
					// if len != 0, the state update function above will already have set the state to WAITING_FOR_READY_BYTES,
					// so the write part won't have to update the state in that case.
					immediateWriteStateUpdateUnneccessaryWhenLenNotNull = true;
				}
				case EOF -> throwEOF();
				case IO_EXCEPTION -> multiplexer.throwIOException();
				case CLOSED -> throwClosed();
				case WAITING_FOR_READY_BYTES -> throw new IllegalStateException("spurious wakeup of semaphore; this is a bug");
				case IDLE, WAITING_FOR_AVAILABLE_BYTES, WRITING, WRITING_AND_GOT_NEXT_READY_BYTES -> throw new IllegalStateException("impossible state; this is a bug");
			}
		}
	}

	void recordReadyForReceiving(int len) throws UnexpectedResponseException, IOException
	{
		if(len <= 0)
			throw new UnexpectedResponseException("ready bytes len <= 0");

		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case IDLE -> new State(State.Kind.WAITING_FOR_AVAILABLE_BYTES, len);
			case WAITING_FOR_READY_BYTES -> new State(State.Kind.JUST_GOT_READY_BYTES, len);
			case WRITING -> new State(State.Kind.WRITING_AND_GOT_NEXT_READY_BYTES, len);
			// illegal response because two reads
			case WAITING_FOR_AVAILABLE_BYTES, JUST_GOT_READY_BYTES, WRITING_AND_GOT_NEXT_READY_BYTES -> state;
			// we got shut down in the meantime; keep that state
			case EOF, IO_EXCEPTION, CLOSED -> state;
		});

		switch(oldState.kind())
		{
			case IDLE, WRITING ->
			{
				// nothing to do; already handled by update function.
				// In particular, don't release the semaphore;
				// that's only necessary when the writing thread was WAITING_FOR_READY_BYTES.
			}
			case WAITING_FOR_READY_BYTES -> waitingForReadyBytesSemaphore.release();
			case WAITING_FOR_AVAILABLE_BYTES, JUST_GOT_READY_BYTES, WRITING_AND_GOT_NEXT_READY_BYTES -> throw new UnexpectedResponseException("Two reads occurred");
			case EOF -> throw new UnexpectedResponseException("Got ready bytes although we are EOF");
			case IO_EXCEPTION -> multiplexer.throwIOException();
			case CLOSED ->
			{
				// ignore, we already sent the EOF
			}
		}
	}

	void eofReached()
	{
		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case IDLE, WAITING_FOR_AVAILABLE_BYTES, WAITING_FOR_READY_BYTES, JUST_GOT_READY_BYTES, WRITING, WRITING_AND_GOT_NEXT_READY_BYTES -> new State(State.Kind.EOF, -1);
			case EOF, IO_EXCEPTION, CLOSED -> state;
		});
		if(oldState.kind() == State.Kind.WAITING_FOR_READY_BYTES)
			waitingForReadyBytesSemaphore.release();
	}
	void ioExceptionThrown()
	{
		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case IDLE, WAITING_FOR_AVAILABLE_BYTES, WAITING_FOR_READY_BYTES, JUST_GOT_READY_BYTES, WRITING, WRITING_AND_GOT_NEXT_READY_BYTES -> new State(State.Kind.IO_EXCEPTION, -1);
			case EOF, IO_EXCEPTION, CLOSED -> state;
		});
		if(oldState.kind() == State.Kind.WAITING_FOR_READY_BYTES)
			waitingForReadyBytesSemaphore.release();
	}

	private <R> R throwEOF() throws EOFException
	{
		throw new EOFException("The corresponding input stream was closed");
	}

	private <R> R throwClosed() throws ClosedException
	{
		throw new ClosedException();
	}

	@Override
	public void close() throws IOException
	{
		if(closeWithoutSendingEOF())
			multiplexer.writeOutputEOF(exchangeId);
	}
	boolean closeWithoutSendingEOF()
	{
		State oldState = state.getAndUpdate(state -> switch(state.kind())
		{
			case IDLE, WAITING_FOR_AVAILABLE_BYTES, WAITING_FOR_READY_BYTES, JUST_GOT_READY_BYTES, WRITING, WRITING_AND_GOT_NEXT_READY_BYTES, EOF, IO_EXCEPTION -> new State(State.Kind.CLOSED, -1);
			case CLOSED -> state;
		});
		if(oldState.kind() == State.Kind.WAITING_FOR_READY_BYTES)
			waitingForReadyBytesSemaphore.release();
		return oldState.kind() != State.Kind.CLOSED;
	}

	private static record State(Kind kind, int readyBytes)
	{
		private static enum Kind
		{
			IDLE,
			WAITING_FOR_AVAILABLE_BYTES,
			WAITING_FOR_READY_BYTES,
			JUST_GOT_READY_BYTES,
			WRITING,
			WRITING_AND_GOT_NEXT_READY_BYTES,
			EOF,
			IO_EXCEPTION,
			CLOSED;
		}
	}
}
