package net.haspamelodica.exchanges;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import net.haspamelodica.exchanges.multiplexed.MultiplexedExchangePool;
import net.haspamelodica.exchanges.util.AutoClosablePair;

public class TestMultiplexedExchange
{
	@RepeatedTest(300)
	public void testCreateAndTeardown() throws Exception
	{
		runTest(pool ->
		{}, pool ->
		{});
	}

	@RepeatedTest(300)
	public void testBasicSingleStream() throws Exception
	{
		byte[] msg = b("test");
		runTest(
				pool -> pool.createNewExchange().out().write(msg),
				pool -> assertArrayEquals(msg, pool.createNewExchange().in().readNBytes(msg.length)));
	}

	@RepeatedTest(300)
	public void testSingleStreamOutEofNoBytes() throws Exception
	{
		runTest(
				pool -> pool.createNewExchange().out().close(),
				pool -> assertEquals(-1, pool.createNewExchange().in().read()));
	}

	@RepeatedTest(300)
	public void testSingleStreamOutEofWithBytes() throws Exception
	{
		byte[] msg = b("test");
		runTest(
				pool ->
				{
					OutputStream out = pool.createNewExchange().out();
					out.write(msg);
					out.close();
				},
				pool -> assertArrayEquals(msg, pool.createNewExchange().in().readAllBytes()));
	}

	@RepeatedTest(300)
	public void testSingleStreamInEof() throws Exception
	{
		byte[] msg = b("test");
		runTest(
				pool -> assertThrows(EOFException.class, () -> pool.createNewExchange().out().write(msg)),
				pool -> pool.createNewExchange().in().close());
	}

	@Test
	public void testMultipleStreamsStress() throws Exception
	{
		long seed = ThreadLocalRandom.current().nextLong();
		System.out.println("Seed: " + seed);

		String msgString = "";
		for(int i = 0; i < 400; i ++)
			msgString += "This is a long message, line " + i + ".\n";
		byte[] msg = b(msgString);
		int parallelExchanges = 400;

		runTest((pool1, pool2) ->
		{
			Random seedRnd = new Random(seed);
			List<AtomicReference<Exception>> exceptionRefs = new ArrayList<>();
			List<AtomicReference<Error>> errorRefs = new ArrayList<>();
			List<Thread> threads = new ArrayList<>();
			startStressTestsThreads(pool1, true, seedRnd, msg, parallelExchanges, exceptionRefs, errorRefs, threads);
			startStressTestsThreads(pool2, false, seedRnd, msg, parallelExchanges, exceptionRefs, errorRefs, threads);
			for(Thread thread : threads)
				thread.join();
			List<Exception> nonNullExceptions = exceptionRefs.stream().map(AtomicReference::get).filter(Objects::nonNull).toList();
			List<Error> nonNullErrors = errorRefs.stream().map(AtomicReference::get).filter(Objects::nonNull).toList();
			if(nonNullExceptions.size() != 0)
			{
				Exception e = nonNullExceptions.get(0);
				for(int i = 1; i < nonNullExceptions.size(); i ++)
					e.addSuppressed(nonNullExceptions.get(i));
				nonNullErrors.forEach(e::addSuppressed);
				throw e;
			}
			if(nonNullErrors.size() != 0)
			{
				Error e = nonNullErrors.get(0);
				for(int i = 1; i < nonNullErrors.size(); i ++)
					e.addSuppressed(nonNullErrors.get(i));
				throw e;
			}
		});
	}

	private void startStressTestsThreads(MultiplexedExchangePool pool, boolean readFirst, Random seedRnd, byte[] msg, int parallelExchanges,
			List<AtomicReference<Exception>> exceptionRefs, List<AtomicReference<Error>> errorRefs, List<Thread> threads)
	{
		for(int i = 0; i < parallelExchanges; i ++)
		{
			AtomicReference<Exception> exceptionRef = new AtomicReference<>();
			AtomicReference<Error> errorRef = new AtomicReference<>();
			long seed = seedRnd.nextLong();
			threads.add(startDaemon(() ->
			{
				Random random = new Random(seed);
				Exchange exchange = pool.createNewExchange();
				if(readFirst)
					assertArrayEquals(msg, readRandomly(exchange.in(), random));
				writeRandomly(exchange.out(), msg, random);
				exchange.out().close();
				if(!readFirst)
					assertArrayEquals(msg, readRandomly(exchange.in(), random));
			}, exceptionRef, errorRef));
			exceptionRefs.add(exceptionRef);
			errorRefs.add(errorRef);
		}
	}

