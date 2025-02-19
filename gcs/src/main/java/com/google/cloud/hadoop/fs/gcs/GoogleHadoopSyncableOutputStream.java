/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.hadoop.fs.gcs;

import com.google.cloud.hadoop.gcsio.CreateFileOptions;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystem;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageItemInfo;
import com.google.cloud.hadoop.gcsio.StorageResourceId;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Syncable;

/**
 * GoogleHadoopSyncableOutputStream implements the {@code Syncable} interface by composing objects
 * created in separate underlying streams for each hsync() call.
 *
 * <p>Prior to the first hsync(), sync() or close() call, this channel will behave the same way as a
 * basic non-syncable channel, writing directly to the destination file.
 *
 * <p>On the first call to hsync()/sync(), the destination file is committed and a new temporary
 * file using a hidden-file prefix (underscore) is created with an additional suffix which differs
 * for each subsequent temporary file in the series; during this time readers can read the data
 * committed to the destination file, but not the bytes written to the temporary file since the last
 * hsync() call.
 *
 * <p>On each subsequent hsync()/sync() call, the temporary file closed(), composed onto the
 * destination file, then deleted, and a new temporary file is opened under a new filename for
 * further writes.
 *
 * <p>Caveat: each hsync()/sync() requires many underlying read and mutation requests occurring
 * sequentially, so latency is expected to be fairly high.
 *
 * <p>If errors occur mid-stream, there may be one or more temporary files failing to be cleaned up,
 * and require manual intervention to discover and delete any such unused files. Data written prior
 * to the most recent successful hsync() is persistent and safe in such a case.
 *
 * <p>If multiple writers are attempting to write to the same destination file, generation ids used
 * with low-level precondition checks will cause all but a one writer to fail their precondition
 * checks during writes, and a single remaining writer will safely occupy the stream.
 */
public class GoogleHadoopSyncableOutputStream extends OutputStream implements Syncable {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // Prefix used for all temporary files created by this stream.
  public static final String TEMPFILE_PREFIX = "_GCS_SYNCABLE_TEMPFILE_";

  // Temporary files don't need to contain the desired attributes of the final destination file
  // since metadata settings get clobbered on final compose() anyways; additionally, due to
  // the way we pick temp file names and already ensured directories for the destination file,
  // we can optimize tempfile creation by skipping various directory checks.
  private static final CreateFileOptions TEMPFILE_CREATE_OPTIONS =
      CreateFileOptions.DEFAULT_NO_OVERWRITE
          .toBuilder()
          .setEnsureNoDirectoryConflict(false)
          .setOverwriteGenerationId(0)
          .build();

  // Deletion of temporary files occurs asynchronously for performance reasons, but in-flight
  // deletions are awaited on close() so as long as all output streams are closed, there should
  // be no remaining in-flight work occurring inside this threadpool.
  private static final ExecutorService TEMPFILE_CLEANUP_THREADPOOL =
      Executors.newCachedThreadPool(
          new ThreadFactoryBuilder()
              .setNameFormat("gcs-syncable-output-stream-cleanup-pool-%d")
              .setDaemon(true)
              .build());

  // Instance of GoogleHadoopFileSystemBase.
  private final GoogleHadoopFileSystemBase ghfs;

  // The final destination path for this stream.
  private final URI finalGcsPath;

  // Statistics tracker provided by the parent GoogleHadoopFileSystemBase for recording
  // numbers of bytes written.
  private final FileSystem.Statistics statistics;

  // Metadata/overwrite options to use on final file.
  private final CreateFileOptions fileOptions;

  // List of file-deletion futures accrued during the lifetime of this output stream.
  private final List<Future<Void>> deletionFutures;

  private final SyncableOutputStreamOptions options;

  private final RateLimiter syncRateLimiter;

  private final ExecutorService cleanupThreadpool;

  // Current GCS path pointing at the "tail" file which will be appended to the destination
  // on each hsync() call.
  private URI curGcsPath;

  // Current OutputStream pointing at the "tail" file which will be appended to the destination
  // on each hsync() call.
  private GoogleHadoopOutputStream curDelegate;

  // Stores the current component index corresponding curGcsPath. If close() is called, the total
  // number of components in the finalGcsPath will be curComponentIndex + 1.
  private int curComponentIndex;

  // The last known generationId of the final destination file, or possibly
  // StorageResourceId.UNKNOWN_GENERATION_ID if unknown.
  private long curDestGenerationId;

