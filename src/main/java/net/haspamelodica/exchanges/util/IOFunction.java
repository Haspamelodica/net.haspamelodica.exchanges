package net.haspamelodica.exchanges.util;

import java.io.IOException;

@FunctionalInterface
public interface IOFunction<P, R>
{
	public R apply(P p) throws IOException;
}