	private void writeRandomly(OutputStream out, byte[] msg, Random random) throws IOException
	{
		for(int nextMsgIndex = 0; nextMsgIndex < msg.length;)
		{
			int len = random.nextInt(msg.length - nextMsgIndex + 1);
			out.write(msg, nextMsgIndex, len);
			nextMsgIndex += len;
		}
	}

	private byte[] readRandomly(InputStream in, Random random) throws IOException
	{
		byte[] buf = new byte[0];
		int readSoFar = 0;
		for(;;)
		{
			int len = random.nextInt(200);
			buf = Arrays.copyOf(buf, readSoFar + len);
			int read = in.read(buf, readSoFar, len);
			assertEquals(len == 0, read == 0);
			if(read < 0)
				break;
			readSoFar += read;
		}
		return Arrays.copyOf(buf, readSoFar);
	}

	private static byte[] b(String string)
	{
		return string.getBytes(StandardCharsets.UTF_8);
	}

	private static void runTest(ThrowingConsumer<MultiplexedExchangePool> action1, ThrowingConsumer<MultiplexedExchangePool> action2) throws Exception
	{
		runTest((pool1, pool2) ->
		{
			AtomicReference<Exception> exceptionInAction2Ref = new AtomicReference<>();
			AtomicReference<Error> errorInAction2Ref = new AtomicReference<>();
			Thread threadForAction2 = startDaemon(() -> action2.accept(pool2), exceptionInAction2Ref, errorInAction2Ref);
			try
			{
				action1.accept(pool1);
				threadForAction2.join();
			} catch(Exception e)
			{
				Exception exceptionInAction2 = exceptionInAction2Ref.get();
				if(exceptionInAction2 != null)
					e.addSuppressed(exceptionInAction2);
				Error errorInAction2 = errorInAction2Ref.get();
				if(errorInAction2 != null)
					e.addSuppressed(errorInAction2);
				throw e;
			}
			Exception exceptionInAction2 = exceptionInAction2Ref.get();
			if(exceptionInAction2 != null)
				throw exceptionInAction2;
			Error errorInAction2 = errorInAction2Ref.get();
			if(errorInAction2 != null)
				throw errorInAction2;
		});
	}

	private static Thread startDaemon(ThrowingRunnable action, AtomicReference<Exception> exceptionInAction2Ref, AtomicReference<Error> errorInAction2Ref)
	{
		Thread thread = new Thread(() ->
		{
			try
			{
				action.run();
			} catch(Exception e)
			{
				exceptionInAction2Ref.set(e);
			} catch(Error e)
			{
				// JUnit's AssertionFailedError is an error, not an exception
				errorInAction2Ref.set(e);
			}
		});
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	private static void runTest(ThrowingBiConsumer<MultiplexedExchangePool, MultiplexedExchangePool> action) throws Exception
	{
		try(AutoClosablePair<Exchange, Exchange> rawPipedExchange = Exchange.openPiped();
				MultiplexedExchangePool pool1 = new MultiplexedExchangePool(rawPipedExchange.a());
				MultiplexedExchangePool pool2 = new MultiplexedExchangePool(rawPipedExchange.b()))
		{
			action.accept(pool1, pool2);
		}
	}

	private static interface ThrowingRunnable
	{
		public void run() throws Exception;
	}
	private static interface ThrowingConsumer<A>
	{
		public void accept(A a) throws Exception;
	}
	private static interface ThrowingBiConsumer<A, B>
	{
		public void accept(A a, B b) throws Exception;
	}
}
