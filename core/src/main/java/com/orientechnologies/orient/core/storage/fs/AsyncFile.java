package com.orientechnologies.orient.core.storage.fs;

import com.orientechnologies.common.concur.lock.ScalableRWLock;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.util.ORawPair;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class AsyncFile implements OFile {
  private static final int ALLOCATION_THRESHOLD = 1024 * 1024;

  private final    ScalableRWLock lock = new ScalableRWLock();
  private volatile Path           osFile;

  private final AtomicLong dirtyCounter   = new AtomicLong();
  private final Object     flushSemaphore = new Object();

  private final AtomicLong size          = new AtomicLong();
  private final AtomicLong committedSize = new AtomicLong();

  private AsynchronousFileChannel fileChannel;

  public AsyncFile(Path osFile) {
    this.osFile = osFile;
  }

  @Override
  public void create() throws IOException {
    lock.exclusiveLock();
    try {
      if (fileChannel != null) {
        throw new OStorageException("File " + osFile + " is already opened.");
      }

      Files.createFile(osFile);

      fileChannel = AsynchronousFileChannel.open(osFile, StandardOpenOption.READ, StandardOpenOption.WRITE);

      initSize();
    } finally {
      lock.exclusiveUnlock();
    }
  }

  private void initSize() throws IOException {
    if (fileChannel.size() < HEADER_SIZE) {
      final ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);

      int written = 0;
      do {
        final Future<Integer> writeFuture = fileChannel.write(buffer, written);
        try {
          written += writeFuture.get();
        } catch (InterruptedException | ExecutionException e) {
          throw OException.wrapException(new OStorageException("Error during write operation to the file " + osFile), e);
        }
      } while (written < HEADER_SIZE);

      dirtyCounter.incrementAndGet();
    }

    final long currentSize = fileChannel.size() - HEADER_SIZE;

    size.set(currentSize);
    committedSize.set(currentSize);
  }

  @Override
  public void open() {
    lock.exclusiveLock();
    try {
      doOpen();
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Can not open file " + osFile), e);
    } finally {
      lock.exclusiveUnlock();
    }
  }

  private void doOpen() throws IOException {
    if (fileChannel != null) {
      throw new OStorageException("File " + osFile + " is already opened.");
    }

    fileChannel = AsynchronousFileChannel.open(osFile, StandardOpenOption.READ, StandardOpenOption.WRITE);

    initSize();
  }

  @Override
  public long getFileSize() {
    return size.get();
  }

  @Override
  public String getName() {
    return osFile.getFileName().toString();
  }

  @Override
  public boolean isOpen() {
    lock.sharedLock();
    try {
      return fileChannel != null;
    } finally {
      lock.sharedUnlock();
    }
  }

  @Override
  public boolean exists() {
    return Files.exists(osFile);
  }

  @Override
  public void write(long offset, ByteBuffer buffer) throws IOException {
    lock.sharedLock();
    try {
      buffer.rewind();

      checkForClose();
      checkPosition(offset);

      int written = 0;
      do {
        final Future<Integer> writeFuture = fileChannel.write(buffer, offset + HEADER_SIZE + written);
        try {
          written += writeFuture.get();
        } catch (InterruptedException | ExecutionException e) {
          throw OException.wrapException(new OStorageException("Error during write operation to the file " + osFile), e);
        }
      } while (written < buffer.limit());
    } finally {
      lock.sharedUnlock();
    }
  }

  @Override
  public IOResult write(List<ORawPair<Long, ByteBuffer>> buffers) {
    final CountDownLatch latch = new CountDownLatch(buffers.size());
    final AsyncIOResult asyncIOResult = new AsyncIOResult(latch);

    for (final ORawPair<Long, ByteBuffer> pair : buffers) {
      final ByteBuffer byteBuffer = pair.getSecond();
      byteBuffer.rewind();
      lock.sharedLock();
      try {
        checkForClose();
        checkPosition(pair.getFirst());

        final long position = pair.getFirst() + HEADER_SIZE;
        fileChannel.write(byteBuffer, position, latch, new WriteHandler(byteBuffer, asyncIOResult, position));
      } finally {
        lock.sharedUnlock();
      }
    }

    return asyncIOResult;
  }

  @Override
  public void read(long offset, ByteBuffer buffer, boolean throwOnEof) throws IOException {
    lock.sharedLock();
    try {
      checkForClose();
      checkPosition(offset);

      int read = 0;
      do {
        final Future<Integer> readFuture = fileChannel.read(buffer, offset + HEADER_SIZE + read);
        final int bytesRead;
        try {
          bytesRead = readFuture.get();
        } catch (InterruptedException | ExecutionException e) {
          throw OException.wrapException(new OStorageException("Error during read operation from the file " + osFile), e);
        }

        if (bytesRead == -1) {
          if (throwOnEof) {
            throw new EOFException("End of file " + osFile + " is reached.");
          }

          break;
        }

        read += bytesRead;
      } while (read < buffer.limit());

    } finally {
      lock.sharedUnlock();
    }
  }

  @Override
  public long allocateSpace(int size) throws IOException {
    lock.sharedLock();
    final long allocatedPosition;
    try {
      final long currentSize = this.size.addAndGet(size);
      allocatedPosition = currentSize - size;

      long currentCommittedSize = this.committedSize.get();

      final long sizeDifference = currentSize - currentCommittedSize;
      if (sizeDifference <= ALLOCATION_THRESHOLD) {
        return allocatedPosition;
      }

      while (currentCommittedSize < currentSize) {
        if (this.committedSize.compareAndSet(currentCommittedSize, currentSize)) {
          break;
        }

        currentCommittedSize = committedSize.get();
      }

      final long sizeDiff = currentSize - currentCommittedSize;
      if (sizeDiff > 0) {
        for (long n = 0; n < sizeDiff / Integer.MAX_VALUE; n++) {
          final int chunkSize = (int) Math.min(Integer.MAX_VALUE, sizeDiff - n * Integer.MAX_VALUE);
          final long ptr = Native.malloc(chunkSize);
          try {
            final Pointer pointer = new Pointer(ptr);
            pointer.setMemory(0, chunkSize, (byte) 0);
            final ByteBuffer buffer = pointer.getByteBuffer(0, chunkSize);
            int written = 0;
            do {
              final Future<Integer> writeFuture = fileChannel
                  .write(buffer, currentCommittedSize + written + HEADER_SIZE + n * Integer.MAX_VALUE);
              try {
                written += writeFuture.get();
              } catch (InterruptedException | ExecutionException e) {
                throw OException.wrapException(new OStorageException("Error during write operation to the file " + osFile), e);
              }
            } while (written < chunkSize);
          } finally {
            Native.free(ptr);
          }
        }

        assert fileChannel.size() >= currentSize + HEADER_SIZE;
      }
    } finally {
      lock.sharedUnlock();
    }

    return allocatedPosition;
  }

  @Override
  public void shrink(long size) throws IOException {
    lock.exclusiveLock();
    try {
      checkForClose();

      this.size.set(0);
      this.committedSize.set(0);

      fileChannel.truncate(size + HEADER_SIZE);
    } finally {
      lock.exclusiveUnlock();
    }
  }

  @Override
  public void synch() {
    lock.sharedLock();
    try {
      synchronized (flushSemaphore) {
        long dirtyCounterValue = dirtyCounter.get();
        if (dirtyCounterValue > 0) {
          try {
            fileChannel.force(false);
          } catch (final IOException e) {
            OLogManager.instance()
                .warn(this, "Error during flush of file %s. Data may be lost in case of power failure", e, getName());
          }

          dirtyCounter.addAndGet(-dirtyCounterValue);
        }
      }
    } finally {
      lock.sharedUnlock();
    }
  }

  @Override
  public void close() {
    lock.exclusiveLock();
    try {
      doClose();
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Error during closing the file " + osFile), e);
    } finally {
      lock.exclusiveUnlock();
    }
  }

  private void doClose() throws IOException {
    fileChannel.close();
    fileChannel = null;
  }

  @Override
  public void delete() throws IOException {
    lock.exclusiveLock();
    try {
      doClose();

      Files.delete(osFile);
    } finally {
      lock.exclusiveUnlock();
    }
  }

  @Override
  public void renameTo(Path newFile) throws IOException {
    lock.exclusiveLock();
    try {
      doClose();

      //noinspection NonAtomicOperationOnVolatileField
      osFile = Files.move(osFile, newFile);

      doOpen();
    } finally {
      lock.exclusiveUnlock();
    }
  }

  @Override
  public void replaceContentWith(final Path newContentFile) throws IOException {
    lock.exclusiveLock();
    try {
      doClose();

      Files.copy(newContentFile, osFile, StandardCopyOption.REPLACE_EXISTING);

      doOpen();
    } finally {
      lock.exclusiveUnlock();
    }
  }

  private void checkPosition(long offset) {
    final long fileSize = size.get();
    if (offset < 0 || offset >= fileSize) {
      throw new OStorageException(
          "You are going to access region outside of allocated file position. File size = " + fileSize + ", requested position "
              + offset);
    }
  }

  private void checkForClose() {
    if (fileChannel == null) {
      throw new OStorageException("File " + osFile + " is closed");
    }
  }

  private final class WriteHandler implements CompletionHandler<Integer, CountDownLatch> {
    private       int           written;
    private final ByteBuffer    byteBuffer;
    private final AsyncIOResult ioResult;
    private final long          position;

    private WriteHandler(ByteBuffer byteBuffer, AsyncIOResult ioResult, long position) {
      this.byteBuffer = byteBuffer;
      this.ioResult = ioResult;
      this.position = position;
    }

    @Override
    public void completed(Integer result, CountDownLatch attachment) {
      written += result;

      if (written < byteBuffer.limit()) {
        lock.sharedLock();
        try {
          checkForClose();

          fileChannel.write(byteBuffer, position + written, attachment, this);
        } finally {
          lock.sharedUnlock();
        }
      } else {
        attachment.countDown();
      }
    }

    @Override
    public void failed(Throwable exc, CountDownLatch attachment) {
      ioResult.exc = exc;
      OLogManager.instance().error(this, "Error during write operation to the file " + osFile, exc);
      attachment.countDown();
    }
  }

  private static final class AsyncIOResult implements IOResult {
    private final CountDownLatch latch;
    private       Throwable      exc;

    private AsyncIOResult(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void await() {
      try {
        latch.await();
      } catch (InterruptedException e) {
        throw OException.wrapException(new OStorageException("IO operation was interrupted"), e);
      }

      if (exc != null) {
        throw OException.wrapException(new OStorageException("Error during IO operation"), exc);
      }
    }
  }
}