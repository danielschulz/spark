/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.network.shuffle;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

import org.apache.spark.network.buffer.FileSegmentManagedBuffer;
import org.apache.spark.network.client.StreamCallbackWithID;
import org.apache.spark.network.shuffle.protocol.ExecutorShuffleInfo;
import org.apache.spark.network.shuffle.protocol.FinalizeShuffleMerge;
import org.apache.spark.network.shuffle.protocol.MergeStatuses;
import org.apache.spark.network.shuffle.protocol.PushBlockStream;
import org.apache.spark.network.util.MapConfigProvider;
import org.apache.spark.network.util.TransportConf;

/**
 * Tests for {@link RemoteBlockPushResolver}.
 */
public class RemoteBlockPushResolverSuite {

  private static final Logger log = LoggerFactory.getLogger(RemoteBlockPushResolverSuite.class);
  private final String TEST_APP = "testApp";
  private final String BLOCK_MANAGER_DIR = "blockmgr-193d8401";

  private TransportConf conf;
  private RemoteBlockPushResolver pushResolver;
  private Path[] localDirs;

  @Before
  public void before() throws IOException {
    localDirs = createLocalDirs(2);
    MapConfigProvider provider = new MapConfigProvider(
      ImmutableMap.of("spark.shuffle.server.minChunkSizeInMergedShuffleFile", "4"));
    conf = new TransportConf("shuffle", provider);
    pushResolver = new RemoteBlockPushResolver(conf);
    registerExecutor(TEST_APP, prepareLocalDirs(localDirs));
  }

  @After
  public void after() {
    try {
      for (Path local : localDirs) {
        FileUtils.deleteDirectory(local.toFile());
      }
      removeApplication(TEST_APP);
    } catch (Exception e) {
      // don't fail if clean up doesn't succeed.
      log.debug("Error while tearing down", e);
    }
  }

  @Test(expected = RuntimeException.class)
  public void testNoIndexFile() {
    try {
      pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    } catch (Throwable t) {
      assertTrue(t.getMessage().startsWith("Merged shuffle index file"));
      Throwables.propagate(t);
    }
  }

