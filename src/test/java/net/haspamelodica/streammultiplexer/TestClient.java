package net.haspamelodica.streammultiplexer;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public class TestClient
{
	public static final int PORT = 1337;

	public static void main(String[] args) throws IOException
	{
		try(Socket sock = new Socket("localhost", PORT);
				StreamMultiplexer multiplexer = new StreamMultiplexer(sock.getInputStream(), sock.getOutputStream()))
		{
			OutputStream out1 = multiplexer.getOut(1);
			OutputStream out2 = multiplexer.getOut(2);
			OutputStream out3 = multiplexer.getOut(3);
			multiplexer.getOut(0).write(0);

			out1.write("Hello".getBytes());
			out2.write("World".getBytes());
			out2.write("!".getBytes());
			out2.close();
			try
			{
				out3.write("ignored".getBytes());
				throw new IllegalStateException("Expected EOFException");
			} catch(EOFException e)
			{
				// ignore, is expected
			}
			// this triggers an UnexpectedResponseException in the server
			multiplexer.getOut(100).close();
		}
	}
}
