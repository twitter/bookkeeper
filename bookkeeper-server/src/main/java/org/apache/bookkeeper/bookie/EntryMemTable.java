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
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.bookkeeper.bookie;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.rmi.UnexpectedException;
import java.util.Iterator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.bookkeeper.conf.ServerConfiguration;

/**
 * Entry skip list
 */
class EntrySkipList extends ConcurrentSkipListMap<EntryKey, EntryKeyValue> {
    EntrySkipList() {
        super(EntryKey.COMPARATOR);
    }

    @Override
    public EntryKeyValue put(EntryKey k, EntryKeyValue v) {
        return putIfAbsent(k, v);
    }

    @Override
    public EntryKeyValue putIfAbsent(EntryKey k, EntryKeyValue v) {
        assert k.equals(v);
        return super.putIfAbsent(v, v);
    }
}

/**
 * The EntryMemTable holds in-memory representation to the entries not-yet flushed.
 * When asked to flush, current EntrySkipList is moved to snapshot and is cleared.
 * We continue to serve edits out of new EntrySkipList and backing snapshot until
 * flusher reports in that the flush succeeded. At that point we let the snapshot go.
 */
public class EntryMemTable implements CacheCallback {
    private static final Log LOG = LogFactory.getLog(EntryMemTable.class);

    volatile EntrySkipList kvmap;

    // Snapshot of EntryMemTable.  Made for flusher.
    volatile EntrySkipList snapshot;

    final ServerConfiguration conf;

    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    final KeyComparator comparator = EntryKey.COMPARATOR;

    // Used to track own data size
    final AtomicLong size;

    final long skipListSizeLimit;

    SkipListArena allocator;

    private EntrySkipList newSkipList() {
        return new EntrySkipList();
    }

    /**
    * Constructor.
    * @param conf Server configuration
    */
    public EntryMemTable(final ServerConfiguration conf) {
        this.kvmap = newSkipList();
        this.snapshot = newSkipList();
        this.conf = conf;
        this.size = new AtomicLong(0);
        this.allocator = new SkipListArena(conf);
        // skip list size limit
        skipListSizeLimit = conf.getSkipListSizeLimit();
    }

    void dump() {
        for (EntryKey key: this.kvmap.keySet()) {
            LOG.info(key);
        }
        for (EntryKey key: this.snapshot.keySet()) {
            LOG.info(key);
        }
    }

    /**
    * Creates a snapshot of the current EntryMemTable.
    * Snapshot must be cleared by call to {@link #clearSnapshot(EntrySkipList)}
    */
    void snapshot(final CacheCallback cb) throws IOException {
        this.lock.writeLock().lock();
        try {
            // If snapshot currently has entries, log a warning.
            if (!this.snapshot.isEmpty()) {
                LOG.warn("SkipList snapshot until previous is cleared");
            } else {
                if (!this.kvmap.isEmpty()) {
                    this.snapshot = this.kvmap;
                    this.kvmap = newSkipList();
                    // Reset heap to not include any keys
                    this.size.set(0);
                    // Reset allocator so we get a fresh buffer for the new EntryMemTable
                    this.allocator = new SkipListArena(conf);
                    cb.onSizeLimitReached();
                }
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Process notification that cache size limit reached
     */
    @Override
    public void onSizeLimitReached() throws IOException {
        // No-op
    }

    /**
     * Flush snapshot and clear it
     * @param force all data to be flushed (incl' current)
     */
    void flush(final SkipListFlusher flusher, boolean force) throws IOException {
        EntrySkipList keyValues = this.snapshot;
        for (EntryKey key : keyValues.keySet()) {
            EntryKeyValue kv = (EntryKeyValue)key;
            flusher.process(kv.getLedgerId(), kv.getEntryId(), kv.getAsByteBuffer());
        }
        clearSnapshot(keyValues);

        if (force) {
            snapshot(this);
            flush(flusher, false);
        }
    }

    /**
    * The passed snapshot was successfully persisted; it can be let go.
    * @param keyValues The snapshot to clean out.
    * @see {@link #snapshot(CacheCallback)}
    */
    void clearSnapshot(final EntrySkipList keyValues) {
        this.lock.writeLock().lock();
        try {
            assert this.snapshot == keyValues;

            // If the old snapshot is not-empty,
            // create a new snapshot and let the old one go.
            if (!keyValues.isEmpty()) {
                this.snapshot = newSkipList();
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
    * Write an update
    * @param entry
    * @return approximate size of the passed key and value.
    */
    long add(long ledgerId, long entryId, final ByteBuffer entry) {
        this.lock.readLock().lock();
        try {
            EntryKeyValue toAdd = cloneWithAllocator(ledgerId, entryId, entry);
            return internalAdd(toAdd);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    /**
    * Internal version of add() that doesn't clone KVs with the
    * allocator, and doesn't take the lock.
    *
    * Callers should ensure they already have the read lock taken
    */
    private long internalAdd(final EntryKeyValue toAdd) {
        long sizeChange = 0;
        if (kvmap.putIfAbsent(toAdd, toAdd) == null) {
            sizeChange = toAdd.getLength();
        }
        this.size.addAndGet(sizeChange);
        return sizeChange;
    }

    private EntryKeyValue newEntry(long ledgerId, long entryId, final ByteBuffer entry) {
        byte[] buf;
        int offset = 0;
        int length = entry.remaining();

        if (entry.hasArray()) {
            buf = entry.array();
            offset = entry.arrayOffset();
        }
        else {
            buf = new byte[length];
            entry.get(buf);
        }
        return new EntryKeyValue(ledgerId, entryId, buf, offset, length);
    }

    private EntryKeyValue cloneWithAllocator(long ledgerId, long entryId, final ByteBuffer entry) {
        int len = entry.remaining();
        SkipListArena.MemorySlice alloc = allocator.allocateBytes(len);
        if (alloc == null) {
            // The allocation was too large, allocator decided
            // not to do anything with it.
            return newEntry(ledgerId, entryId, entry);
        }

        assert alloc != null && alloc.getData() != null;
        entry.get(alloc.getData(), alloc.getOffset(), len);
        return new EntryKeyValue(ledgerId, entryId, alloc.getData(), alloc.getOffset(), len);
    }

    /**
     * Find the entry with given key
     * @param ledgerId
     * @param entryId
     * @return the entry kv or null if none found.
     */
    public EntryKeyValue getEntry(long ledgerId, long entryId) throws IOException {
        EntryKey key = new EntryKey(ledgerId, entryId);
        EntryKeyValue value = null;
        this.lock.readLock().lock();
        try {
            value = this.kvmap.get(key);
            if (value == null) {
                value = this.snapshot.get(key);
            }
        } finally {
            this.lock.readLock().unlock();
        }

        return value;
    }

    /**
     * Check if the entire heap usage for this EntryMemTable exceeds limit
     */
    boolean isSizeLimitReached() {
        return size.get() >= skipListSizeLimit;
    }
}