  /** Creates a new GoogleHadoopSyncableOutputStream. */
  public GoogleHadoopSyncableOutputStream(
      GoogleHadoopFileSystemBase ghfs,
      URI gcsPath,
      FileSystem.Statistics statistics,
      CreateFileOptions createFileOptions,
      SyncableOutputStreamOptions options)
      throws IOException {
    this(ghfs, gcsPath, statistics, createFileOptions, options, TEMPFILE_CLEANUP_THREADPOOL);
  }

  @VisibleForTesting
  GoogleHadoopSyncableOutputStream(
      GoogleHadoopFileSystemBase ghfs,
      URI gcsPath,
      FileSystem.Statistics statistics,
      CreateFileOptions createFileOptions,
      SyncableOutputStreamOptions options,
      ExecutorService cleanupThreadpool)
      throws IOException {
    logger.atFiner().log(
        "GoogleHadoopSyncableOutputStream(gcsPath: %s, createFileOptions:  %s, options: %s)",
        gcsPath, createFileOptions, options);
    this.ghfs = ghfs;
    this.finalGcsPath = gcsPath;
    this.statistics = statistics;
    this.fileOptions = createFileOptions;
    this.deletionFutures = new ArrayList<>();
    this.cleanupThreadpool = cleanupThreadpool;
    this.options = options;
    this.syncRateLimiter = createRateLimiter(options.getMinSyncInterval());

    if (options.isAppendEnabled()) {
      // When appending first component has to go to new temporary file.
      this.curGcsPath = getNextTemporaryPath();
      this.curComponentIndex = 1;
    } else {
      // The first component of the stream will go straight to the destination filename to optimize
      // the case where no hsync() or a single hsync() is called during the lifetime of the stream;
      // committing the first component thus doesn't require any compose() call under the hood.
      this.curGcsPath = gcsPath;
      this.curComponentIndex = 0;
    }

    this.curDelegate = new GoogleHadoopOutputStream(ghfs, curGcsPath, statistics, fileOptions);
    this.curDestGenerationId = StorageResourceId.UNKNOWN_GENERATION_ID;
  }

  private static RateLimiter createRateLimiter(Duration minSyncInterval) {
    if (minSyncInterval.isNegative() || minSyncInterval.isZero()) {
      return null;
    }
    double permitsPerSecond = 1000.0 / minSyncInterval.toMillis();
    return RateLimiter.create(permitsPerSecond);
  }

  @Override
  public void write(int b) throws IOException {
    throwIfNotOpen();
    curDelegate.write(b);
  }

  @Override
  public void write(byte[] b, int offset, int len) throws IOException {
    throwIfNotOpen();
    curDelegate.write(b, offset, len);
  }

  @Override
  public void close() throws IOException {
    logger.atFiner().log(
        "close(): Current tail file: %s final destination: %s", curGcsPath, finalGcsPath);
    if (!isOpen()) {
      logger.atFiner().log("close(): Ignoring; stream already closed.");
      return;
    }
    commitCurrentFile();

    // null denotes stream closed.
    // TODO(user): Add checks which throw IOException if further operations are attempted on a
    // closed stream, except for multiple calls to close(), which should behave as no-ops.
    curGcsPath = null;
    curDelegate = null;

    logger.atFiner().log("close(): Awaiting %s deletionFutures", deletionFutures.size());
    for (Future<?> deletion : deletionFutures) {
      try {
        deletion.get();
      } catch (ExecutionException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        throw new IOException("Failed to delete files while closing stream", e);
      }
    }
  }

  public void sync() throws IOException {
    hsync();
  }

  /**
   * There is no way to flush data to become available for readers without a full-fledged hsync(),
   * If the output stream is only syncable, this method is a no-op. If the output stream is also
   * flushable, this method will simply use the same implementation of hsync().
   *
   * <p>If it is rate limited, unlike hsync(), which will try to acquire the permits and block, it
   * will do nothing.
   */
  @Override
  public void hflush() throws IOException {
    long startTimeNs = System.nanoTime();
    if (!options.isSyncOnFlushEnabled()) {
      logger.atWarning().log(
          "hflush(): No-op: readers will *not* yet see flushed data for %s", finalGcsPath);
      throwIfNotOpen();
      return;
    }
    // If rate limit not set or permit acquired than use hsync()
    if (syncRateLimiter == null || syncRateLimiter.tryAcquire()) {
      logger.atFine().log("hflush() uses hsync() for %s", finalGcsPath);
      hsyncInternal(startTimeNs);
      return;
    }
    logger.atWarning().log(
        "hflush(): No-op due to rate limit (%s): readers will *not* yet see flushed data for %s",
        syncRateLimiter, finalGcsPath);
    throwIfNotOpen();
  }

