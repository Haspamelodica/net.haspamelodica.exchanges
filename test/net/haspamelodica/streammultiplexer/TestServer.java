package net.haspamelodica.streammultiplexer;

import static net.haspamelodica.streammultiplexer.TestClient.PORT;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class TestServer
{
	public static void main(String[] args) throws IOException
	{
		try(ServerSocket server = new ServerSocket(PORT); Socket sock = server.accept();
				StreamMultiplexer multiplexer = new StreamMultiplexer(sock.getInputStream(), sock.getOutputStream()))
		{
			byte[] buf = new byte[10];
			multiplexer.getIn(0).read();

			System.out.println(new String(buf, 3, multiplexer.getIn(1).read(buf, 3, buf.length - 3)));
			System.out.println(new String(buf, 3, multiplexer.getIn(2).read(buf, 3, buf.length - 3)));
			System.out.println(new String(buf, 3, multiplexer.getIn(2).read(buf, 3, buf.length - 3)));
			System.out.println(multiplexer.getIn(2).read(buf, 3, buf.length - 3));
			System.out.println(multiplexer.getIn(2).read(buf, 3, buf.length - 3));
			multiplexer.getIn(3).close();
			try
			{
				multiplexer.getIn(1).read();
				throw new IllegalStateException("Expected UnexpectedResponseException");
			} catch(UnexpectedResponseException e)
			{
				// ignore, is expected
			}
		}
	}
}
