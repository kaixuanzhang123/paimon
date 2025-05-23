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
import org.apache.paimon.KeyValue;
import org.apache.paimon.KeyValueFileStore;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.deletionvectors.DeletionVector;
import org.apache.paimon.disk.IOManager;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.io.KeyValueFileReaderFactory;
import org.apache.paimon.mergetree.DropDeleteReader;
import org.apache.paimon.mergetree.MergeSorter;
import org.apache.paimon.mergetree.MergeTreeReaders;
import org.apache.paimon.mergetree.SortedRun;
import org.apache.paimon.mergetree.compact.ConcatRecordReader;
import org.apache.paimon.mergetree.compact.IntervalPartition;
import org.apache.paimon.mergetree.compact.MergeFunctionFactory;
import org.apache.paimon.mergetree.compact.MergeFunctionFactory.AdjustedProjection;
import org.apache.paimon.mergetree.compact.MergeFunctionWrapper;
import org.apache.paimon.mergetree.compact.ReducerMergeFunctionWrapper;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.reader.ReaderSupplier;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.DeletionFile;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.ProjectedRow;
import org.apache.paimon.utils.Projection;
import org.apache.paimon.utils.UserDefinedSeqComparator;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.paimon.predicate.PredicateBuilder.containsFields;
import static org.apache.paimon.predicate.PredicateBuilder.splitAnd;

/**
 * An implementation for {@link KeyValueFileStore}, this class handle LSM merging and changelog row
 * kind things, it will force reading fields such as sequence and row_kind.
 *
 * @see RawFileSplitRead If in batch mode and reading raw files, it is recommended to use this read.
 */
public class MergeFileSplitRead implements SplitRead<KeyValue> {

    private final TableSchema tableSchema;
    private final FileIO fileIO;
    private final KeyValueFileReaderFactory.Builder readerFactoryBuilder;
    private final Comparator<InternalRow> keyComparator;
    private final MergeFunctionFactory<KeyValue> mfFactory;
    private final MergeSorter mergeSorter;
    private final List<String> sequenceFields;
    private final boolean sequenceOrder;

    @Nullable private RowType readKeyType;

    @Nullable private List<Predicate> filtersForKeys;

    @Nullable private List<Predicate> filtersForAll;

    @Nullable private int[][] pushdownProjection;
    @Nullable private int[][] outerProjection;

    private boolean forceKeepDelete = false;

    public MergeFileSplitRead(
            CoreOptions options,
            TableSchema schema,
            RowType keyType,
            RowType valueType,
            Comparator<InternalRow> keyComparator,
            MergeFunctionFactory<KeyValue> mfFactory,
            KeyValueFileReaderFactory.Builder readerFactoryBuilder) {
        this.tableSchema = schema;
        this.readerFactoryBuilder = readerFactoryBuilder;
        this.fileIO = readerFactoryBuilder.fileIO();
        this.keyComparator = keyComparator;
        this.mfFactory = mfFactory;
        this.mergeSorter =
                new MergeSorter(
                        CoreOptions.fromMap(tableSchema.options()), keyType, valueType, null);
        this.sequenceFields = options.sequenceField();
        this.sequenceOrder = options.sequenceFieldSortOrderIsAscending();
    }

    public Comparator<InternalRow> keyComparator() {
        return keyComparator;
    }

    public MergeSorter mergeSorter() {
        return mergeSorter;
    }

    public TableSchema tableSchema() {
        return tableSchema;
    }

    public MergeFileSplitRead withReadKeyType(RowType readKeyType) {
        readerFactoryBuilder.withReadKeyType(readKeyType);
        this.readKeyType = readKeyType;
        return this;
    }

