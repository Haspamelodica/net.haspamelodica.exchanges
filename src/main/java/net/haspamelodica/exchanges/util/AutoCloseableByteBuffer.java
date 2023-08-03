package net.haspamelodica.exchanges.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

public interface AutoCloseableByteBuffer extends AutoCloseable
{
	public ByteBuffer byteBuffer();

	@Override
	public void close() throws IOException;

	public static AutoCloseableByteBuffer openMappedFile(Path path, MapMode mapMode, long offset, long size,
			OpenOption... extraOpenOptions) throws IOException
	{
		return openMappedFile(path, mapMode.toNioMapMode(), offset, size,
				Stream.concat(mapMode.openOptions.stream(), Stream.of(extraOpenOptions)).toArray(OpenOption[]::new));
	}

	public static AutoCloseableByteBuffer openMappedFile(Path path, FileChannel.MapMode mapMode, long offset, long size,
			OpenOption... openOptions) throws IOException
	{
		FileChannel fileChannel = FileChannel.open(path, openOptions);
		//TODO change to MemorySegment once that's stable API
		return wrap(fileChannel.map(mapMode, offset, size), fileChannel::close);
	}

	public static AutoCloseableByteBuffer wrapNoCloseAction(ByteBuffer byteBuffer)
	{
		return wrap(byteBuffer, null);
	}
	public static AutoCloseableByteBuffer wrap(ByteBuffer byteBuffer, IORunnable closeAction)
	{
		return new AutoCloseableByteBufferImpl(byteBuffer, closeAction);
	}

	public static enum MapMode
	{
		READ_ONLY(FileChannel.MapMode.READ_ONLY, StandardOpenOption.READ),
		READ_WRITE(FileChannel.MapMode.READ_WRITE, StandardOpenOption.READ, StandardOpenOption.WRITE),
		PRIVATE(FileChannel.MapMode.PRIVATE, StandardOpenOption.READ);

		private final FileChannel.MapMode	nioMapMode;
		private final List<OpenOption>		openOptions;

		private MapMode(FileChannel.MapMode nioMapMode, OpenOption... openOptions)
		{
			this.nioMapMode = nioMapMode;
			this.openOptions = List.of(openOptions);
		}

		public FileChannel.MapMode toNioMapMode()
		{
			return nioMapMode;
		}
	}
}
