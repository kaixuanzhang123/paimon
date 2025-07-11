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

package org.apache.paimon.operation;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.io.CompactIncrement;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.io.DataIncrement;
import org.apache.paimon.options.Options;
import org.apache.paimon.partition.PartitionStatistics;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.CatalogEnvironment;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.PartitionHandler;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.CommitMessageImpl;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.TableCommitImpl;
import org.apache.paimon.types.RowType;
import org.apache.paimon.types.VarCharType;
import org.apache.paimon.utils.SnapshotManager;

import org.apache.paimon.shade.guava30.com.google.common.collect.Streams;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.apache.paimon.CoreOptions.METASTORE_PARTITIONED_TABLE;
import static org.apache.paimon.CoreOptions.PARTITION_EXPIRATION_CHECK_INTERVAL;
import static org.apache.paimon.CoreOptions.PARTITION_EXPIRATION_TIME;
import static org.apache.paimon.CoreOptions.PARTITION_TIMESTAMP_FORMATTER;
import static org.apache.paimon.CoreOptions.PATH;
import static org.apache.paimon.CoreOptions.WRITE_ONLY;
import static org.apache.paimon.CoreOptions.createCommitUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test for {@link PartitionExpire}. */
public class PartitionExpireTest {

    @TempDir java.nio.file.Path tempDir;

    private Path path;
    private FileStoreTable table;
    private List<Map<String, String>> deletedPartitions;

    @BeforeEach
    public void beforeEach() {
        path = new Path(tempDir.toUri());
    }

    private void newTable() {
        LocalFileIO fileIO = LocalFileIO.create();
        Options options = new Options();
        options.set(PATH, path.toString());
        Path tablePath = CoreOptions.path(options);
        String branchName = CoreOptions.branch(options.toMap());
        TableSchema tableSchema = new SchemaManager(fileIO, tablePath, branchName).latest().get();
        deletedPartitions = new ArrayList<>();
        PartitionHandler partitionHandler =
                new PartitionHandler() {
                    @Override
                    public void createPartitions(List<Map<String, String>> partitions)
                            throws Catalog.TableNotExistException {}

                    @Override
                    public void dropPartitions(List<Map<String, String>> partitions)
                            throws Catalog.TableNotExistException {
                        deletedPartitions.addAll(partitions);
                        try (FileStoreCommit commit =
                                table.store()
                                        .newCommit(
                                                createCommitUser(
                                                        table.coreOptions().toConfiguration()),
                                                null)) {
                            commit.dropPartitions(partitions, BatchWriteBuilder.COMMIT_IDENTIFIER);
                        }
                    }

                    @Override
                    public void alterPartitions(List<PartitionStatistics> partitions)
                            throws Catalog.TableNotExistException {}

                    @Override
                    public void markDonePartitions(List<Map<String, String>> partitions)
                            throws Catalog.TableNotExistException {}

                    @Override
                    public void close() throws Exception {}
                };

        CatalogEnvironment env =
                new CatalogEnvironment(null, null, null, null, null, false) {

                    @Override
                    public PartitionHandler partitionHandler() {
                        return partitionHandler;
                    }
                };
        table = FileStoreTableFactory.create(fileIO, path, tableSchema, env);
    }

    @Test
    public void testNonPartitionedTable() {
        SchemaManager schemaManager = new SchemaManager(LocalFileIO.create(), path);
        assertThatThrownBy(
                        () ->
                                schemaManager.createTable(
                                        new Schema(
                                                RowType.of(VarCharType.STRING_TYPE).getFields(),
                                                emptyList(),
                                                emptyList(),
                                                Collections.singletonMap(
                                                        PARTITION_EXPIRATION_TIME.key(), "1 d"),
                                                "")))
                .hasMessageContaining(
                        "Can not set 'partition.expiration-time' for non-partitioned table");
    }

    @Test
    public void testIllegalPartition() throws Exception {
        SchemaManager schemaManager = new SchemaManager(LocalFileIO.create(), path);
        schemaManager.createTable(
                new Schema(
                        RowType.of(VarCharType.STRING_TYPE, VarCharType.STRING_TYPE).getFields(),
                        singletonList("f0"),
                        emptyList(),
                        Collections.emptyMap(),
                        ""));
        newTable();
        write("20230101", "11");
        write("abcd", "12");
        write("20230101", "12");
        write("20230103", "31");
        write("20230103", "32");
        write("20230105", "51");
        PartitionExpire expire = newExpire();
        expire.setLastCheck(date(1));
        Assertions.assertDoesNotThrow(() -> expire.expire(date(8), Long.MAX_VALUE));
        assertThat(read()).containsExactlyInAnyOrder("abcd:12");
    }

