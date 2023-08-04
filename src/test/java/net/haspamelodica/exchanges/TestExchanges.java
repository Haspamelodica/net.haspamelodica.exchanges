package net.haspamelodica.exchanges;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.RepeatedTest;

import net.haspamelodica.exchanges.multiplexed.MultiplexedExchangePool;
import net.haspamelodica.exchanges.pipes.PipesExchangePool;
import net.haspamelodica.exchanges.util.AutoCloseablePair;

public class TestExchanges
{
	private static final int	REGULAR_TEST_REPETITIONS		= 300;
	private static final int	MANY_BYTES_TEST_REPETITIONS		= 30;
	private static final int	STRESS_TEST_REPETITIONS			= 3;
	private static final int	STRESS_TEST_LENGTH_MULTIPLIER	= 400;
	private static final int	STRESS_TEST_PARALLEL_EXCHANGES	= 400;
	private static final int	MAX_ASSUMED_BUFFER_SIZE			= 40000;

	private static final boolean	TEST_MULTIPLEXED		= true;
	private static final boolean	TEST_PIPED				= true;
	private static final boolean	TEST_PIPED_NOSHAREDMEM	= true;

	@RepeatedTest(REGULAR_TEST_REPETITIONS)
	public void testCreateAndTeardown() throws Exception
	{
		runTest(pool ->
		{}, pool ->
		{});
	}

	@RepeatedTest(REGULAR_TEST_REPETITIONS)
	public void testBasicSingleStream() throws Exception
	{
		byte[] msg = b("test");
		runTest(
				pool -> pool.createNewExchange().out().write(msg),
				pool -> assertArrayEquals(msg, pool.createNewExchange().in().readNBytes(msg.length)));
	}

	@RepeatedTest(REGULAR_TEST_REPETITIONS)
	public void testSingleStreamOutEofNoBytes() throws Exception
	{
		runTest(
				pool -> pool.createNewExchange().out().close(),
				pool -> assertEquals(-1, pool.createNewExchange().in().read()));
	}

	@RepeatedTest(REGULAR_TEST_REPETITIONS)
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

	@RepeatedTest(MANY_BYTES_TEST_REPETITIONS)
	public void testSingleStreamOutEofWithManyBytes() throws Exception
	{
		byte[] msg = b("test".repeat(10000));
		runTest(
				pool ->
				{
					OutputStream out = pool.createNewExchange().out();
					out.write(msg);
					out.close();
				},
				pool ->
				{
					InputStream in = pool.createNewExchange().in();
					assertArrayEquals(msg, in.readAllBytes());
				});
	}

	@RepeatedTest(REGULAR_TEST_REPETITIONS)
	public void testSingleStreamInEof() throws Exception
	{
		byte[] msg = b("test".repeat(MAX_ASSUMED_BUFFER_SIZE / 4));
		runTest(
				pool -> assertThrows(EOFException.class, () ->
				{
					Exchange createNewExchange = pool.createNewExchange();
					createNewExchange.out().write(msg);
				}),
				pool -> pool.createNewExchange().in().close());
	}

	@RepeatedTest(STRESS_TEST_REPETITIONS)
	public void testMultipleStreamsStress() throws Exception
	{
		long seed = ThreadLocalRandom.current().nextLong();
		System.out.println("Seed: " + seed);

		String msgString = "";
		for(int i = 0; i < STRESS_TEST_LENGTH_MULTIPLIER; i ++)
			msgString += "This is a long message, line " + i + ".\n";
		byte[] msg = b(msgString);
		int parallelExchanges = STRESS_TEST_PARALLEL_EXCHANGES;

		runTest((pool1, pool2) ->
		{
			Random seedRnd = new Random(seed);
			DaemonThreadGroup group = new DaemonThreadGroup();
			CyclicBarrier barrier = new CyclicBarrier(parallelExchanges * 2);
			startStressTestsThreads(pool1, true, seedRnd, msg, parallelExchanges, group, barrier);
			startStressTestsThreads(pool2, false, seedRnd, msg, parallelExchanges, group, barrier);
			group.waitForCompletionOrError();
		});
	}