    @Override
    public MergeFileSplitRead withReadType(RowType readType) {
        // todo: replace projectedFields with readType
        RowType tableRowType = tableSchema.logicalRowType();
        int[][] projectedFields =
                Arrays.stream(tableRowType.getFieldIndices(readType.getFieldNames()))
                        .mapToObj(i -> new int[] {i})
                        .toArray(int[][]::new);
        int[][] newProjectedFields = projectedFields;
        if (sequenceFields.size() > 0) {
            // make sure projection contains sequence fields
            List<String> fieldNames = tableSchema.fieldNames();
            List<String> projectedNames = Projection.of(projectedFields).project(fieldNames);
            int[] lackFields =
                    sequenceFields.stream()
                            .filter(f -> !projectedNames.contains(f))
                            .mapToInt(fieldNames::indexOf)
                            .toArray();
            if (lackFields.length > 0) {
                newProjectedFields =
                        Arrays.copyOf(projectedFields, projectedFields.length + lackFields.length);
                for (int i = 0; i < lackFields.length; i++) {
                    newProjectedFields[projectedFields.length + i] = new int[] {lackFields[i]};
                }
            }
        }

        AdjustedProjection projection = mfFactory.adjustProjection(newProjectedFields);
        this.pushdownProjection = projection.pushdownProjection;
        this.outerProjection = projection.outerProjection;
        if (pushdownProjection != null) {
            List<DataField> tableFields = tableRowType.getFields();
            List<DataField> readFields = readType.getFields();
            List<DataField> finalReadFields = new ArrayList<>();
            for (int i : Arrays.stream(pushdownProjection).mapToInt(arr -> arr[0]).toArray()) {
                DataField requiredField = tableFields.get(i);
                finalReadFields.add(
                        readFields.stream()
                                .filter(x -> x.name().equals(requiredField.name()))
                                .findFirst()
                                .orElse(requiredField));
            }
            RowType pushdownRowType = new RowType(finalReadFields);
            readerFactoryBuilder.withReadValueType(pushdownRowType);
            mergeSorter.setProjectedValueType(pushdownRowType);
        }

        if (newProjectedFields != projectedFields) {
            // Discard the completed sequence fields
            if (outerProjection == null) {
                outerProjection = Projection.range(0, projectedFields.length).toNestedIndexes();
            } else {
                outerProjection = Arrays.copyOf(outerProjection, projectedFields.length);
            }
        }

        return this;
    }

    @Override
    public MergeFileSplitRead withIOManager(IOManager ioManager) {
        this.mergeSorter.setIOManager(ioManager);
        return this;
    }

    @Override
    public MergeFileSplitRead forceKeepDelete() {
        this.forceKeepDelete = true;
        return this;
    }

    @Override
    public MergeFileSplitRead withFilter(Predicate predicate) {
        if (predicate == null) {
            return this;
        }

        List<Predicate> allFilters = new ArrayList<>();
        List<Predicate> pkFilters = null;
        List<String> primaryKeys = tableSchema.trimmedPrimaryKeys();
        Set<String> nonPrimaryKeys =
                tableSchema.fieldNames().stream()
                        .filter(name -> !primaryKeys.contains(name))
                        .collect(Collectors.toSet());
        for (Predicate sub : splitAnd(predicate)) {
            allFilters.add(sub);
            if (!containsFields(sub, nonPrimaryKeys)) {
                if (pkFilters == null) {
                    pkFilters = new ArrayList<>();
                }
                // TODO Actually, the index is wrong, but it is OK.
                //  The orc filter just use name instead of index.
                pkFilters.add(sub);
            }
        }
        // Consider this case:
        // Denote (seqNumber, key, value) as a record. We have two overlapping runs in a section:
        //   * First run: (1, k1, 100), (2, k2, 200)
        //   * Second run: (3, k1, 10), (4, k2, 20)
        // If we push down filter "value >= 100" for this section, only the first run will be read,
        // and the second run is lost. This will produce incorrect results.
        //
        // So for sections with overlapping runs, we only push down key filters.
        // For sections with only one run, as each key only appears once, it is OK to push down
        // value filters.
        filtersForAll = allFilters;
        filtersForKeys = pkFilters;
        return this;
    }

