package net.haspamelodica.streammultiplexer;

import java.io.IOException;

public class UnexpectedResponseException extends IOException
{
	public UnexpectedResponseException()
	{}
	public UnexpectedResponseException(String message)
	{
		super(message);
	}
	public UnexpectedResponseException(String message, Throwable cause)
	{
		super(message, cause);
	}
	public UnexpectedResponseException(Throwable cause)
	{
		super(cause);
	}
}