    @Test
    public void testBatchExpire() throws Exception {
        SchemaManager schemaManager = new SchemaManager(LocalFileIO.create(), path);
        schemaManager.createTable(
                new Schema(
                        RowType.of(VarCharType.STRING_TYPE, VarCharType.STRING_TYPE).getFields(),
                        singletonList("f0"),
                        emptyList(),
                        Collections.emptyMap(),
                        ""));
        newTable();
        table =
                table.copy(
                        Collections.singletonMap(
                                CoreOptions.PARTITION_EXPIRATION_BATCH_SIZE.key(), "1"));
        write("20230101", "11");
        write("20230101", "12");
        write("20230103", "31");
        write("20230103", "32");
        write("20230105", "51");
        PartitionExpire expire = newExpire();
        expire.setLastCheck(date(1));
        Assertions.assertDoesNotThrow(() -> expire.expire(date(8), Long.MAX_VALUE));

        long overwriteSnapshotCnt =
                Streams.stream(table.snapshotManager().snapshots())
                        .filter(snapshot -> snapshot.commitKind() == Snapshot.CommitKind.OVERWRITE)
                        .count();
        assertThat(overwriteSnapshotCnt).isEqualTo(3L);
    }

    @Test
    public void test() throws Exception {
        SchemaManager schemaManager = new SchemaManager(LocalFileIO.create(), path);
        schemaManager.createTable(
                new Schema(
                        RowType.of(VarCharType.STRING_TYPE, VarCharType.STRING_TYPE).getFields(),
                        singletonList("f0"),
                        emptyList(),
                        Collections.singletonMap(METASTORE_PARTITIONED_TABLE.key(), "true"),
                        ""));
        newTable();

        write("20230101", "11");
        write("20230101", "12");
        write("20230103", "31");
        write("20230103", "32");
        write("20230105", "51");

        PartitionExpire expire = newExpire();
        expire.setLastCheck(date(1));

        expire.expire(date(3), Long.MAX_VALUE);
        assertThat(read())
                .containsExactlyInAnyOrder(
                        "20230101:11", "20230101:12", "20230103:31", "20230103:32", "20230105:51");

        expire.expire(date(5), Long.MAX_VALUE);
        assertThat(read()).containsExactlyInAnyOrder("20230103:31", "20230103:32", "20230105:51");

        // PARTITION_EXPIRATION_INTERVAL not trigger
        expire.expire(date(6), Long.MAX_VALUE);
        assertThat(read()).containsExactlyInAnyOrder("20230103:31", "20230103:32", "20230105:51");

        expire.expire(date(8), Long.MAX_VALUE);
        assertThat(read()).isEmpty();

        assertThat(deletedPartitions)
                .containsExactlyInAnyOrder(
                        new LinkedHashMap<>(Collections.singletonMap("f0", "20230101")),
                        new LinkedHashMap<>(Collections.singletonMap("f0", "20230103")),
                        new LinkedHashMap<>(Collections.singletonMap("f0", "20230105")));
    }

    @Test
    public void testFilterCommittedAfterExpiring() throws Exception {
        SchemaManager schemaManager = new SchemaManager(LocalFileIO.create(), path);
        schemaManager.createTable(
                new Schema(
                        RowType.of(VarCharType.STRING_TYPE, VarCharType.STRING_TYPE).getFields(),
                        singletonList("f0"),
                        emptyList(),
                        Collections.emptyMap(),
                        ""));

        newTable();
        // disable compaction and snapshot expiration
        table = table.copy(Collections.singletonMap(WRITE_ONLY.key(), "true"));
        String commitUser = UUID.randomUUID().toString();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // prepare commits
        LocalDate now = LocalDate.now();
        int preparedCommits = random.nextInt(20, 30);

        List<List<CommitMessage>> commitMessages = new ArrayList<>();
        for (int i = 0; i < preparedCommits; i++) {
            // ensure the partition will be expired
            String f0 =
                    now.minus(random.nextInt(10), ChronoUnit.DAYS)
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String f1 = String.valueOf(random.nextInt(25));
            StreamTableWrite write = table.newWrite(commitUser);
            write.write(GenericRow.of(BinaryString.fromString(f0), BinaryString.fromString(f1)));
            commitMessages.add(write.prepareCommit(false, i));
        }

        // commit a part of data and trigger partition expire
        int successCommits = random.nextInt(preparedCommits / 4, preparedCommits / 2);
        StreamTableCommit commit = table.newCommit(commitUser);
        for (int i = 0; i < successCommits - 2; i++) {
            commit.commit(i, commitMessages.get(i));
        }

        // we need two commits to trigger partition expire
        // the first commit will set the last check time to now
        // the second commit will do the partition expire
        Map<String, String> options = new HashMap<>();
        options.put(WRITE_ONLY.key(), "false");
        options.put(PARTITION_EXPIRATION_TIME.key(), "1 d");
        options.put(PARTITION_EXPIRATION_CHECK_INTERVAL.key(), "5 s");
        options.put(PARTITION_TIMESTAMP_FORMATTER.key(), "yyyyMMdd");
        table = table.copy(options);
        commit.close();
        commit = table.newCommit(commitUser);
        commit.commit(successCommits - 2, commitMessages.get(successCommits - 2));
        Thread.sleep(5000);
        commit.commit(successCommits - 1, commitMessages.get(successCommits - 1));

        // check whether partition expire is triggered
        SnapshotManager snapshotManager = table.snapshotManager();
        Snapshot snapshot = snapshotManager.latestSnapshot();
        assertThat(snapshot.commitKind()).isEqualTo(Snapshot.CommitKind.OVERWRITE);

        // filterAndCommit
        Map<Long, List<CommitMessage>> allCommits = new HashMap<>();
        for (int i = 0; i < preparedCommits; i++) {
            allCommits.put((long) i, commitMessages.get(i));
        }

        // no exception here
        commit.filterAndCommit(allCommits);
        commit.close();

        // check commit last
        assertThat(snapshotManager.latestSnapshot().commitIdentifier())
                .isEqualTo(allCommits.size() - 1);
    }

