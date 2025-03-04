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

package org.apache.cassandra.db.compaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.lifecycle.SSTableSet;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.CompactionParams;
import org.apache.cassandra.utils.Pair;

import static com.google.common.collect.Iterables.filter;
import static org.apache.cassandra.utils.Clock.Global.currentTimeMillis;

public class TimeWindowCompactionStrategy extends AbstractCompactionStrategy
{
    private static final Logger logger = LoggerFactory.getLogger(TimeWindowCompactionStrategy.class);

    private final TimeWindowCompactionStrategyOptions options;
    protected volatile int estimatedRemainingTasks;
    private final Set<SSTableReader> sstables = new HashSet<>();
    private long lastExpiredCheck;
    private long highestWindowSeen;

    // This is accessed in both the threading context of compaction / repair and also JMX
    private volatile Map<Long, Integer> sstableCountByBuckets = Collections.emptyMap();

    public TimeWindowCompactionStrategy(ColumnFamilyStore cfs, Map<String, String> options)
    {
        super(cfs, options);
        this.estimatedRemainingTasks = 0;
        this.options = new TimeWindowCompactionStrategyOptions(options);
        String[] tsOpts = { UNCHECKED_TOMBSTONE_COMPACTION_OPTION, TOMBSTONE_COMPACTION_INTERVAL_OPTION, TOMBSTONE_THRESHOLD_OPTION };
        if (Arrays.stream(tsOpts).map(o -> options.get(o)).filter(Objects::nonNull).anyMatch(v -> !v.equals("false")))
        {
            logger.debug("Enabling tombstone compactions for TWCS");
        }
        else
        {
            logger.debug("Disabling tombstone compactions for TWCS");
            disableTombstoneCompactions = true;
        }
    }

    @Override
    @SuppressWarnings("resource") // transaction is closed by AbstractCompactionTask::execute
    public AbstractCompactionTask getNextBackgroundTask(int gcBefore)
    {
        List<SSTableReader> previousCandidate = null;
        while (true)
        {
            List<SSTableReader> latestBucket = getNextBackgroundSSTables(gcBefore);

            if (latestBucket.isEmpty())
                return null;

            // Already tried acquiring references without success. It means there is a race with
            // the tracker but candidate SSTables were not yet replaced in the compaction strategy manager
            if (latestBucket.equals(previousCandidate))
            {
                logger.warn("Could not acquire references for compacting SSTables {} which is not a problem per se," +
                            "unless it happens frequently, in which case it must be reported. Will retry later.",
                            latestBucket);
                return null;
            }

            LifecycleTransaction modifier = cfs.getTracker().tryModify(latestBucket, OperationType.COMPACTION);
            if (modifier != null)
                return new TimeWindowCompactionTask(cfs, modifier, gcBefore, options.ignoreOverlaps);
            previousCandidate = latestBucket;
        }
    }

    /**
     *
     * @param gcBefore
     * @return
     */
    private synchronized List<SSTableReader> getNextBackgroundSSTables(final int gcBefore)
    {
        if (Iterables.isEmpty(cfs.getSSTables(SSTableSet.LIVE)))
            return Collections.emptyList();

        Set<SSTableReader> uncompacting = ImmutableSet.copyOf(filter(cfs.getUncompactingSSTables(), sstables::contains));

        // Find fully expired SSTables. Those will be included no matter what.
        Set<SSTableReader> expired = Collections.emptySet();

        if (currentTimeMillis() - lastExpiredCheck > options.expiredSSTableCheckFrequency)
        {
            logger.debug("TWCS expired check sufficiently far in the past, checking for fully expired SSTables");
            expired = CompactionController.getFullyExpiredSSTables(cfs, uncompacting, options.ignoreOverlaps ? Collections.emptySet() : cfs.getOverlappingLiveSSTables(uncompacting),
                                                                   gcBefore, options.ignoreOverlaps);
            lastExpiredCheck = currentTimeMillis();
        }
        else
        {
            logger.debug("TWCS skipping check for fully expired SSTables");
        }

        Set<SSTableReader> candidates = Sets.newHashSet(filterSuspectSSTables(uncompacting));

        List<SSTableReader> compactionCandidates = new ArrayList<>(getNextNonExpiredSSTables(Sets.difference(candidates, expired), gcBefore));
        if (!expired.isEmpty())
        {
            logger.debug("Including expired sstables: {}", expired);
            compactionCandidates.addAll(expired);
        }

        return compactionCandidates;
    }

