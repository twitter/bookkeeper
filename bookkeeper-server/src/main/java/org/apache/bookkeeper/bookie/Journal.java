/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.bookkeeper.bookie;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import org.apache.bookkeeper.bookie.LedgerDirsManager.NoWritableLedgerDirException;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.OpStatsLogger;
import org.apache.bookkeeper.stats.Stats;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.util.DaemonThreadFactory;
import org.apache.bookkeeper.util.IOUtils;
import org.apache.bookkeeper.util.MathUtils;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.apache.bookkeeper.util.SafeRunnable;
import org.apache.bookkeeper.util.ZeroBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.bookkeeper.bookie.BookKeeperServerStats.*;

/**
 * Provide journal related management.
 */
class Journal extends BookieCriticalThread implements CheckpointSource {

    static Logger LOG = LoggerFactory.getLogger(Journal.class);

    /**
     * Filter to pickup journals
     */
    private static interface JournalIdFilter {
        public boolean accept(long journalId);
    }

    /**
     * List all journal ids by a specified journal id filer
     *
     * @param journalDir journal dir
     * @param filter journal id filter
     * @return list of filtered ids
     */
    private static List<Long> listJournalIds(File journalDir, JournalIdFilter filter) {
        File logFiles[] = journalDir.listFiles();
        List<Long> logs = new ArrayList<Long>();
        for(File f: logFiles) {
            String name = f.getName();
            if (!name.endsWith(".txn")) {
                continue;
            }
            String idString = name.split("\\.")[0];
            long id = Long.parseLong(idString, 16);
            if (filter != null) {
                if (filter.accept(id)) {
                    logs.add(id);
                }
            } else {
                logs.add(id);
            }
        }
        Collections.sort(logs);
        return logs;
    }

    /**
     * A wrapper over log mark to provide a checkpoint for users of journal
     * to do checkpointing.
     */
    private static class LogMarkCheckpoint implements Checkpoint {
        final LastLogMark mark;

        public LogMarkCheckpoint(LastLogMark checkpoint) {
            this.mark = checkpoint;
        }

