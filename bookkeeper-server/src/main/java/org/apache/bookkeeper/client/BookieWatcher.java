package org.apache.bookkeeper.client;

/**
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.bookkeeper.client.BKException.BKNotEnoughBookiesException;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.net.BookieSocketAddress;
import org.apache.bookkeeper.util.BookKeeperConstants;
import org.apache.bookkeeper.util.SafeRunnable;
import org.apache.bookkeeper.util.ZkUtils;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.bookkeeper.util.BookKeeperConstants.*;

/**
 * This class is responsible for maintaining a consistent view of what bookies
 * are available by reading Zookeeper (and setting watches on the bookie nodes).
 * When a bookie fails, the other parts of the code turn to this class to find a
 * replacement
 *
 */
class BookieWatcher implements Watcher, ChildrenCallback {
    static final Logger logger = LoggerFactory.getLogger(BookieWatcher.class);

    public static int ZK_CONNECT_BACKOFF_SEC = 1;
    private static final Set<BookieSocketAddress> EMPTY_SET = new HashSet<BookieSocketAddress>();

    // Bookie registration path in ZK
    private final String bookieRegistrationPath;

    final BookKeeper bk;
    final ScheduledExecutorService scheduler;
    final EnsemblePlacementPolicy placementPolicy;
    final CopyOnWriteArraySet<BookiesListener> listeners =
            new CopyOnWriteArraySet<BookiesListener>();

    private final SafeRunnable reReadTask = new SafeRunnable() {
        @Override
        public void safeRun() {
            readBookies();
        }
    };
    private final ReadOnlyBookieWatcher readOnlyBookieWatcher;

    BookieWatcher(ClientConfiguration conf,
                  ScheduledExecutorService scheduler,
                  EnsemblePlacementPolicy placementPolicy,
                  BookKeeper bk)
            throws KeeperException, InterruptedException  {
        this.bk = bk;
        // ZK bookie registration path
        this.bookieRegistrationPath = conf.getZkAvailableBookiesPath();
        this.scheduler = scheduler;
        this.placementPolicy = placementPolicy;
        readOnlyBookieWatcher = new ReadOnlyBookieWatcher(conf, bk);
    }

    void registerBookiesListener(final BookiesListener listener) {
        listeners.add(listener);
        readOnlyBookieWatcher.registerBookiesListener(listener);
    }

    void unregisterBookiesListener(final BookiesListener listener) {
        listeners.remove(listener);
        readOnlyBookieWatcher.unregisterBookiesListener(listener);
    }

    /**
     * Get all the bookies registered under cookies path.
     *
     * @return the registered bookie list.
     */
    Collection<BookieSocketAddress> getRegisteredBookies() throws BKException {
        String cookiePath = bk.getConf().getZkLedgersRootPath() + "/"
            + BookKeeperConstants.COOKIE_NODE;
        try {
            List<String> children = bk.getZkHandle().getChildren(cookiePath, false);
            List<BookieSocketAddress> bookies = new ArrayList<BookieSocketAddress>(children.size());
            for (String child : children) {
                try {
                    bookies.add(new BookieSocketAddress(child));
                } catch (IOException ioe) {
                    logger.error("Error parsing bookie address {} : ", child, ioe);
                    throw new BKException.ZKException();
                }
            }
            return bookies;
        } catch (KeeperException ke) {
            logger.error("Failed to get registered bookie list : ", ke);
            throw new BKException.ZKException();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted reading registered bookie list", ie);
            throw new BKException.BKInterruptedException();
        }
    }

    Collection<BookieSocketAddress> getAvailableBookies() throws BKException {
        try {
            List<String> children = bk.getZkHandle().getChildren(this.bookieRegistrationPath, false);
            children.remove(BookKeeperConstants.READONLY);
            return convertToBookieAddresses(children);
        } catch (KeeperException ke) {
            if (ZkUtils.isRecoverableException(ke)) {
                logger.info("Encountered recoverable zookeeper exception on getting bookie list : code = {}", ke.code());
            } else {
                logger.error("Encountered zookeeper exception on getting bookie list : code = {}, message = {}",
                        ke.code(), ke.getMessage());
            }
            throw new BKException.ZKException();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted reading bookie list", ie);
            throw new BKException.BKInterruptedException();
        }
    }