    private List<SSTableReader> getNextNonExpiredSSTables(Iterable<SSTableReader> nonExpiringSSTables, final int gcBefore)
    {
        List<SSTableReader> mostInteresting = getCompactionCandidates(nonExpiringSSTables);

        if (mostInteresting != null)
        {
            return mostInteresting;
        }

        // if there is no sstable to compact in standard way, try compacting single sstable whose droppable tombstone
        // ratio is greater than threshold.
        List<SSTableReader> sstablesWithTombstones = new ArrayList<>();
        for (SSTableReader sstable : nonExpiringSSTables)
        {
            if (worthDroppingTombstones(sstable, gcBefore))
                sstablesWithTombstones.add(sstable);
        }
        if (sstablesWithTombstones.isEmpty())
            return Collections.emptyList();

        return Collections.singletonList(Collections.min(sstablesWithTombstones, SSTableReader.sizeComparator));
    }

    private List<SSTableReader> getCompactionCandidates(Iterable<SSTableReader> candidateSSTables)
    {
        Pair<HashMultimap<Long, SSTableReader>, Long> buckets = getBuckets(candidateSSTables, options.sstableWindowUnit, options.sstableWindowSize, options.timestampResolution);
        // Update the highest window seen, if necessary
        if(buckets.right > this.highestWindowSeen)
            this.highestWindowSeen = buckets.right;

        NewestBucket mostInteresting = newestBucket(buckets.left,
                cfs.getMinimumCompactionThreshold(),
                cfs.getMaximumCompactionThreshold(),
                options.stcsOptions,
                this.highestWindowSeen);

        this.estimatedRemainingTasks = mostInteresting.estimatedRemainingTasks;
        this.sstableCountByBuckets = buckets.left.keySet().stream().collect(Collectors.toMap(Function.identity(), k -> buckets.left.get(k).size()));
        if (!mostInteresting.sstables.isEmpty())
            return mostInteresting.sstables;
        return null;
    }

    @Override
    public synchronized void addSSTable(SSTableReader sstable)
    {
        sstables.add(sstable);
    }

    @Override
    public synchronized void removeSSTable(SSTableReader sstable)
    {
        sstables.remove(sstable);
    }

    @Override
    protected synchronized Set<SSTableReader> getSSTables()
    {
        return ImmutableSet.copyOf(sstables);
    }

    /**
     * Find the lowest and highest timestamps in a given timestamp/unit pair
     * Returns milliseconds, caller should adjust accordingly
     */
    public static Pair<Long,Long> getWindowBoundsInMillis(TimeUnit windowTimeUnit, int windowTimeSize, long timestampInMillis)
    {
        long lowerTimestamp;
        long upperTimestamp;
        long timestampInSeconds = TimeUnit.SECONDS.convert(timestampInMillis, TimeUnit.MILLISECONDS);

        switch(windowTimeUnit)
        {
            case MINUTES:
                lowerTimestamp = timestampInSeconds - ((timestampInSeconds) % (60L * windowTimeSize));
                upperTimestamp = (lowerTimestamp + (60L * (windowTimeSize - 1L))) + 59L;
                break;
            case HOURS:
                lowerTimestamp = timestampInSeconds - ((timestampInSeconds) % (3600L * windowTimeSize));
                upperTimestamp = (lowerTimestamp + (3600L * (windowTimeSize - 1L))) + 3599L;
                break;
            case DAYS:
            default:
                lowerTimestamp = timestampInSeconds - ((timestampInSeconds) % (86400L * windowTimeSize));
                upperTimestamp = (lowerTimestamp + (86400L * (windowTimeSize - 1L))) + 86399L;
                break;
        }

        return Pair.create(TimeUnit.MILLISECONDS.convert(lowerTimestamp, TimeUnit.SECONDS),
                           TimeUnit.MILLISECONDS.convert(upperTimestamp, TimeUnit.SECONDS));

    }

