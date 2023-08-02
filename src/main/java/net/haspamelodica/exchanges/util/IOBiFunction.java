package net.haspamelodica.exchanges.util;

import java.io.IOException;

public interface IOBiFunction<A, B, R>
{
	public R apply(A a, B b) throws IOException;
}
