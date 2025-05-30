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

package org.apache.paimon.table.source.snapshot;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.Snapshot;
import org.apache.paimon.table.source.ScanMode;
import org.apache.paimon.utils.SnapshotManager;

import java.io.FileNotFoundException;

import static org.apache.paimon.utils.Preconditions.checkArgument;

/**
 * {@link StartingScanner} for the {@link CoreOptions.StartupMode#FROM_SNAPSHOT} or {@link
 * CoreOptions.StartupMode#FROM_SNAPSHOT_FULL} startup mode of a batch read.
 */
public class StaticFromSnapshotStartingScanner extends ReadPlanStartingScanner {

    public StaticFromSnapshotStartingScanner(SnapshotManager snapshotManager, long snapshotId) {
        super(snapshotManager);
        this.startingSnapshotId = snapshotId;
    }

    @Override
    public ScanMode startingScanMode() {
        return ScanMode.ALL;
    }

    @Override
    public SnapshotReader configure(SnapshotReader snapshotReader) {
        return snapshotReader.withMode(ScanMode.ALL).withSnapshot(getSnapshot());
    }

    public Snapshot getSnapshot() {
        try {
            return snapshotManager.tryGetSnapshot(startingSnapshotId);
        } catch (FileNotFoundException e) {
            Long earliestSnapshotId = snapshotManager.earliestSnapshotId();
            Long latestSnapshotId = snapshotManager.latestSnapshotId();

            if (earliestSnapshotId == null || latestSnapshotId == null) {
                throw new IllegalArgumentException("There is currently no snapshot.");
            }

            // Checks earlier whether the specified scan snapshot id is valid.
            checkArgument(
                    startingSnapshotId >= earliestSnapshotId
                            && startingSnapshotId <= latestSnapshotId,
                    "The specified scan snapshotId %s is out of available snapshotId range [%s, %s].",
                    startingSnapshotId,
                    earliestSnapshotId,
                    latestSnapshotId);
            throw new RuntimeException(e);
        }
    }
}