    /**
     * Group files with similar max timestamp into buckets.
     *
     * @param files pairs consisting of a file and its min timestamp
     * @param sstableWindowUnit
     * @param sstableWindowSize
     * @param timestampResolution
     * @return A pair, where the left element is the bucket representation (map of timestamp to sstablereader), and the right is the highest timestamp seen
     */
    @VisibleForTesting
    static Pair<HashMultimap<Long, SSTableReader>, Long> getBuckets(Iterable<SSTableReader> files, TimeUnit sstableWindowUnit, int sstableWindowSize, TimeUnit timestampResolution)
    {
        HashMultimap<Long, SSTableReader> buckets = HashMultimap.create();

        long maxTimestamp = 0;
        // Create hash map to represent buckets
        // For each sstable, add sstable to the time bucket
        // Where the bucket is the file's max timestamp rounded to the nearest window bucket
        for (SSTableReader f : files)
        {
            assert TimeWindowCompactionStrategyOptions.validTimestampTimeUnits.contains(timestampResolution);
            long tStamp = TimeUnit.MILLISECONDS.convert(f.getMaxTimestamp(), timestampResolution);
            Pair<Long,Long> bounds = getWindowBoundsInMillis(sstableWindowUnit, sstableWindowSize, tStamp);
            buckets.put(bounds.left, f);
            if (bounds.left > maxTimestamp)
                maxTimestamp = bounds.left;
        }

        logger.trace("buckets {}, max timestamp {}", buckets, maxTimestamp);
        return Pair.create(buckets, maxTimestamp);
    }

    static final class NewestBucket
    {
        /** The sstables that should be compacted next */
        final List<SSTableReader> sstables;

        /** The number of tasks estimated */
        final int estimatedRemainingTasks;

        NewestBucket(List<SSTableReader> sstables, int estimatedRemainingTasks)
        {
            this.sstables = sstables;
            this.estimatedRemainingTasks = estimatedRemainingTasks;
        }

        @Override
        public String toString()
        {
            return String.format("sstables: %s, estimated remaining tasks: %d", sstables, estimatedRemainingTasks);
        }
    }


    /**
     * @param buckets list of buckets, sorted from newest to oldest, from which to return the newest bucket within thresholds.
     * @param minThreshold minimum number of sstables in a bucket to qualify.
     * @param maxThreshold maximum number of sstables to compact at once (the returned bucket will be trimmed down to this).
     * @return a bucket (list) of sstables to compact.
     */
    @VisibleForTesting
    static NewestBucket newestBucket(HashMultimap<Long, SSTableReader> buckets, int minThreshold, int maxThreshold, SizeTieredCompactionStrategyOptions stcsOptions, long now)
    {
        // If the current bucket has at least minThreshold SSTables, choose that one.
        // For any other bucket, at least 2 SSTables is enough.
        // In any case, limit to maxThreshold SSTables.

        List<SSTableReader> sstables = Collections.emptyList();
        int estimatedRemainingTasks = 0;

        TreeSet<Long> allKeys = new TreeSet<>(buckets.keySet());

        Iterator<Long> it = allKeys.descendingIterator();
        while(it.hasNext())
        {
            Long key = it.next();
            Set<SSTableReader> bucket = buckets.get(key);
            logger.trace("Key {}, now {}", key, now);
            if (bucket.size() >= minThreshold && key >= now)
            {
                // If we're in the newest bucket, we'll use STCS to prioritize sstables
                List<Pair<SSTableReader,Long>> pairs = SizeTieredCompactionStrategy.createSSTableAndLengthPairs(bucket);
                List<List<SSTableReader>> stcsBuckets = SizeTieredCompactionStrategy.getBuckets(pairs, stcsOptions.bucketHigh, stcsOptions.bucketLow, stcsOptions.minSSTableSize);
                List<SSTableReader> stcsInterestingBucket = SizeTieredCompactionStrategy.mostInterestingBucket(stcsBuckets, minThreshold, maxThreshold);

                // If the tables in the current bucket aren't eligible in the STCS strategy, we'll skip it and look for other buckets
                if (!stcsInterestingBucket.isEmpty())
                {
                    double remaining = bucket.size() - maxThreshold;
                    estimatedRemainingTasks +=  1 + (remaining > minThreshold ? Math.ceil(remaining / maxThreshold) : 0);
                    if (sstables.isEmpty())
                    {
                        logger.debug("Using STCS compaction for first window of bucket: data files {} , options {}", pairs, stcsOptions);
                        sstables = stcsInterestingBucket;
                    }
                    else
                    {
                        logger.trace("First window of bucket is eligible but not selected: data files {} , options {}", pairs, stcsOptions);
                    }
                }
            }
            else if (bucket.size() >= 2 && key < now)
            {
                double remaining = bucket.size() - maxThreshold;
                estimatedRemainingTasks +=  1 + (remaining > minThreshold ? Math.ceil(remaining / maxThreshold) : 0);
                if (sstables.isEmpty())
                {
                    logger.debug("bucket size {} >= 2 and not in current bucket, compacting what's here: {}", bucket.size(), bucket);
                    sstables = trimToThreshold(bucket, maxThreshold);
                }
                else
                {
                    logger.trace("bucket size {} >= 2 and not in current bucket, eligible but not selected: {}", bucket.size(), bucket);
                }
            }
            else
            {
                logger.trace("No compaction necessary for bucket size {} , key {}, now {}", bucket.size(), key, now);
            }
        }
        return new NewestBucket(sstables, estimatedRemainingTasks);
    }