	private void startStressTestsThreads(ExchangePool pool, boolean readFirst, Random seedRnd, byte[] msg, int parallelExchanges,
			DaemonThreadGroup group, CyclicBarrier barrier)
	{
		Semaphore threadSem = new Semaphore(1);
		for(int i = 0; i < parallelExchanges; i ++)
		{
			long seed = seedRnd.nextLong();
			Semaphore prevThreadSem = threadSem;
			Semaphore nextThreadSem = new Semaphore(0);
			threadSem = nextThreadSem;
			group.startThread("#" + i + (readFirst ? "a" : "b"), () ->
			{
				Random random = new Random(seed);
				prevThreadSem.acquire();
				Exchange exchange = pool.createNewExchange();
				nextThreadSem.release();
				barrier.await();
				if(readFirst)
					assertArrayEquals(msg, readRandomly(exchange.in(), random));
				writeRandomly(exchange.out(), msg, random);
				exchange.out().close();
				if(!readFirst)
					assertArrayEquals(msg, readRandomly(exchange.in(), random));
			});
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
			if(len == 1 && random.nextBoolean())
			{
				int read = in.read();
				if(read < 0)
					break;
				buf[readSoFar ++] = (byte) read;
			} else
			{
				int read = in.read(buf, readSoFar, len);
				assertEquals(len == 0, read == 0);
				if(read < 0)
					break;
				readSoFar += read;
			}
		}
		return Arrays.copyOf(buf, readSoFar);
	}

	private static byte[] b(String string)
	{
		return string.getBytes(StandardCharsets.UTF_8);
	}

	private static void runTest(ThrowingConsumer<ExchangePool> action1, ThrowingConsumer<ExchangePool> action2) throws Exception
	{
		runTest((pool1, pool2) ->
		{
			DaemonThreadGroup group = new DaemonThreadGroup();
			group.startThread("a1", () -> action1.accept(pool1));
			group.startThread("a2", () -> action2.accept(pool2));
			group.waitForCompletionOrError();
		});
	}

	private static void runTest(ThrowingBiConsumer<ExchangePool, ExchangePool> action) throws Exception
	{
		if(TEST_MULTIPLEXED)
			try(AutoCloseablePair<Exchange, Exchange> rawPipedExchange = Exchange.openPiped();
					MultiplexedExchangePool pool1 = new MultiplexedExchangePool(rawPipedExchange.a());
					MultiplexedExchangePool pool2 = new MultiplexedExchangePool(rawPipedExchange.b()))
			{
				action.accept(pool1, pool2);
			}

		if(TEST_PIPED)
			try(PipesExchangePool pool = new PipesExchangePool())
			{
				action.accept(pool, pool.getClient());
			}

		if(TEST_PIPED_NOSHAREDMEM)
			try(PipesExchangePool pool = new PipesExchangePool(Exchange::openPipedNoSharedMemory))
			{
				action.accept(pool, pool.getClient());
			}
	}

	private static class DaemonThreadGroup
	{
		private final BlockingQueue<ThreadResult>	threadResults;
		private final AtomicInteger					threadCount;

		public DaemonThreadGroup()
		{
			threadResults = new ArrayBlockingQueue<>(10);
			threadCount = new AtomicInteger();
		}

		public void startThread(String name, ThrowingRunnable action)
		{
			threadCount.incrementAndGet();
			Thread thread = new Thread(() ->
			{
				try
				{
					action.run();
					threadResults.put(new ThreadResult(null, null));
				} catch(Exception e)
				{
					try
					{
						threadResults.put(new ThreadResult(e, null));
					} catch(InterruptedException e1)
					{
						e1.printStackTrace();
					}
				} catch(Error e)
				{
					// JUnit's AssertionFailedError is an error, not an exception
					try
					{
						threadResults.put(new ThreadResult(null, e));
					} catch(InterruptedException e1)
					{
						e1.printStackTrace();
					}
				}
			}, name);
			thread.setDaemon(true);
			thread.start();
		}

		public void waitForCompletionOrError() throws Exception
		{
			for(int i = 0; i < threadCount.get(); i ++)
			{
				ThreadResult result = threadResults.take();
				if(result.exceptionIfAny() != null)
					throw result.exceptionIfAny();
				if(result.errorIfAny() != null)
					throw result.errorIfAny();
			}
		}

		private static record ThreadResult(Exception exceptionIfAny, Error errorIfAny)
		{}
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
