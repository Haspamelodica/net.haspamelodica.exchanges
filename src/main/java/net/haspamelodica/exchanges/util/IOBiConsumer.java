package net.haspamelodica.exchanges.util;

import java.io.IOException;

@FunctionalInterface
public interface IOBiConsumer<A, B>
{
	public void accept(A a, B b) throws IOException;
}