    /**
     * @param bucket set of sstables
     * @param maxThreshold maximum number of sstables in a single compaction task.
     * @return A bucket trimmed to the maxThreshold newest sstables.
     */
    @VisibleForTesting
    static List<SSTableReader> trimToThreshold(Set<SSTableReader> bucket, int maxThreshold)
    {
        List<SSTableReader> ssTableReaders = new ArrayList<>(bucket);

        // Trim the largest sstables off the end to meet the maxThreshold
        Collections.sort(ssTableReaders, SSTableReader.sizeComparator);

        return ImmutableList.copyOf(Iterables.limit(ssTableReaders, maxThreshold));
    }

    @Override
    @SuppressWarnings("resource") // transaction is closed by AbstractCompactionTask::execute
    public synchronized Collection<AbstractCompactionTask> getMaximalTask(int gcBefore, boolean splitOutput)
    {
        Iterable<SSTableReader> filteredSSTables = filterSuspectSSTables(sstables);
        if (Iterables.isEmpty(filteredSSTables))
            return null;
        LifecycleTransaction txn = cfs.getTracker().tryModify(filteredSSTables, OperationType.COMPACTION);
        if (txn == null)
            return null;
        return Collections.singleton(new TimeWindowCompactionTask(cfs, txn, gcBefore, options.ignoreOverlaps));
    }

    /**
     * TWCS should not group sstables for anticompaction - this can mix new and old data
     */
    @Override
    public Collection<Collection<SSTableReader>> groupSSTablesForAntiCompaction(Collection<SSTableReader> sstablesToGroup)
    {
        Collection<Collection<SSTableReader>> groups = new ArrayList<>(sstablesToGroup.size());
        for (SSTableReader sstable : sstablesToGroup)
        {
            groups.add(Collections.singleton(sstable));
        }
        return groups;
    }

    @Override
    @SuppressWarnings("resource") // transaction is closed by AbstractCompactionTask::execute
    public synchronized AbstractCompactionTask getUserDefinedTask(Collection<SSTableReader> sstables, int gcBefore)
    {
        assert !sstables.isEmpty(); // checked for by CM.submitUserDefined

        LifecycleTransaction modifier = cfs.getTracker().tryModify(sstables, OperationType.COMPACTION);
        if (modifier == null)
        {
            logger.debug("Unable to mark {} for compaction; probably a background compaction got to it first.  You can disable background compactions temporarily if this is a problem", sstables);
            return null;
        }

        return new TimeWindowCompactionTask(cfs, modifier, gcBefore, options.ignoreOverlaps).setUserDefined(true);
    }

    public int getEstimatedRemainingTasks()
    {
        return this.estimatedRemainingTasks;
    }

    public long getMaxSSTableBytes()
    {
        return Long.MAX_VALUE;
    }

    public Map<Long, Integer> getSSTableCountByBuckets()
    {
        return sstableCountByBuckets;
    }

    public static Map<String, String> validateOptions(Map<String, String> options) throws ConfigurationException
    {
        Map<String, String> uncheckedOptions = AbstractCompactionStrategy.validateOptions(options);
        uncheckedOptions = TimeWindowCompactionStrategyOptions.validateOptions(options, uncheckedOptions);

        uncheckedOptions.remove(CompactionParams.Option.MIN_THRESHOLD.toString());
        uncheckedOptions.remove(CompactionParams.Option.MAX_THRESHOLD.toString());

        return uncheckedOptions;
    }

    public String toString()
    {
        return String.format("TimeWindowCompactionStrategy[%s/%s]",
                cfs.getMinimumCompactionThreshold(),
                cfs.getMaximumCompactionThreshold());
    }
}
