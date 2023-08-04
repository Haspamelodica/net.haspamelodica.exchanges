package net.haspamelodica.exchanges.pipes;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import net.haspamelodica.exchanges.Exchange;
import net.haspamelodica.exchanges.util.ClosedException;
import net.haspamelodica.exchanges.util.IOAutoCloseable;

public class Pipe implements IOAutoCloseable
{
	private final AtomicReference<State>	state;
	private final Semaphore					readReadySemaphore;
	private final Semaphore					readDoneSemaphore;

	public Pipe()
	{
		state = new AtomicReference<>(new State(Kind.IDLE));
		readReadySemaphore = new Semaphore(0);
		readDoneSemaphore = new Semaphore(0);
	}

	public InputStream in()
	{
		return new InputStream()
		{
			@Override
			public int read() throws IOException
			{
				byte[] buf = new byte[1];
				int read = read(buf);
				if(read < 0)
					return -1;
				if(read != 1)
					throw new IOException("Internal error");
				return buf[0] & 0xFF;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException
			{
				Objects.checkFromIndexSize(off, len, b.length);
				if(len == 0)
					return 0;
				// from here: len > 0

				boolean initial = true;
				for(;;)
				{
					State oldState = state.getAndUpdate(initial
							? state -> switch(state.kind())
							{
								case IDLE -> new State(Kind.READ_WAITING_FOR_WRITE);
								case TRANSFER_COMPLETE -> new State(Kind.TRANSFER_COMPLETE, null, -1, -1, Kind.READ_WAITING_FOR_WRITE);
								case READ_WAITING_FOR_WRITE, BYTES_READY, TRANSFERRING, IN_CLOSED, OUT_CLOSED, CLOSED -> state;
								case WRITE_WAITING_FOR_READ -> new State(Kind.TRANSFERRING);
							}
							: state -> switch(state.kind())
							{
								case IDLE, READ_WAITING_FOR_WRITE, WRITE_WAITING_FOR_READ, TRANSFERRING, TRANSFER_COMPLETE -> throw new IllegalStateException(state.kind().toString());
								case BYTES_READY -> new State(Kind.TRANSFERRING);
								case IN_CLOSED, OUT_CLOSED, CLOSED -> state;
							});
					int readOr0 = switch(oldState.kind())
					{
						case IDLE, TRANSFER_COMPLETE -> 0;
						case READ_WAITING_FOR_WRITE, TRANSFERRING -> throw new IOException("Concurrent reads");
						case WRITE_WAITING_FOR_READ, BYTES_READY ->
						{
							if(initial && oldState.kind() == Kind.BYTES_READY)
								throw new IOException("Concurrent reads");

							int read = Math.min(len, oldState.writtenLen());
							System.arraycopy(oldState.writtenBuf(), oldState.writtenOff(), b, off, read);
							state.getAndUpdate(state -> switch(state.kind())
							{
								case IDLE, READ_WAITING_FOR_WRITE, WRITE_WAITING_FOR_READ, BYTES_READY, TRANSFER_COMPLETE -> throw new IllegalStateException(state.kind().toString());
								case TRANSFERRING -> oldState.writtenLen() != read ? new State(Kind.WRITE_WAITING_FOR_READ,
										oldState.writtenBuf(), oldState.writtenOff() + read, oldState.writtenLen() - read,
										oldState.kindAfterDone())
										: new State(oldState.kindAfterDone(), null, -1, -1, Kind.IDLE);
								case IN_CLOSED, OUT_CLOSED, CLOSED -> state;
							});
							if(oldState.writtenLen() == read)
								readDoneSemaphore.release();
							// ignore if we got closed in the meantime; the read was successful.
							yield read;
						}
						case IN_CLOSED, CLOSED -> throw new ClosedException();
						case OUT_CLOSED -> -1;
					};
					if(readOr0 != 0)
						return readOr0;

					initial = false;
					try
					{
						readReadySemaphore.acquire();
					} catch(InterruptedException e)
					{
						Thread.currentThread().interrupt();
						throw new InterruptedIOException();
					}
				}
			}

			@Override
			public void close()
			{
				State oldState = state.getAndUpdate(state -> switch(state.kind())
				{
					case IDLE, READ_WAITING_FOR_WRITE, WRITE_WAITING_FOR_READ, BYTES_READY, TRANSFERRING -> new State(Kind.IN_CLOSED);
					case TRANSFER_COMPLETE -> new State(Kind.TRANSFER_COMPLETE, null, -1, -1, Kind.IN_CLOSED);
					case IN_CLOSED, CLOSED -> state;
					case OUT_CLOSED -> new State(Kind.CLOSED);
				});
				if(oldState.kind() != Kind.IN_CLOSED && oldState.kind() != Kind.CLOSED)
				{
					readReadySemaphore.release();
					readDoneSemaphore.release();
				}
			}
		};
	}

	public OutputStream out()
	{
		return new OutputStream()
		{
			@Override
			public void write(int b) throws IOException
			{
				write(new byte[] {(byte) b});
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException
			{
				Objects.checkFromIndexSize(off, len, b.length);
				if(len == 0)
					return;
				// from here: len > 0

				boolean initial = true;
				for(;;)
				{
					State oldState = state.getAndUpdate(initial
							? state -> switch(state.kind())
							{
								case IDLE -> new State(Kind.WRITE_WAITING_FOR_READ, b, off, len, Kind.TRANSFER_COMPLETE);
								case READ_WAITING_FOR_WRITE -> new State(Kind.BYTES_READY, b, off, len, Kind.TRANSFER_COMPLETE);
								case WRITE_WAITING_FOR_READ, BYTES_READY, TRANSFERRING, TRANSFER_COMPLETE, IN_CLOSED, OUT_CLOSED, CLOSED -> state;
							}
							: state -> switch(state.kind())
							{
								case IDLE, READ_WAITING_FOR_WRITE, WRITE_WAITING_FOR_READ, BYTES_READY, TRANSFERRING -> throw new IllegalStateException(state.kind().toString());
								case TRANSFER_COMPLETE -> new State(state.kindAfterDone());
								case IN_CLOSED, OUT_CLOSED, CLOSED -> state;
							});
					boolean done = switch(oldState.kind())
					{
						case IDLE -> false;
						case READ_WAITING_FOR_WRITE ->
						{
							readReadySemaphore.release();
							yield false;
						}
						case TRANSFER_COMPLETE ->
						{
							if(initial)
								throw new IOException("Concurrent writes");
							yield true;
						}
						case WRITE_WAITING_FOR_READ, BYTES_READY, TRANSFERRING -> throw new IOException("Concurrent writes");
						case IN_CLOSED -> throw new EOFException();
						case OUT_CLOSED, CLOSED -> throw new ClosedException();
					};
					if(done)
						return;

					initial = false;
					try
					{
						readDoneSemaphore.acquire();
					} catch(InterruptedException e)
					{
						Thread.currentThread().interrupt();
						throw new InterruptedIOException();
					}
				}
			}

			@Override
			public void close()
			{
				State oldState = state.getAndUpdate(state -> switch(state.kind())
				{
					case IDLE, READ_WAITING_FOR_WRITE -> new State(Kind.OUT_CLOSED);
					case WRITE_WAITING_FOR_READ, BYTES_READY, TRANSFERRING, TRANSFER_COMPLETE ->
							new State(state.kind(), state.writtenBuf(), state.writtenOff(), state.writtenLen(), Kind.OUT_CLOSED);
					case IN_CLOSED -> new State(Kind.CLOSED);
					case OUT_CLOSED, CLOSED -> state;
				});
				if(oldState.kind() == Kind.READ_WAITING_FOR_WRITE)
					readReadySemaphore.release();
			}
		};
	}

	public Exchange asExchange()
	{
		return Exchange.of(in(), out(), this::close);
	}

	@Override
	public void close()
	{
		if(state.getAndSet(new State(Kind.CLOSED)).kind() != Kind.CLOSED)
		{
			readReadySemaphore.release();
			readDoneSemaphore.release();
		}
	}

	private static record State(Kind kind, byte[] writtenBuf, int writtenOff, int writtenLen, Kind kindAfterDone)
	{
		public State(Kind kind)
		{
			this(kind, null, -1, -1, null);
		}
	}
	private static enum Kind
	{
		IDLE,
		READ_WAITING_FOR_WRITE,
		WRITE_WAITING_FOR_READ,
		BYTES_READY,
		TRANSFERRING,
		TRANSFER_COMPLETE,
		IN_CLOSED,
		OUT_CLOSED,
		CLOSED;
	}
}
