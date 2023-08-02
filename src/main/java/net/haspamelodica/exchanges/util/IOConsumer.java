package net.haspamelodica.exchanges.util;

import java.io.IOException;

public interface IOConsumer<A>
{
	public void accept(A a) throws IOException;
}