  @Override
  public void hsync() throws IOException {
    long startTimeNs = System.nanoTime();
    if (syncRateLimiter != null) {
      logger.atFiner().log(
          "hsync(): Rate limited (%s) with blocking permit acquisition for %s",
          syncRateLimiter, finalGcsPath);
      syncRateLimiter.acquire();
    }
    hsyncInternal(startTimeNs);
  }

  /** Internal implementation of hsync, can be reused by hflush() as well. */
  private void hsyncInternal(long startTimeNs) throws IOException {
    logger.atFiner().log(
        "hsync(): Committing tail file %s to final destination %s", curGcsPath, finalGcsPath);
    throwIfNotOpen();

    commitCurrentFile();

    // Use a different temporary path for each temporary component to reduce the possible avenues of
    // race conditions in the face of low-level retries, etc.
    ++curComponentIndex;
    curGcsPath = getNextTemporaryPath();

    logger.atFiner().log(
        "hsync(): Opening next temporary tail file %s as component number %s",
        curGcsPath, curComponentIndex);
    curDelegate =
        new GoogleHadoopOutputStream(ghfs, curGcsPath, statistics, TEMPFILE_CREATE_OPTIONS);

    long finishTimeNs = System.nanoTime();
    logger.atFiner().log("Took %d ns to sync() for %s", finishTimeNs - startTimeNs, finalGcsPath);
  }

  private void commitCurrentFile() throws IOException {
    // TODO(user): Optimize the case where 0 bytes have been written in the current component
    // to return early.
    WritableByteChannel innerChannel = curDelegate.getInternalChannel();
    curDelegate.close();

    long generationId = StorageResourceId.UNKNOWN_GENERATION_ID;
    if (innerChannel instanceof GoogleCloudStorageItemInfo.Provider) {
      generationId =
          ((GoogleCloudStorageItemInfo.Provider) innerChannel).getItemInfo().getContentGeneration();
      logger.atFiner().log(
          "innerChannel is GoogleCloudStorageItemInfo.Provider; closed generationId %s.",
          generationId);
    } else {
      logger.atFiner().log("innerChannel NOT instanceof provider: %s", innerChannel.getClass());
    }

    // On the first component, curGcsPath will equal finalGcsPath, and no compose() call is
    // necessary. Otherwise, we compose in-place into the destination object and then delete
    // the temporary object.
    if (!finalGcsPath.equals(curGcsPath)) {
      StorageResourceId destResourceId =
          StorageResourceId.fromStringPath(finalGcsPath.toString(), curDestGenerationId);
      final StorageResourceId tempResourceId =
          StorageResourceId.fromStringPath(curGcsPath.toString(), generationId);
      if (!destResourceId.getBucketName().equals(tempResourceId.getBucketName())) {
        throw new IllegalStateException(
            String.format(
                "Destination bucket in path '%s' doesn't match temp file bucket in path '%s'",
                finalGcsPath, curGcsPath));
      }
      GoogleCloudStorageItemInfo composedObject =
          ghfs.getGcsFs()
              .getGcs()
              .composeObjects(
                  ImmutableList.of(destResourceId, tempResourceId),
                  destResourceId,
                  GoogleCloudStorageFileSystem.objectOptionsFromFileOptions(fileOptions));
      curDestGenerationId = composedObject.getContentGeneration();
      deletionFutures.add(
          cleanupThreadpool.submit(
              () -> {
                ghfs.getGcsFs().getGcs().deleteObjects(ImmutableList.of(tempResourceId));
                return null;
              }));
    } else {
      // First commit was direct to the destination; the generationId of the object we just
      // committed will be used as the destination generation id for future compose calls.
      curDestGenerationId = generationId;
    }
  }

  /** Returns URI to be used for the next "tail" file in the series. */
  private URI getNextTemporaryPath() {
    Path basePath = ghfs.getHadoopPath(finalGcsPath);
    Path baseDir = basePath.getParent();
    Path tempPath =
        new Path(
            baseDir,
            String.format(
                "%s%s.%d.%s",
                TEMPFILE_PREFIX,
                basePath.getName(),
                curComponentIndex,
                UUID.randomUUID().toString()));
    return ghfs.getGcsPath(tempPath);
  }

  private boolean isOpen() {
    return curDelegate != null;
  }

  private void throwIfNotOpen() throws IOException {
    if (!isOpen()) {
      throw new ClosedChannelException();
    }
  }
}