  @Test
  public void testBasicBlockMerge() throws IOException {
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, ByteBuffer.wrap(new byte[4])),
      new PushBlock(0, 1, 0, ByteBuffer.wrap(new byte[5]))
    };
    pushBlockHelper(TEST_APP, pushBlocks);
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, 0));
    validateMergeStatuses(statuses, new int[] {0}, new long[] {9});
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, blockMeta, new int[]{4, 5}, new int[][]{{0}, {1}});
  }

  @Test
  public void testDividingMergedBlocksIntoChunks() throws IOException {
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, ByteBuffer.wrap(new byte[2])),
      new PushBlock(0, 1, 0, ByteBuffer.wrap(new byte[3])),
      new PushBlock(0, 2, 0, ByteBuffer.wrap(new byte[5])),
      new PushBlock(0, 3, 0, ByteBuffer.wrap(new byte[3]))
    };
    pushBlockHelper(TEST_APP, pushBlocks);
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, 0));
    validateMergeStatuses(statuses, new int[] {0}, new long[] {13});
    MergedBlockMeta meta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, meta, new int[]{5, 5, 3}, new int[][]{{0, 1}, {2}, {3}});
  }

  @Test
  public void testFinalizeWithMultipleReducePartitions() throws IOException {
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, ByteBuffer.wrap(new byte[2])),
      new PushBlock(0, 1, 0, ByteBuffer.wrap(new byte[3])),
      new PushBlock(0, 0, 1, ByteBuffer.wrap(new byte[5])),
      new PushBlock(0, 1, 1, ByteBuffer.wrap(new byte[3]))
    };
    pushBlockHelper(TEST_APP, pushBlocks);
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, 0));
    validateMergeStatuses(statuses, new int[] {0, 1}, new long[] {5, 8});
    MergedBlockMeta meta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, meta, new int[]{5}, new int[][]{{0, 1}});
  }

  @Test
  public void testDeferredBufsAreWrittenDuringOnData() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 1, 0, 0));
    // This should be deferred
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[3]));
    // stream 1 now completes
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());
    // stream 2 has more data and then completes
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[3]));
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, blockMeta, new int[]{4, 6}, new int[][]{{0}, {1}});
  }

  @Test
  public void testDeferredBufsAreWrittenDuringOnComplete() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 1, 0, 0));
    // This should be deferred
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[3]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[3]));
    // stream 1 now completes
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());
    // stream 2 now completes completes
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, blockMeta, new int[]{4, 6}, new int[][]{{0}, {1}});
  }

  @Test
  public void testDuplicateBlocksAreIgnoredWhenPrevStreamHasCompleted() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    // This should be ignored
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, blockMeta, new int[]{4}, new int[][]{{0}});
  }

  @Test
  public void testDuplicateBlocksAreIgnoredWhenPrevStreamIsInProgress() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    // This should be ignored
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    // stream 1 now completes
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());
    // stream 2 now completes completes
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, blockMeta, new int[]{4}, new int[][]{{0}});
  }

  @Test
  public void testFailureAfterData() throws IOException {
    StreamCallbackWithID stream =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[4]));
    stream.onFailure(stream.getID(), new RuntimeException("Forced Failure"));
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    assertEquals("num-chunks", 0, blockMeta.getNumChunks());
  }

  @Test
  public void testFailureAfterMultipleDataBlocks() throws IOException {
    StreamCallbackWithID stream =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[2]));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[3]));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[4]));
    stream.onFailure(stream.getID(), new RuntimeException("Forced Failure"));
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    assertEquals("num-chunks", 0, blockMeta.getNumChunks());
  }

  @Test
  public void testFailureAfterComplete() throws IOException {
    StreamCallbackWithID stream =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[2]));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[3]));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[4]));
    stream.onComplete(stream.getID());
    stream.onFailure(stream.getID(), new RuntimeException("Forced Failure"));
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, blockMeta, new int[]{9}, new int[][]{{0}});
  }

  @Test (expected = RuntimeException.class)
  public void testTooLateArrival() throws IOException {
    ByteBuffer[] blocks = new ByteBuffer[]{
      ByteBuffer.wrap(new byte[4]),
      ByteBuffer.wrap(new byte[5])
    };
    StreamCallbackWithID stream = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    for (ByteBuffer block : blocks) {
      stream.onData(stream.getID(), block);
    }
    stream.onComplete(stream.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    StreamCallbackWithID stream1 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, 0, 1, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[4]));
    try {
      stream1.onComplete(stream1.getID());
    } catch (RuntimeException re) {
      assertEquals(
        "Block shufflePush_0_1_0 received after merged shuffle is finalized",
          re.getMessage());
      MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
      validateChunks(TEST_APP, 0, 0, blockMeta, new int[]{9}, new int[][]{{0}});
      throw re;
    }
  }

  @Test
  public void testIncompleteStreamsAreOverwritten() throws IOException {
    registerExecutor(TEST_APP, prepareLocalDirs(localDirs));
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[4]));
    // There is a failure
    stream1.onFailure(stream1.getID(), new RuntimeException("forced error"));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 1, 0, 0));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[5]));
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, blockMeta, new int[]{5}, new int[][]{{1}});
  }

  @Test (expected = RuntimeException.class)
  public void testCollision() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 1, 0, 0));
    // This should be deferred
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[5]));
    // Since stream2 didn't get any opportunity it will throw couldn't find opportunity error
    try {
      stream2.onComplete(stream2.getID());
    } catch (RuntimeException re) {
      assertEquals(
        "Couldn't find an opportunity to write block shufflePush_0_1_0 to merged shuffle",
        re.getMessage());
      throw re;
    }
  }

  @Test (expected = RuntimeException.class)
  public void testFailureInAStreamDoesNotInterfereWithStreamWhichIsWriting() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 1, 0, 0));
    // There is a failure with stream2
    stream2.onFailure(stream2.getID(), new RuntimeException("forced error"));
    StreamCallbackWithID stream3 =
      pushResolver.receiveBlockDataAsStream(new PushBlockStream(TEST_APP, 0, 2, 0, 0));
    // This should be deferred
    stream3.onData(stream3.getID(), ByteBuffer.wrap(new byte[5]));
    // Since this stream didn't get any opportunity it will throw couldn't find opportunity error
    RuntimeException failedEx = null;
    try {
      stream3.onComplete(stream3.getID());
    } catch (RuntimeException re) {
      assertEquals(
        "Couldn't find an opportunity to write block shufflePush_0_2_0 to merged shuffle",
        re.getMessage());
      failedEx = re;
    }
    // stream 1 now completes
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());

    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0);
    validateChunks(TEST_APP, 0, 0, blockMeta, new int[] {4}, new int[][] {{0}});
    if (failedEx != null) {
      throw failedEx;
    }
  }

  @Test(expected = NullPointerException.class)
  public void testUpdateLocalDirsOnlyOnce() throws IOException {
    String testApp = "updateLocalDirsOnlyOnceTest";
    Path[] activeLocalDirs = createLocalDirs(1);
    registerExecutor(testApp, prepareLocalDirs(activeLocalDirs));
    assertEquals(pushResolver.getMergedBlockDirs(testApp).length, 1);
    assertTrue(pushResolver.getMergedBlockDirs(testApp)[0].contains(
      activeLocalDirs[0].toFile().getPath()));
    // Any later executor register from the same application should not change the active local
    // dirs list
    Path[] updatedLocalDirs = localDirs;
    registerExecutor(testApp, prepareLocalDirs(updatedLocalDirs));
    assertEquals(pushResolver.getMergedBlockDirs(testApp).length, 1);
    assertTrue(pushResolver.getMergedBlockDirs(testApp)[0].contains(
      activeLocalDirs[0].toFile().getPath()));
    removeApplication(testApp);
    try {
      pushResolver.getMergedBlockDirs(testApp);
    } catch (Throwable e) {
      assertTrue(e.getMessage()
        .startsWith("application " + testApp + " is not registered or NM was restarted."));
      Throwables.propagate(e);
    }
  }

  @Test
  public void testCleanUpDirectory() throws IOException, InterruptedException {
    String testApp = "cleanUpDirectory";
    Semaphore deleted = new Semaphore(0);
    pushResolver = new RemoteBlockPushResolver(conf) {
      @Override
      void deleteExecutorDirs(Path[] dirs) {
        super.deleteExecutorDirs(dirs);
        deleted.release();
      }
    };
    Path[] activeDirs = createLocalDirs(1);
    registerExecutor(testApp, prepareLocalDirs(activeDirs));
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, ByteBuffer.wrap(new byte[4]))};
    pushBlockHelper(testApp, pushBlocks);
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(testApp, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(testApp, 0, 0);
    validateChunks(testApp, 0, 0, blockMeta, new int[]{4}, new int[][]{{0}});
    String[] mergeDirs = pushResolver.getMergedBlockDirs(testApp);
    pushResolver.applicationRemoved(testApp,  true);
    // Since the cleanup happen in a different thread, check few times to see if the merge dirs gets
    // deleted.
    deleted.acquire();
    for (String mergeDir : mergeDirs) {
      Assert.assertFalse(Files.exists(Paths.get(mergeDir)));
    }
  }

  private Path[] createLocalDirs(int numLocalDirs) throws IOException {
    Path[] localDirs = new Path[numLocalDirs];
    for (int i = 0; i < localDirs.length; i++) {
      localDirs[i] = Files.createTempDirectory("shuffleMerge");
      localDirs[i].toFile().deleteOnExit();
    }
    return localDirs;
  }

  private void registerExecutor(String appId, String[] localDirs) throws IOException {
    ExecutorShuffleInfo shuffleInfo = new ExecutorShuffleInfo(localDirs, 1, "mergedShuffle");
    pushResolver.registerExecutor(appId, shuffleInfo);
  }

  private String[] prepareLocalDirs(Path[] localDirs) throws IOException {
    String[] blockMgrDirs = new String[localDirs.length];
    for (int i = 0; i< localDirs.length; i++) {
      Files.createDirectories(localDirs[i].resolve(
        RemoteBlockPushResolver.MERGE_MANAGER_DIR + File.separator + "00"));
      blockMgrDirs[i] = localDirs[i].toFile().getPath() + File.separator + BLOCK_MANAGER_DIR;
    }
    return blockMgrDirs;
  }

  private void removeApplication(String appId) {
    // PushResolver cleans up the local dirs in a different thread which can conflict with the test
    // data of other tests, since they are using the same Application Id.
    pushResolver.applicationRemoved(appId,  false);
  }

  private void validateMergeStatuses(
      MergeStatuses mergeStatuses,
      int[] expectedReduceIds,
      long[] expectedSizes) {
    assertArrayEquals(expectedReduceIds, mergeStatuses.reduceIds);
    assertArrayEquals(expectedSizes, mergeStatuses.sizes);
  }

  private void validateChunks(
      String appId,
      int shuffleId,
      int reduceId,
      MergedBlockMeta meta,
      int[] expectedSizes,
      int[][] expectedMapsPerChunk) throws IOException {
    assertEquals("num chunks", expectedSizes.length, meta.getNumChunks());
    RoaringBitmap[] bitmaps = meta.readChunkBitmaps();
    assertEquals("num of bitmaps", meta.getNumChunks(), bitmaps.length);
    for (int i = 0; i < meta.getNumChunks(); i++) {
      RoaringBitmap chunkBitmap = bitmaps[i];
      Arrays.stream(expectedMapsPerChunk[i]).forEach(x -> assertTrue(chunkBitmap.contains(x)));
    }
    for (int i = 0; i < meta.getNumChunks(); i++) {
      FileSegmentManagedBuffer mb =
        (FileSegmentManagedBuffer) pushResolver.getMergedBlockData(appId, shuffleId, reduceId, i);
      assertEquals(expectedSizes[i], mb.getLength());
    }
  }

  private void pushBlockHelper(
      String appId,
      PushBlock[] blocks) throws IOException {
    for (int i = 0; i < blocks.length; i++) {
      StreamCallbackWithID stream = pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(appId, blocks[i].shuffleId, blocks[i].mapIndex, blocks[i].reduceId, 0));
      stream.onData(stream.getID(), blocks[i].buffer);
      stream.onComplete(stream.getID());
    }
  }

  private static class PushBlock {
    private final int shuffleId;
    private final int mapIndex;
    private final int reduceId;
    private final ByteBuffer buffer;
    PushBlock(int shuffleId, int mapIndex, int reduceId, ByteBuffer buffer) {
      this.shuffleId = shuffleId;
      this.mapIndex = mapIndex;
      this.reduceId = reduceId;
      this.buffer = buffer;
    }
  }
}
