/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.lakesoul.sink.writer;

import org.apache.flink.api.connector.sink.Sink;
import org.apache.flink.api.connector.sink.SinkWriter;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;
import org.apache.flink.lakesoul.sink.FileSinkCommittable;
import org.apache.flink.lakesoul.sink.LakeSoulMultiTablesSink;
import org.apache.flink.lakesoul.types.TableId;
import org.apache.flink.lakesoul.types.TableSchemaIdentity;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.groups.SinkWriterMetricGroup;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketWriter;
import org.apache.flink.streaming.api.functions.sink.filesystem.OutputFileConfig;
import org.apache.flink.streaming.api.functions.sink.filesystem.RollingPolicy;
import org.apache.flink.table.data.RowData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A {@link SinkWriter} implementation for {@link LakeSoulMultiTablesSink}.
 *
 * <p>It writes data to and manages the different active {@link FileWriterBucket buckes} in the
 * {@link LakeSoulMultiTablesSink}.
 *
 * @param <IN> The type of input elements.
 */
public abstract class AbstractLakeSoulMultiTableSinkWriter<IN>
        implements SinkWriter<IN, FileSinkCommittable, FileWriterBucketState>,
                Sink.ProcessingTimeService.ProcessingTimeCallback {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractLakeSoulMultiTableSinkWriter.class);

    private final int subTaskId;

    private final FileWriterBucketFactory bucketFactory;

    private final RollingPolicy<RowData, String> rollingPolicy;

    private final Sink.ProcessingTimeService processingTimeService;

    private final long bucketCheckInterval;

    // --------------------------- runtime fields -----------------------------

    private final BucketerContext bucketerContext;

    private final Map<Tuple2<TableId, String>, FileWriterBucket> activeBuckets;

    private final OutputFileConfig outputFileConfig;

    private final Counter recordsOutCounter;

    private final ConcurrentHashMap<TableSchemaIdentity, TableSchemaWriterCreator> perTableSchemaWriterCreator;

    private final ClassLoader userClassLoader;

    private final Configuration conf;

    /**
     * A constructor creating a new empty bucket manager.
     * <p>
     * //     * @param basePath The base path for our buckets.
     *
     * @param subTaskId
     * @param metricGroup   {@link SinkWriterMetricGroup} to set sink writer specific metrics.
     *                      //     * @param bucketAssigner The {@link BucketAssigner} provided by the user.
     * @param bucketFactory The {@link FileWriterBucketFactory} to be used to create buckets.
     *                      //     * @param bucketWriter The {@link BucketWriter} to be used when writing data.
     * @param rollingPolicy The {@link RollingPolicy} as specified by the user.
     */
    public AbstractLakeSoulMultiTableSinkWriter(
            int subTaskId, final SinkWriterMetricGroup metricGroup,
            final FileWriterBucketFactory bucketFactory,
            final RollingPolicy<RowData, String> rollingPolicy,
            final OutputFileConfig outputFileConfig,
            final Sink.ProcessingTimeService processingTimeService,
            final long bucketCheckInterval,
            final ClassLoader userClassLoader,
            final Configuration conf) {
        this.subTaskId = subTaskId;

        this.bucketFactory = checkNotNull(bucketFactory);
        this.rollingPolicy = checkNotNull(rollingPolicy);

        this.perTableSchemaWriterCreator = new ConcurrentHashMap<>();

        this.outputFileConfig = checkNotNull(outputFileConfig);

        this.activeBuckets = new HashMap<>();
        this.bucketerContext = new BucketerContext();

        this.recordsOutCounter =
                checkNotNull(metricGroup).getIOMetricGroup().getNumRecordsOutCounter();
        this.processingTimeService = checkNotNull(processingTimeService);
        checkArgument(
                bucketCheckInterval > 0,
                "Bucket checking interval for processing time should be positive.");
        this.bucketCheckInterval = bucketCheckInterval;
        this.userClassLoader = userClassLoader;
        this.conf = conf;
    }

    /**
     * Initializes the state after recovery from a failure.
     *
     * <p>During this process:
     *
     * <ol>
     *   <li>we set the initial value for part counter to the maximum value used before across all
     *       tasks and buckets. This guarantees that we do not overwrite valid data,
     *   <li>we commit any pending files for previous checkpoints (previous to the last successful
     *       one from which we restore),
     *   <li>we resume writing to the previous in-progress file of each bucket, and
     *   <li>if we receive multiple states for the same bucket, we merge them.
     * </ol>
     *
     * @param bucketStates the state holding recovered state about active buckets.
     * @throws IOException if anything goes wrong during retrieving the state or
     *     restoring/committing of any in-progress/pending part files
     */
    public void initializeState(List<FileWriterBucketState> bucketStates) throws IOException {
        checkNotNull(bucketStates, "The retrieved state was null.");

        for (FileWriterBucketState state : bucketStates) {
            String bucketId = state.getBucketId();

            if (LOG.isDebugEnabled()) {
                LOG.debug("Restoring: {}", state);
            }

//            FileWriterBucket<IN> restoredBucket =
//                    bucketFactory.restoreBucket(
//                            bucketWriter, rollingPolicy, state, outputFileConfig);
//
//            updateActiveBucketId(bucketId, restoredBucket);
        }

        registerNextBucketInspectionTimer();
    }

    private void updateActiveBucketId(TableId tableId, String bucketId, FileWriterBucket restoredBucket)
            throws IOException {
        final FileWriterBucket bucket = activeBuckets.get(Tuple2.of(tableId, bucketId));
        if (bucket != null) {
            bucket.merge(restoredBucket);
        } else {
            activeBuckets.put(Tuple2.of(tableId, bucketId), restoredBucket);
        }
    }

    protected TableSchemaWriterCreator getOrCreateTableSchemaWriterCreator(TableSchemaIdentity identity) {
        return perTableSchemaWriterCreator.computeIfAbsent(identity, identity1 -> {
            try {
                return TableSchemaWriterCreator.create(identity1.tableId, identity1.rowType,
                        identity1.tableLocation, identity1.primaryKeys,
                        identity1.partitionKeyList, userClassLoader, conf);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    protected abstract List<Tuple2<TableSchemaIdentity, RowData>> extractTableSchemaAndRowData(IN element) throws Exception;

    @Override
    public void write(IN element, Context context) throws IOException {
        // setting the values in the bucketer context
        bucketerContext.update(
                context.timestamp(),
                context.currentWatermark(),
                processingTimeService.getCurrentProcessingTime());

        List<Tuple2<TableSchemaIdentity, RowData>> schemaAndRowDatas;
        try {
            schemaAndRowDatas = extractTableSchemaAndRowData(element);
        } catch (Exception e) {
            throw new IOException(e);
        }
        for (Tuple2<TableSchemaIdentity, RowData> schemaAndRowData : schemaAndRowDatas) {
            TableSchemaIdentity identity = schemaAndRowData.f0;
            RowData rowData = schemaAndRowData.f1;
            TableSchemaWriterCreator creator = getOrCreateTableSchemaWriterCreator(identity);
            final String bucketId = creator.bucketAssigner.getBucketId(rowData, bucketerContext);
            final FileWriterBucket bucket = getOrCreateBucketForBucketId(identity.tableId, bucketId, creator);
            bucket.write(rowData, processingTimeService.getCurrentProcessingTime());
            recordsOutCounter.inc();
        }
    }

    @Override
    public List<FileSinkCommittable> prepareCommit(boolean flush) throws IOException {
        List<FileSinkCommittable> committables = new ArrayList<>();

        // Every time before we prepare commit, we first check and remove the inactive
        // buckets. Checking the activeness right before pre-committing avoid re-creating
        // the bucket every time if the bucket use OnCheckpointingRollingPolicy.
        Iterator<Map.Entry<Tuple2<TableId, String>, FileWriterBucket>> activeBucketIt =
                activeBuckets.entrySet().iterator();
        while (activeBucketIt.hasNext()) {
            Map.Entry<Tuple2<TableId, String>, FileWriterBucket> entry = activeBucketIt.next();
            if (!entry.getValue().isActive()) {
                activeBucketIt.remove();
            } else {
                committables.addAll(entry.getValue().prepareCommit(flush));
            }
        }

        return committables;
    }

    @Override
    public List<FileWriterBucketState> snapshotState(long checkpointId) throws IOException {

        List<FileWriterBucketState> state = new ArrayList<>();
        for (FileWriterBucket bucket : activeBuckets.values()) {
            state.add(bucket.snapshotState());
        }

        return state;
    }

    private FileWriterBucket getOrCreateBucketForBucketId(
            TableId tableId,
            String bucketId,
            TableSchemaWriterCreator creator) throws IOException {
        FileWriterBucket bucket = activeBuckets.get(Tuple2.of(creator.identity.tableId, bucketId));
        if (bucket == null) {
            final Path bucketPath = assembleBucketPath(creator.tableLocation, bucketId);
            BucketWriter<RowData, String> bucketWriter = creator.createBucketWriter();
            bucket =
                    bucketFactory.getNewBucket(
                            subTaskId,
                            creator.identity,
                            bucketId, bucketPath, bucketWriter, rollingPolicy, outputFileConfig, creator.comparator);
            activeBuckets.put(Tuple2.of(tableId, bucketId), bucket);
        }
        return bucket;
    }

    @Override
    public void close() {
        if (activeBuckets != null) {
            activeBuckets.values().forEach(FileWriterBucket::disposePartFile);
        }
    }

    private Path assembleBucketPath(Path basePath, String bucketId) {
        System.out.println("assemble path: " + basePath);
        if ("".equals(bucketId)) {
            return basePath;
        }
        return new Path(basePath, bucketId);
    }

    @Override
    public void onProcessingTime(long time) throws IOException {
        for (FileWriterBucket bucket : activeBuckets.values()) {
            bucket.onProcessingTime(time);
        }

        registerNextBucketInspectionTimer();
    }

    private void registerNextBucketInspectionTimer() {
        final long nextInspectionTime =
                processingTimeService.getCurrentProcessingTime() + bucketCheckInterval;
        processingTimeService.registerProcessingTimer(nextInspectionTime, this);
    }

    /**
     * The {@link BucketAssigner.Context} exposed to the {@link BucketAssigner#getBucketId(Object,
     * BucketAssigner.Context)} whenever a new incoming element arrives.
     */
    private static final class BucketerContext implements BucketAssigner.Context {

        @Nullable private Long elementTimestamp;

        private long currentWatermark;

        private long currentProcessingTime;

        private BucketerContext() {
            this.elementTimestamp = null;
            this.currentWatermark = Long.MIN_VALUE;
            this.currentProcessingTime = Long.MIN_VALUE;
        }

        void update(@Nullable Long elementTimestamp, long watermark, long currentProcessingTime) {
            this.elementTimestamp = elementTimestamp;
            this.currentWatermark = watermark;
            this.currentProcessingTime = currentProcessingTime;
        }

        @Override
        public long currentProcessingTime() {
            return currentProcessingTime;
        }

        @Override
        public long currentWatermark() {
            return currentWatermark;
        }

        @Override
        @Nullable
        public Long timestamp() {
            return elementTimestamp;
        }
    }
}