    @Test
    public void testDeleteExpiredPartition() throws Exception {
        SchemaManager schemaManager = new SchemaManager(LocalFileIO.create(), path);
        schemaManager.createTable(
                new Schema(
                        RowType.of(VarCharType.STRING_TYPE, VarCharType.STRING_TYPE).getFields(),
                        singletonList("f0"),
                        emptyList(),
                        Collections.emptyMap(),
                        ""));
        newTable();
        table = newExpireTable();

        List<CommitMessage> commitMessages = write("20230101", "11");
        write("20230105", "51");

        PartitionExpire expire = newExpire();
        expire.setLastCheck(date(1));
        expire.expire(date(5), Long.MAX_VALUE);
        assertThat(read()).containsExactlyInAnyOrder("20230105:51");

        TableCommitImpl commit = table.newCommit("");
        CommitMessageImpl message = (CommitMessageImpl) commitMessages.get(0);
        DataFileMeta file = message.newFilesIncrement().newFiles().get(0);
        CommitMessageImpl newMessage =
                new CommitMessageImpl(
                        message.partition(),
                        message.bucket(),
                        message.totalBuckets(),
                        new DataIncrement(emptyList(), emptyList(), emptyList()),
                        new CompactIncrement(singletonList(file), emptyList(), emptyList()));

        assertThatThrownBy(() -> commit.commit(0L, singletonList(newMessage)))
                .hasMessage(
                        "You are writing data to expired partitions, and you can filter "
                                + "this data to avoid job failover. Otherwise, continuous expired records will cause the"
                                + " job to failover restart continuously. Expired partitions are: [20230101]");
    }

    private List<String> read() throws IOException {
        List<String> ret = new ArrayList<>();
        table.newRead()
                .createReader(table.newScan().plan().splits())
                .forEachRemaining(row -> ret.add(row.getString(0) + ":" + row.getString(1)));
        return ret;
    }

    private LocalDateTime date(int day) {
        return LocalDateTime.of(LocalDate.of(2023, 1, day), LocalTime.MIN);
    }

    private List<CommitMessage> write(String f0, String f1) throws Exception {
        StreamTableWrite write =
                table.copy(Collections.singletonMap(WRITE_ONLY.key(), "true")).newWrite("");
        write.write(GenericRow.of(BinaryString.fromString(f0), BinaryString.fromString(f1)));
        TableCommitImpl commit = table.newCommit("");
        List<CommitMessage> commitMessages = write.prepareCommit(true, 0);
        commit.commit(0, commitMessages);
        write.close();
        commit.close();

        return commitMessages;
    }

    private PartitionExpire newExpire() {
        FileStoreTable table = newExpireTable();
        return table.store().newPartitionExpire("", table);
    }

    private FileStoreTable newExpireTable() {
        Map<String, String> options = new HashMap<>();
        options.put(PARTITION_EXPIRATION_TIME.key(), "2 d");
        options.put(CoreOptions.PARTITION_EXPIRATION_CHECK_INTERVAL.key(), "1 d");
        options.put(CoreOptions.PARTITION_TIMESTAMP_FORMATTER.key(), "yyyyMMdd");
        return table.copy(options);
    }
}