    @Override
    public RecordReader<KeyValue> createReader(DataSplit split) throws IOException {
        if (!split.beforeFiles().isEmpty()) {
            throw new IllegalArgumentException("This read cannot accept split with before files.");
        }

        if (split.isStreaming() || split.bucket() == BucketMode.POSTPONE_BUCKET) {
            return createNoMergeReader(
                    split.partition(),
                    split.bucket(),
                    split.dataFiles(),
                    split.deletionFiles().orElse(null),
                    split.isStreaming());
        } else {
            return createMergeReader(
                    split.partition(),
                    split.bucket(),
                    split.dataFiles(),
                    split.deletionFiles().orElse(null),
                    forceKeepDelete);
        }
    }

    public RecordReader<KeyValue> createMergeReader(
            BinaryRow partition,
            int bucket,
            List<DataFileMeta> files,
            @Nullable List<DeletionFile> deletionFiles,
            boolean keepDelete)
            throws IOException {
        // Sections are read by SortMergeReader, which sorts and merges records by keys.
        // So we cannot project keys or else the sorting will be incorrect.
        DeletionVector.Factory dvFactory = DeletionVector.factory(fileIO, files, deletionFiles);
        KeyValueFileReaderFactory overlappedSectionFactory =
                readerFactoryBuilder.build(partition, bucket, dvFactory, false, filtersForKeys);
        KeyValueFileReaderFactory nonOverlappedSectionFactory =
                readerFactoryBuilder.build(partition, bucket, dvFactory, false, filtersForAll);

        List<ReaderSupplier<KeyValue>> sectionReaders = new ArrayList<>();
        MergeFunctionWrapper<KeyValue> mergeFuncWrapper =
                new ReducerMergeFunctionWrapper(mfFactory.create(pushdownProjection));
        for (List<SortedRun> section : new IntervalPartition(files, keyComparator).partition()) {
            sectionReaders.add(
                    () ->
                            MergeTreeReaders.readerForSection(
                                    section,
                                    section.size() > 1
                                            ? overlappedSectionFactory
                                            : nonOverlappedSectionFactory,
                                    keyComparator,
                                    createUdsComparator(),
                                    mergeFuncWrapper,
                                    mergeSorter));
        }
        RecordReader<KeyValue> reader = ConcatRecordReader.create(sectionReaders);

        if (!keepDelete) {
            reader = new DropDeleteReader(reader);
        }

        return projectOuter(projectKey(reader));
    }

    public RecordReader<KeyValue> createNoMergeReader(
            BinaryRow partition,
            int bucket,
            List<DataFileMeta> files,
            @Nullable List<DeletionFile> deletionFiles,
            boolean onlyFilterKey)
            throws IOException {
        KeyValueFileReaderFactory readerFactory =
                readerFactoryBuilder.build(
                        partition,
                        bucket,
                        DeletionVector.factory(fileIO, files, deletionFiles),
                        true,
                        onlyFilterKey ? filtersForKeys : filtersForAll);
        List<ReaderSupplier<KeyValue>> suppliers = new ArrayList<>();
        for (DataFileMeta file : files) {
            suppliers.add(() -> readerFactory.createRecordReader(file));
        }

        return projectOuter(ConcatRecordReader.create(suppliers));
    }

    private RecordReader<KeyValue> projectKey(RecordReader<KeyValue> reader) {
        if (readKeyType == null) {
            return reader;
        }

        ProjectedRow projectedRow = ProjectedRow.from(readKeyType, tableSchema.logicalRowType());
        return reader.transform(kv -> kv.replaceKey(projectedRow.replaceRow(kv.key())));
    }

    private RecordReader<KeyValue> projectOuter(RecordReader<KeyValue> reader) {
        if (outerProjection != null) {
            ProjectedRow projectedRow = ProjectedRow.from(outerProjection);
            reader = reader.transform(kv -> kv.replaceValue(projectedRow.replaceRow(kv.value())));
        }
        return reader;
    }

    @Nullable
    public UserDefinedSeqComparator createUdsComparator() {
        return UserDefinedSeqComparator.create(
                readerFactoryBuilder.readValueType(), sequenceFields, sequenceOrder);
    }
}
