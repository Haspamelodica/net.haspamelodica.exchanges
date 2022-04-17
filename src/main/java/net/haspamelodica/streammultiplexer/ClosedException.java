package net.haspamelodica.streammultiplexer;

import java.io.IOException;

public class ClosedException extends IOException
{
	public ClosedException()
	{}
	public ClosedException(String message)
	{
		super(message);
	}
	public ClosedException(String message, Throwable cause)
	{
		super(message, cause);
	}
	public ClosedException(Throwable cause)
	{
		super(cause);
	}
}