        @Override
        public int compareTo(Checkpoint o) {
            if (o == Checkpoint.MAX) {
                return -1;
            } else if (o == Checkpoint.MIN) {
                return 1;
            }
            return mark.getCurMark().compare(((LogMarkCheckpoint) o).mark.getCurMark());
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof LogMarkCheckpoint)) {
                return false;
            }
            return 0 == compareTo((LogMarkCheckpoint)o);
        }

        @Override
        public int hashCode() {
            return mark.hashCode();
        }

        @Override
        public String toString() {
            return mark.toString();
        }
    }

    /**
     * Last Log Mark
     */
    class LastLogMark {
        private final LogMark curMark;

        LastLogMark(long logId, long logPosition) {
            this.curMark = new LogMark(logId, logPosition);
        }
        void setCurLogMark(long logId, long logPosition) {
            curMark.setLogMark(logId, logPosition);
        }
        LastLogMark markLog() {
            return new LastLogMark(curMark.getLogFileId(), curMark.getLogFileOffset());
        }
        LogMark getCurMark() {
            return curMark;
        }

        void rollLog(LastLogMark lastMark) throws NoWritableLedgerDirException {
            byte buff[] = new byte[16];
            ByteBuffer bb = ByteBuffer.wrap(buff);
            // we should record <logId, logPosition> marked in markLog
            // which is safe since records before lastMark have been
            // persisted to disk (both index & entry logger)
            lastMark.getCurMark().writeLogMark(bb);
            LOG.debug("RollLog to persist last marked log : {}", lastMark.getCurMark());
            List<File> writableLedgerDirs = ledgerDirsManager
                    .getWritableLedgerDirs();
            for (File dir : writableLedgerDirs) {
                File file = new File(dir, "lastMark");
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(file);
                    fos.write(buff);
                    fos.getChannel().force(true);
                    fos.close();
                    fos = null;
                } catch (IOException e) {
                    LOG.error("Problems writing to " + file, e);
                } finally {
                    // if stream already closed in try block successfully,
                    // stream might have nullified, in such case below
                    // call will simply returns
                    IOUtils.close(LOG, fos);
                }
            }
        }

        /**
         * Read last mark from lastMark file.
         * The last mark should first be max journal log id,
         * and then max log position in max journal log.
         */
        void readLog() {
            byte buff[] = new byte[16];
            ByteBuffer bb = ByteBuffer.wrap(buff);
            LogMark mark = new LogMark();
            for(File dir: ledgerDirsManager.getAllLedgerDirs()) {
                File file = new File(dir, "lastMark");
                try {
                    FileInputStream fis = new FileInputStream(file);
                    try {
                        int bytesRead = fis.read(buff);
                        if (bytesRead != 16) {
                            throw new IOException("Couldn't read enough bytes from lastMark."
                                                  + " Wanted " + 16 + ", got " + bytesRead);
                        }
                    } finally {
                        fis.close();
                    }
                    bb.clear();
                    mark.readLogMark(bb);
                    if (curMark.compare(mark) < 0) {
                        curMark.setLogMark(mark.getLogFileId(), mark.getLogFileOffset());
                    }
                } catch (IOException e) {
                    LOG.error("Problems reading from " + file + " (this is okay if it is the first time starting this bookie");
                }
            }
        }

        @Override
        public String toString() {
            return curMark.toString();
        }
    }

    /**
     * Filter to return list of journals for rolling
     */
    private static class JournalRollingFilter implements JournalIdFilter {

        final LastLogMark lastMark;

        JournalRollingFilter(LastLogMark lastMark) {
            this.lastMark = lastMark;
        }

        @Override
        public boolean accept(long journalId) {
            if (journalId < lastMark.getCurMark().getLogFileId()) {
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Scanner used to scan a journal
     */
    public static interface JournalScanner {
        /**
         * Process a journal entry.
         *
         * @param journalVersion
         *          Journal Version
         * @param offset
         *          File offset of the journal entry
         * @param entry
         *          Journal Entry
         * @throws IOException
         */
        public void process(int journalVersion, long offset, ByteBuffer entry) throws IOException;
    }

    /**
     * Journal Entry to Record
     */
    private static class QueueEntry extends SafeRunnable {
        final ByteBuffer entry;
        final long ledgerId;
        final long entryId;
        final WriteCallback cb;
        final Object ctx;
        final long enqueueTime;
        final OpStatsLogger addLatencyStats;

        QueueEntry(ByteBuffer entry,
                   long ledgerId, long entryId,
                   WriteCallback cb,
                   Object ctx,
                   long enqueueTime,
                   OpStatsLogger addLatencyStats) {
            this.entry = entry.duplicate();
            this.cb = cb;
            this.ctx = ctx;
            this.ledgerId = ledgerId;
            this.entryId = entryId;
            this.enqueueTime = enqueueTime;
            this.addLatencyStats = addLatencyStats;
        }

        @Override
        public void safeRun() {
            this.addLatencyStats.registerSuccessfulEvent(
                    MathUtils.elapsedMicroSec(enqueueTime));
            if (LOG.isDebugEnabled()) {
                LOG.debug("Acknowledge Ledger: {}, Entry: {}", ledgerId, entryId);
            }
            cb.writeComplete(0, ledgerId, entryId, null, ctx);
        }

        @Override
        public String toString() {
            return String.format("QueueEntry(%d, %d)", ledgerId, entryId);
        }
    }

    private class ForceWriteRequest {
        private final JournalChannel logFile;
        private final LinkedList<QueueEntry> forceWriteWaiters;
        private boolean shouldClose;
        private final boolean isMarker;
        private final long startFlushPosition;
        private final long endFlushPosition;
        private final long logId;

        private ForceWriteRequest(JournalChannel logFile,
                          long logId,
                          long startFlushPosition,
                          long endFlushPosition,
                          LinkedList<QueueEntry> forceWriteWaiters,
                          boolean shouldClose,
                          boolean isMarker) {
            this.forceWriteWaiters = forceWriteWaiters;
            this.logFile = logFile;
            this.logId = logId;
            this.startFlushPosition = startFlushPosition;
            this.endFlushPosition = endFlushPosition;
            this.shouldClose = shouldClose;
            this.isMarker = isMarker;
            journalForceWriteQueueSizeGauge.inc();
        }

        public int process(boolean shouldForceWrite) throws IOException {
            journalForceWriteQueueSizeGauge.dec();

            if (isMarker) {
                return 0;
            }

            try {
                if (shouldForceWrite) {
                    if (enableGroupForceWrites) {
                        this.logFile.forceWrite(false);
                    } else {
                        this.logFile.syncRangeOrForceWrite(this.startFlushPosition,
                            this.endFlushPosition - this.startFlushPosition);
                    }
                }
                lastLogMark.setCurLogMark(this.logId, this.endFlushPosition);

                // Notify the waiters that the force write succeeded
                callback();

                return this.forceWriteWaiters.size();
            }
            finally {
                closeFileIfNecessary();
            }
        }

        void callback() {
            for (QueueEntry e : this.forceWriteWaiters) {
                if (null != e.ctx) {
                    cbThreadPool.submitOrdered(e.ctx, e);
                } else {
                    cbThreadPool.submit(e);
                }
            }
        }

        public void closeFileIfNecessary() {
            // Close if shouldClose is set
            if (shouldClose) {
                // We should guard against exceptions so its
                // safe to call in catch blocks
                try {
                    logFile.close();
                    // Call close only once
                    shouldClose = false;
                }
                catch (IOException ioe) {
                    LOG.error("I/O exception while closing file", ioe);
                }
            }
        }
    }

    /**
     * ForceWriteThread is a background thread which makes the journal durable periodically
     *
     */
    private class ForceWriteThread extends BookieCriticalThread {
        volatile boolean running = true;
        // This holds the queue entries that should be notified after a
        // successful force write
        Thread threadToNotifyOnEx;
        // make flush interval as a parameter
        public ForceWriteThread(Thread threadToNotifyOnEx) {
            super("ForceWriteThread");
            this.threadToNotifyOnEx = threadToNotifyOnEx;
        }

        @Override
        public void run() {
            LOG.info("ForceWrite Thread started");
            boolean shouldForceWrite = true;
            int numReqInLastForceWrite = 0;
            while(running) {
                ForceWriteRequest req = null;
                try {
                    req = forceWriteRequests.take();

                    // Force write the file and then notify the write completions
                    //
                    if (!req.isMarker && shouldForceWrite) {
                        // if we are going to force write, any request that is already in the
                        // queue will benefit from this force write - post a marker prior to issuing
                        // the flush so until this marker is encountered we can skip the force write
                        if (enableGroupForceWrites) {
                            forceWriteRequests.put(new ForceWriteRequest(req.logFile, 0, 0, 0, null, false, true));
                        }

                        // If we are about to issue a write, record the number of requests in
                        // the last force write and then reset the counter so we can accumulate
                        // requests in the write we are about to issue
                        if (numReqInLastForceWrite > 0) {
                            journalForceWriteGroupingStats.registerSuccessfulEvent(numReqInLastForceWrite);
                            numReqInLastForceWrite = 0;
                        }
                    }
                    numReqInLastForceWrite += req.process(shouldForceWrite);

                    if (enableGroupForceWrites &&
                        // if its a marker we should switch back to flushing
                        !req.isMarker &&
                        // This indicates that this is the last request in a given file
                        // so subsequent requests will go to a different file so we should
                        // flush on the next request
                        !req.shouldClose) {
                        shouldForceWrite = false;
                    }
                    else {
                        shouldForceWrite = true;
                    }
                } catch (IOException ioe) {
                    LOG.error("I/O exception in ForceWrite thread", ioe);
                    running = false;
                } catch (InterruptedException e) {
                    LOG.error("ForceWrite thread interrupted", e);
                    // close is idempotent
                    if (null != req) {
                        req.closeFileIfNecessary();
                    }
                    running = false;
                }
            }
            // Regardless of what caused us to exit, we should notify the
            // the parent thread as it should either exit or be in the process
            // of exiting else we will have write requests hang
            threadToNotifyOnEx.interrupt();
        }
        // shutdown sync thread
        void shutdown() throws InterruptedException {
            running = false;
            this.interrupt();
            this.join();
        }
    }

    final static int PADDING_MASK = -0x100;

    static void writePaddingBytes(JournalChannel jc, ByteBuffer paddingBuffer, int journalAlignSize) throws IOException {
        int bytesToAlign = (int) (jc.bc.position() % journalAlignSize);
        if (0 != bytesToAlign) {
            int paddingBytes = journalAlignSize - bytesToAlign;
            if (paddingBytes < 8) {
                paddingBytes = journalAlignSize - (8 - paddingBytes);
            } else {
                paddingBytes -= 8;
            }
            paddingBuffer.clear();
            // padding mask
            paddingBuffer.putInt(PADDING_MASK);
            // padding len
            paddingBuffer.putInt(paddingBytes);
            // padding bytes
            paddingBuffer.position(8 + paddingBytes);

            paddingBuffer.flip();
            jc.preAllocIfNeeded(paddingBuffer.limit());
            // write padding bytes
            jc.bc.write(paddingBuffer);
        }
    }

    final static long MB = 1024 * 1024L;
    final static int KB = 1024;
    // max journal file size
    final long maxJournalSize;
    // pre-allocation size for the journal files
    final long journalPreAllocSize;
    // write buffer size for the journal files
    final int journalWriteBufferSize;
    // number journal files kept before marked journal
    final int maxBackupJournals;

    final File journalDirectory;
    final ServerConfiguration conf;
    final ForceWriteThread forceWriteThread;
    // should we group force writes
    private final boolean enableGroupForceWrites;
    // Time after which we will stop grouping and issue the flush
    private final long maxGroupWaitInNanos;
    // Threshold after which we flush any buffered journal entries
    private final long bufferedEntriesThreshold;
    // Threshold after which we flush any buffered journal writes
    private final long bufferedWritesThreshold;
    // should we flush if the queue is empty
    private final boolean flushWhenQueueEmpty;
    // should we hint the filesystem to remove pages from cache after force write
    private final boolean removePagesFromCache;
    // journal align size
    private final int journalAlignmentSize;
    // journal format version to write
    private final int journalFormatVersionToWrite;

    private final LastLogMark lastLogMark = new LastLogMark(0, 0);

    /**
     * The thread pool used to handle callback.
     */
    private final OrderedSafeExecutor cbThreadPool;

    // journal entry queue to commit
    final LinkedBlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<QueueEntry>();
    final LinkedBlockingQueue<ForceWriteRequest> forceWriteRequests = new LinkedBlockingQueue<ForceWriteRequest>();

    volatile boolean running = true;
    private final LedgerDirsManager ledgerDirsManager;

    // Stats
    final StatsLogger statsLogger;
    final Counter journalQueueSizeGauge; // counter implemented gauge
    final Counter journalForceWriteQueueSizeGauge; // counter implemented gauge
    final Counter journalWriteBytesCounter;
    final OpStatsLogger journalCreationLatencyStats;
    final OpStatsLogger journalAddLatencyStats;
    final Counter journalFlushMaxWaitCounter;
    final Counter journalFlushMaxOutstandingBytesCounter;
    final Counter journalFlushEmptyQueueCounter;
    final OpStatsLogger journalFlushLatencyStats;
    final OpStatsLogger journalMemAddFlushTimesStats;
    final OpStatsLogger journalMemAddLatencyStats;
    final OpStatsLogger journalForceWriteBatchEntriesStats;
    final OpStatsLogger journalForceWriteBatchBytesStats;
    final OpStatsLogger journalForceWriteGroupingStats;

    public Journal(ServerConfiguration conf,
                   LedgerDirsManager ledgerDirsManager,
                   StatsLogger statsLogger) {
        super("BookieJournal-" + conf.getBookiePort());
        this.ledgerDirsManager = ledgerDirsManager;
        this.conf = conf;
        this.journalDirectory = Bookie.getCurrentDirectory(conf.getJournalDir());
        this.maxJournalSize = conf.getMaxJournalSizeMB() * MB;
        this.journalPreAllocSize = conf.getJournalPreAllocSizeMB() * MB;
        this.journalWriteBufferSize = conf.getJournalWriteBufferSizeKB() * KB;
        this.maxBackupJournals = conf.getMaxBackupJournals();
        this.enableGroupForceWrites = conf.getJournalAdaptiveGroupWrites();
        this.forceWriteThread = new ForceWriteThread(this);
        this.maxGroupWaitInNanos = TimeUnit.MILLISECONDS.toNanos(conf.getJournalMaxGroupWaitMSec());
        this.bufferedWritesThreshold = conf.getJournalBufferedWritesThreshold();
        this.bufferedEntriesThreshold = conf.getJournalBufferedEntriesThreshold();
        this.journalAlignmentSize = conf.getJournalAlignmentSize();
        this.journalFormatVersionToWrite = conf.getJournalFormatVersionToWrite();
        this.cbThreadPool = OrderedSafeExecutor.newBuilder()
                .name("BookieJournal")
                .numThreads(conf.getNumJournalCallbackThreads())
                .statsLogger(Stats.get().getStatsLogger("journal"))
                .threadFactory(new DaemonThreadFactory())
                .build();

        // Unless there is a cap on the max wait (which requires group force writes)
        // we cannot skip flushing for queue empty
        this.flushWhenQueueEmpty = maxGroupWaitInNanos <= 0 || conf.getJournalFlushWhenQueueEmpty();

        this.removePagesFromCache = conf.getJournalRemovePagesFromCache();
        // read last log mark
        lastLogMark.readLog();
        LOG.debug("Last Log Mark : {}", lastLogMark.getCurMark());

        // Stats
        this.statsLogger = statsLogger;
        journalQueueSizeGauge = statsLogger.getCounter(JOURNAL_QUEUE_SIZE);
        journalForceWriteQueueSizeGauge = statsLogger.getCounter(JOURNAL_FORCE_WRITE_QUEUE_SIZE);
        journalWriteBytesCounter = statsLogger.getCounter(JOURNAL_WRITE_BYTES);
        journalCreationLatencyStats = statsLogger.getOpStatsLogger(JOURNAL_CREATION_LATENCY);
        journalAddLatencyStats = statsLogger.getOpStatsLogger(JOURNAL_ADD_ENTRY);
        journalFlushMaxWaitCounter = statsLogger.getCounter(JOURNAL_NUM_FLUSH_MAX_WAIT);
        journalFlushMaxOutstandingBytesCounter = statsLogger.getCounter(JOURNAL_NUM_FLUSH_MAX_OUTSTANDING_BYTES);
        journalFlushEmptyQueueCounter = statsLogger.getCounter(JOURNAL_NUM_FLUSH_EMPTY_QUEUE);
        journalFlushLatencyStats = statsLogger.getOpStatsLogger(JOURNAL_FLUSH_LATENCY);
        journalMemAddFlushTimesStats = statsLogger.getOpStatsLogger(JOURNAL_FLUSH_IN_MEM_ADD);
        journalMemAddLatencyStats = statsLogger.getOpStatsLogger(JOURNAL_MEM_ADD_ENTRY);
        journalForceWriteBatchEntriesStats = statsLogger.getOpStatsLogger(JOURNAL_FORCE_WRITE_BATCH_ENTRIES);
        journalForceWriteBatchBytesStats = statsLogger.getOpStatsLogger(JOURNAL_FORCE_WRITE_BATCH_BYTES);
        journalForceWriteGroupingStats = statsLogger.getOpStatsLogger(JOURNAL_FORCE_WRITE_GROUPING_COUNT);
    }

    LastLogMark getLastLogMark() {
        return lastLogMark;
    }

    /**
     * Application tried to schedule a checkpoint. After all the txns added
     * before checkpoint are persisted, a <i>checkpoint</i> will be returned
     * to application. Application could use <i>checkpoint</i> to do its logic.
     */
    @Override
    public Checkpoint newCheckpoint() {
        return new LogMarkCheckpoint(lastLogMark.markLog());
    }

    @Override
    public void checkpointComplete(Checkpoint checkpoint, boolean compact) throws IOException {
        if (!(checkpoint instanceof LogMarkCheckpoint)) {
            return; // we didn't create this checkpoint, so dont do anything with it
        }
        LogMarkCheckpoint lmcheckpoint = (LogMarkCheckpoint)checkpoint;
        LastLogMark mark = lmcheckpoint.mark;

        mark.rollLog(mark);
        if (compact) {
            // list the journals that have been marked
            List<Long> logs = listJournalIds(journalDirectory, new JournalRollingFilter(mark));
            // keep MAX_BACKUP_JOURNALS journal files before marked journal
            if (logs.size() >= maxBackupJournals) {
                int maxIdx = logs.size() - maxBackupJournals;
                for (int i=0; i<maxIdx; i++) {
                    long id = logs.get(i);
                    // make sure the journal id is smaller than marked journal id
                    if (id < mark.getCurMark().getLogFileId()) {
                        File journalFile = new File(journalDirectory, Long.toHexString(id) + ".txn");
                        if (!journalFile.delete()) {
                            LOG.warn("Could not delete old journal file {}", journalFile);
                        }
                        LOG.info("garbage collected journal " + journalFile.getName());
                    }
                }
            }
        }
    }

    /**
     * Scan the journal
     *
     * @param journalId
     *          Journal Log Id
     * @param journalPos
     *          Offset to start scanning
     * @param scanner
     *          Scanner to handle entries
     * @throws IOException
     */
    public void scanJournal(long journalId, long journalPos, JournalScanner scanner)
        throws IOException {
        JournalChannel recLog;
        if (journalPos <= 0) {
            recLog = new JournalChannel(journalDirectory, journalId,
                    journalPreAllocSize, journalWriteBufferSize, statsLogger);
        } else {
            recLog = new JournalChannel(journalDirectory, journalId,
                    journalPreAllocSize, journalWriteBufferSize, journalPos, statsLogger);
        }
        int journalVersion = recLog.getFormatVersion();
        try {
            ByteBuffer lenBuff = ByteBuffer.allocate(4);
            ByteBuffer recBuff = ByteBuffer.allocate(64*1024);
            while(true) {
                // entry start offset
                long offset = recLog.fc.position();
                // start reading entry
                lenBuff.clear();
                fullRead(recLog, lenBuff);
                if (lenBuff.remaining() != 0) {
                    break;
                }
                lenBuff.flip();
                int len = lenBuff.getInt();
                if (len == 0) {
                    break;
                }
                boolean isPaddingRecord = false;
                if (len == PADDING_MASK) {
                    if (journalVersion >= JournalChannel.V5) {
                        // skip padding bytes
                        lenBuff.clear();
                        fullRead(recLog, lenBuff);
                        if (lenBuff.remaining() != 0) {
                            break;
                        }
                        lenBuff.flip();
                        len = lenBuff.getInt();
                        if (len == 0) {
                            continue;
                        }
                        isPaddingRecord = true;
                    } else {
                        throw new IOException("Invalid record found with negative length : " + len);
                    }
                }
                recBuff.clear();
                if (recBuff.remaining() < len) {
                    recBuff = ByteBuffer.allocate(len);
                }
                recBuff.limit(len);
                if (fullRead(recLog, recBuff) != len) {
                    // This seems scary, but it just means that this is where we
                    // left off writing
                    break;
                }
                recBuff.flip();
                if (!isPaddingRecord) {
                    scanner.process(journalVersion, offset, recBuff);
                }
                // update last log mark during replaying
                lastLogMark.setCurLogMark(journalId, offset);
            }
        } finally {
            recLog.close();
        }
    }

    /**
     * Replay journal files
     *
     * @param scanner
     *          Scanner to process replayed entries.
     * @throws IOException
     */
    public void replay(JournalScanner scanner) throws IOException {
        final LogMark markedLog = lastLogMark.getCurMark();
        List<Long> logs = listJournalIds(journalDirectory, new JournalIdFilter() {
            @Override
            public boolean accept(long journalId) {
                if (journalId < markedLog.getLogFileId()) {
                    return false;
                }
                return true;
            }
        });
        // last log mark may be missed due to no sync up before
        // validate filtered log ids only when we have markedLogId
        if (markedLog.getLogFileId() > 0) {
            if (logs.size() == 0 || logs.get(0) != markedLog.getLogFileId()) {
                throw new IOException("Recovery log " + markedLog.getLogFileId() + " is missing");
            }
        }
        LOG.debug("Try to relay journal logs : {}", logs);
        // TODO: When reading in the journal logs that need to be synced, we
        // should use BufferedChannels instead to minimize the amount of
        // system calls done.
        for(Long id: logs) {
            long logPosition = 0L;
            if(id == markedLog.getLogFileId()) {
                logPosition = markedLog.getLogFileOffset();
            }
            scanJournal(id, logPosition, scanner);
        }
    }

    /**
     * record an add entry operation in journal
     */
    public void logAddEntry(ByteBuffer entry, WriteCallback cb, Object ctx) {
        long ledgerId = entry.getLong();
        long entryId = entry.getLong();
        entry.rewind();
        journalQueueSizeGauge.inc();
        queue.add(new QueueEntry(entry, ledgerId, entryId, cb, ctx, MathUtils.nowInNano(), journalAddLatencyStats));
    }

    /**
     * Get the length of journal entries queue.
     *
     * @return length of journal entry queue.
     */
    public int getJournalQueueLength() {
        return queue.size();
    }

    /**
     * A thread used for persisting journal entries to journal files.
     *
     * <p>
     * Besides persisting journal entries, it also takes responsibility of
     * rolling journal files when a journal file reaches journal file size
     * limitation.
     * </p>
     * <p>
     * During journal rolling, it first closes the writing journal, generates
     * new journal file using current timestamp, and continue persistence logic.
     * Those journals will be garbage collected in SyncThread.
     * </p>
     * @see org.apache.bookkeeper.bookie.Bookie.SyncThread
     */
    @Override
    public void run() {
        LinkedList<QueueEntry> toFlush = new LinkedList<QueueEntry>();
        ByteBuffer lenBuff = ByteBuffer.allocate(4);
        ByteBuffer paddingBuff = ByteBuffer.allocate(2 * journalAlignmentSize);
        ZeroBuffer.put(paddingBuff);
        JournalChannel logFile = null;
        forceWriteThread.start();
        Stopwatch journalAllocationWatcher = Stopwatch.createUnstarted();
        Stopwatch journalCreationWatcher = Stopwatch.createUnstarted();
        Stopwatch journalFlushWatcher = Stopwatch.createUnstarted();

        long batchSize = 0;
        try {
            List<Long> journalIds = listJournalIds(journalDirectory, null);
            // Should not use MathUtils.now(), which use System.nanoTime() and could only be used to measure elapsed time.
            // http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/System.html#nanoTime%28%29
            long logId = journalIds.isEmpty() ? System.currentTimeMillis() : journalIds.get(journalIds.size() - 1);
            BufferedChannel bc = null;
            long lastFlushPosition = 0L;
            boolean groupWhenTimeout = false;

            QueueEntry qe = null;
            while (true) {
                // new journal file to write
                if (null == logFile) {
                    logId = logId + 1;

                    journalCreationWatcher.reset().start();
                    logFile = new JournalChannel(journalDirectory,
                                        logId,
                                        journalPreAllocSize,
                                        journalWriteBufferSize,
                                        journalAlignmentSize,
                                        removePagesFromCache,
                                        journalFormatVersionToWrite,
                                        statsLogger);
                    journalCreationLatencyStats.registerSuccessfulEvent(
                            journalCreationWatcher.stop().elapsed(TimeUnit.MICROSECONDS));

                    bc = logFile.getBufferedChannel();

                    lastFlushPosition = 0;
                }

                if (qe == null) {
                    if (toFlush.isEmpty()) {
                        qe = queue.take();
                    } else {
                        long pollWaitTimeNanos = maxGroupWaitInNanos - MathUtils.elapsedNanos(toFlush.getFirst().enqueueTime);
                        if (flushWhenQueueEmpty || pollWaitTimeNanos < 0) {
                            pollWaitTimeNanos = 0;
                        }
                        qe = queue.poll(pollWaitTimeNanos, TimeUnit.NANOSECONDS);
                        boolean shouldFlush = false;
                        // We should issue a forceWrite if any of the three conditions below holds good
                        // 1. If the oldest pending entry has been pending for longer than the max wait time
                        if (maxGroupWaitInNanos > 0 && !groupWhenTimeout
                                && (MathUtils.elapsedNanos(toFlush.getFirst().enqueueTime) > maxGroupWaitInNanos)) {
                            groupWhenTimeout = true;
                        } else if (maxGroupWaitInNanos > 0 && groupWhenTimeout && qe != null
                                && MathUtils.elapsedNanos(qe.enqueueTime) < maxGroupWaitInNanos) {
                            // when group timeout, it would be better to look forward, as there might be lots of entries already timeout
                            // due to a previous slow write (writing to filesystem which impacted by force write).
                            // Group those entries in the queue
                            // a) already timeout
                            // b) limit the number of entries to group
                            groupWhenTimeout = false;
                            shouldFlush = true;
                            journalFlushMaxWaitCounter.inc();
                        } else if (qe != null &&
                                ((bufferedEntriesThreshold > 0 && toFlush.size() > bufferedEntriesThreshold) ||
                                 (bc.position() > lastFlushPosition + bufferedWritesThreshold))) {
                            // 2. If we have buffered more than the buffWriteThreshold or bufferedEntriesThreshold
                            shouldFlush = true;
                            journalFlushMaxOutstandingBytesCounter.inc();
                        } else if (qe == null) {
                            // We should get here only if we flushWhenQueueEmpty is true else we would wait
                            // for timeout that would put is past the maxWait threshold
                            // 3. If the queue is empty i.e. no benefit of grouping. This happens when we have one
                            // publish at a time - common case in tests.
                            shouldFlush = true;
                            journalFlushEmptyQueueCounter.inc();
                        }

                        // toFlush is non null and not empty so should be safe to access getFirst
                        if (shouldFlush) {
                            long prevFlushPosition = lastFlushPosition;

                            journalFlushWatcher.reset().start();
                            if (journalFormatVersionToWrite > JournalChannel.V5) {
                                writePaddingBytes(logFile, paddingBuff, journalAlignmentSize);
                            }
                            bc.flush(false);
                            lastFlushPosition = bc.position();

                            // start sync the range
                            if (!enableGroupForceWrites) {
                                logFile.startSyncRange(prevFlushPosition, lastFlushPosition);
                            }
                            journalFlushLatencyStats.registerSuccessfulEvent(
                                    journalFlushWatcher.stop().elapsed(TimeUnit.MICROSECONDS));

                            // Trace the lifetime of entries through persistence
                            if (LOG.isDebugEnabled()) {
                                for (QueueEntry e : toFlush) {
                                    LOG.debug("Written and queuing for flush Ledger:" + e.ledgerId + " Entry:" + e.entryId);
                                }
                            }

                            journalForceWriteBatchEntriesStats.registerSuccessfulEvent(toFlush.size());
                            journalForceWriteBatchBytesStats.registerSuccessfulEvent(batchSize);

                            forceWriteRequests.put(new ForceWriteRequest(logFile, logId, prevFlushPosition,
                                    lastFlushPosition, toFlush, (lastFlushPosition > maxJournalSize), false));
                            toFlush = new LinkedList<QueueEntry>();
                            batchSize = 0L;
                            // check whether journal file is over file limit
                            if (bc.position() > maxJournalSize) {
                                logFile = null;
                                continue;
                            }
                        }
                    }
                }

                if (!running) {
                    LOG.info("Journal Manager is asked to shut down, quit.");
                    break;
                }

                if (qe == null) { // no more queue entry
                    continue;
                }
                journalWriteBytesCounter.add(qe.entry.remaining());
                journalQueueSizeGauge.dec();

                batchSize += (4 + qe.entry.remaining());

                lenBuff.clear();
                lenBuff.putInt(qe.entry.remaining());
                lenBuff.flip();

                // preAlloc based on size
                logFile.preAllocIfNeeded(4 + qe.entry.remaining(), journalAllocationWatcher);

                //
                // we should be doing the following, but then we run out of
                // direct byte buffers
                // logFile.write(new ByteBuffer[] { lenBuff, qe.entry });
                int flushes = 0;
                flushes += bc.write(lenBuff);
                flushes += bc.write(qe.entry);

                journalMemAddFlushTimesStats.registerSuccessfulEvent(flushes);
                journalMemAddLatencyStats.registerSuccessfulEvent(
                        MathUtils.elapsedMicroSec(qe.enqueueTime));

                toFlush.add(qe);
                qe = null;
            }
            logFile.close();
            logFile = null;
        } catch (IOException ioe) {
            LOG.error("I/O exception in Journal thread!", ioe);
        } catch (InterruptedException ie) {
            LOG.warn("Journal exits when shutting down", ie);
        } finally {
            // There could be packets queued for forceWrite on this logFile
            // That is fine as this exception is going to anyway take down the
            // the bookie. If we execute this as a part of graceful shutdown,
            // close will flush the file system cache making any previous
            // cached writes durable so this is fine as well.
            IOUtils.close(LOG, logFile);
        }
    }

    /**
     * Shuts down the journal.
     */
    public synchronized void shutdown() {
        try {
            if (!running) {
                return;
            }
            LOG.info("Shutting down Journal thread");
            forceWriteThread.shutdown();
            cbThreadPool.shutdown();
            cbThreadPool.forceShutdown(5, TimeUnit.SECONDS);
            running = false;
            this.interrupt();
            this.join();
            LOG.info("Finished Shutting down Journal thread");
        } catch (InterruptedException ie) {
            LOG.warn("Interrupted during shutting down journal : ", ie);
        }
    }

    private static int fullRead(JournalChannel fc, ByteBuffer bb) throws IOException {
        int total = 0;
        while(bb.remaining() > 0) {
            int rc = fc.read(bb);
            if (rc <= 0) {
                return total;
            }
            total += rc;
        }
        return total;
    }
}