    Collection<BookieSocketAddress> getReadOnlyBookies() throws BKException {
        try {
            readOnlyBookieWatcher.readROBookiesBlocking();
        } catch (KeeperException ke) {
            if (ZkUtils.isRecoverableException(ke)) {
                logger.info("Encountered recoverable zookeeper exception on getting readonly bookie list : code = {}",
                        ke.code());
            } else {
                logger.error("Encountered zookeeper exception on getting readonly bookie list : code = {}, message = {}",
                        ke.code(), ke.getMessage());
            }
            throw new BKException.ZKException();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted reading bookie list : ", ie);
            throw new BKException.BKInterruptedException();
        }
        return new HashSet<BookieSocketAddress>(readOnlyBookieWatcher.getReadOnlyBookies());
    }

    private void readBookies() {
        readBookies(this);
    }

    private void readBookies(ChildrenCallback callback) {
        bk.getZkHandle().getChildren(this.bookieRegistrationPath, this, callback, null);
    }

    @Override
    public void process(WatchedEvent event) {
        readBookies();
    }

    @Override
    public void processResult(int rc, String path, Object ctx, List<String> children) {

        if (rc != KeeperException.Code.OK.intValue()) {
            if (bk.closed) {
                return;
            }
            // try the read after a second again
            try {
                scheduler.schedule(reReadTask, ZK_CONNECT_BACKOFF_SEC, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ree) {
                if (!bk.closed) {
                    logger.warn("Failed to schedule reading bookies task (it might be because bookkeeper" +
                            " client is closing): ", ree);
                }
            }
            return;
        }

        // Just exclude the 'readonly' znode to exclude r-o bookies from
        // available nodes list.
        children.remove(READONLY);

        HashSet<BookieSocketAddress> newBookieAddrs = convertToBookieAddresses(children);

        synchronized (this) {
            Set<BookieSocketAddress> readonlyBookies = readOnlyBookieWatcher.getReadOnlyBookies();
            placementPolicy.onClusterChanged(newBookieAddrs, readonlyBookies);
        }

        for (BookiesListener listener : listeners) {
            listener.availableBookiesChanged(newBookieAddrs);
        }

        // we don't need to close clients here, because:
        // a. the dead bookies will be removed from topology, which will not be used in new ensemble.
        // b. the read sequence will be reordered based on znode availability, so most of the reads
        //    will not be sent to them.
        // c. the close here is just to disconnect the channel, which doesn't remove the channel from
        //    from pcbc map. we don't really need to disconnect the channel here, since if a bookie is
        //    really down, PCBC will disconnect itself based on netty callback. if we try to disconnect
        //    here, it actually introduces side-effects on case d.
        // d. closing the client here will affect latency if the bookie is alive but just being flaky
        //    on its znode registration due zookeeper session expire.
        // e. if we want to permanently remove a bookkeeper client, we should watch on the cookies' list.
        // if (bk.getBookieClient() != null) {
        //     bk.getBookieClient().closeClients(deadBookies);
        // }
    }

    private static HashSet<BookieSocketAddress> convertToBookieAddresses(List<String> children) {
        // Read the bookie addresses into a set for efficient lookup
        HashSet<BookieSocketAddress> newBookieAddrs = new HashSet<BookieSocketAddress>();
        for (String bookieAddrString : children) {
            BookieSocketAddress bookieAddr;
            try {
                bookieAddr = new BookieSocketAddress(bookieAddrString);
            } catch (IOException e) {
                logger.info("Could not parse bookie address: {}, ignoring this bookie : ",
                        bookieAddrString, e);
                continue;
            }
            newBookieAddrs.add(bookieAddr);
        }
        return newBookieAddrs;
    }

    /**
     * Blocks until bookies are read from zookeeper, used in the {@link BookKeeper} constructor.
     * @throws InterruptedException
     * @throws KeeperException
     */
    public void readBookiesBlocking() throws InterruptedException, KeeperException {
        // Read readonly bookies first
        readOnlyBookieWatcher.readROBookiesBlocking();

        final LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<Integer>();
        readBookies(new ChildrenCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx, List<String> children) {
                try {
                    BookieWatcher.this.processResult(rc, path, ctx, children);
                    queue.put(rc);
                } catch (InterruptedException e) {
                    logger.error("Interruped when trying to read bookies in a blocking fashion : ", e);
                    throw new RuntimeException(e);
                }
            }
        });
        int rc = queue.take();

        if (rc != KeeperException.Code.OK.intValue()) {
            throw KeeperException.create(Code.get(rc));
        }
    }

    /**
     * Wrapper over the {@link org.apache.bookkeeper.client.EnsemblePlacementPolicy#newEnsemble(int, int, int,
     * java.util.Set)} * method when there is no exclusion list (or exisiting bookies)
     *
     * @param ensembleSize
     *          Ensemble Size
     * @param writeQuorumSize
     *          Write Quorum Size
     * @return list of bookies for new ensemble.
     * @throws BKNotEnoughBookiesException
     */
    public ArrayList<BookieSocketAddress> newEnsemble(int ensembleSize, int writeQuorumSize, int ackQuorumSize)
            throws BKNotEnoughBookiesException {
        return placementPolicy.newEnsemble(ensembleSize, writeQuorumSize, ackQuorumSize, EMPTY_SET);
    }

    /**
     * Wrapper over the {@link org.apache.bookkeeper.client.EnsemblePlacementPolicy#replaceBookie(int, int, int,
     * java.util.Collection, org.apache.bookkeeper.net.BookieSocketAddress, java.util.Set)} method when you just need 1 extra bookie
     * @param existingBookies
     * @return replaced bookie
     * @throws BKNotEnoughBookiesException
     */
    public BookieSocketAddress replaceBookie(int ensembleSize, int writeQuorumSize, int ackQuorumSize,
                                             List<BookieSocketAddress> existingBookies,
                                             int bookieIdx, Set<BookieSocketAddress> excludeBookies)
            throws BKNotEnoughBookiesException {
        BookieSocketAddress addr = existingBookies.get(bookieIdx);
        return placementPolicy.replaceBookie(ensembleSize, writeQuorumSize, ackQuorumSize, existingBookies, addr, excludeBookies);
    }

    /**
     * Watcher implementation to watch the readonly bookies under
     * &lt;available&gt;/readonly
     */
    private static class ReadOnlyBookieWatcher implements Watcher, ChildrenCallback {

        private final static Logger LOG = LoggerFactory.getLogger(ReadOnlyBookieWatcher.class);
        private HashSet<BookieSocketAddress> readOnlyBookies = new HashSet<BookieSocketAddress>();
        private final BookKeeper bk;
        private final String readOnlyBookieRegPath;

        final CopyOnWriteArraySet<BookiesListener> listeners =
                new CopyOnWriteArraySet<BookiesListener>();

        public ReadOnlyBookieWatcher(ClientConfiguration conf, BookKeeper bk) throws KeeperException,
                InterruptedException {
            this.bk = bk;
            readOnlyBookieRegPath = conf.getZkAvailableBookiesPath() + "/" + READONLY;
            if (null == bk.getZkHandle().exists(readOnlyBookieRegPath, false)) {
                try {
                    bk.getZkHandle().create(readOnlyBookieRegPath, new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
                } catch (NodeExistsException e) {
                    // this node is just now created by someone.
                }
            }
        }

        void registerBookiesListener(final BookiesListener listener) {
            listeners.add(listener);
        }

        void unregisterBookiesListener(final BookiesListener listener) {
            listeners.remove(listener);
        }

        @Override
        public void process(WatchedEvent event) {
            readROBookies();
        }

        // read the readonly bookies in blocking fashion. Used only for first
        // time.
        void readROBookiesBlocking() throws InterruptedException, KeeperException {

            final LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<Integer>();
            readROBookies(new ChildrenCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx, List<String> children) {
                    try {
                        ReadOnlyBookieWatcher.this.processResult(rc, path, ctx, children);
                        queue.put(rc);
                    } catch (InterruptedException e) {
                        logger.error("Interruped when trying to read readonly bookies in a blocking fashion");
                        throw new RuntimeException(e);
                    }
                }
            });
            int rc = queue.take();

            if (rc != KeeperException.Code.OK.intValue()) {
                throw KeeperException.create(Code.get(rc));
            }
        }

        // Read children and register watcher for readonly bookies path
        void readROBookies(ChildrenCallback callback) {
            bk.getZkHandle().getChildren(this.readOnlyBookieRegPath, this, callback, null);
        }

        void readROBookies() {
            readROBookies(this);
        }

        @Override
        public void processResult(int rc, String path, Object ctx, List<String> children) {
            if (rc != Code.OK.intValue()) {
                if (ZkUtils.isRecoverableException(rc)) {
                    logger.info("Encountered recoverable zookeeper exception on reading readonly bookie list : code = {}", Code.get(rc));
                } else {
                    logger.error("Encountered zookeeper exception on reading readonly bookie list : code = {}",
                            Code.get(rc));
                }
                return;
            }

            HashSet<BookieSocketAddress> newReadOnlyBookies = convertToBookieAddresses(children);
            readOnlyBookies = newReadOnlyBookies;

            for (BookiesListener listener : listeners) {
                listener.readOnlyBookiesChanged(newReadOnlyBookies);
            }
        }

        // returns the readonly bookies
        public HashSet<BookieSocketAddress> getReadOnlyBookies() {
            return readOnlyBookies;
        }
    }
}
