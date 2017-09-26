/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3;

import java.lang.ref.Reference;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.RouteDatabase;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Socket连接池，对连接缓存进行回收与管理
 * <p>
 * Manages reuse of HTTP and HTTP/2 connections for reduced network latency. HTTP requests that
 * share the same {@link Address} may share a {@link Connection}. This class implements the policy
 * of which connections to keep open for future use.
 */
public final class ConnectionPool {
    /**
     * Background threads are used to cleanup expired connections. There will be at most a single
     * thread running per connection pool. The thread pool executor permits the pool itself to be
     * garbage collected.
     */
    private static final Executor executor = new ThreadPoolExecutor(0 /* corePoolSize */,
            Integer.MAX_VALUE /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));

    /**
     * The maximum number of idle connections for each address.
     */
    private final int maxIdleConnections;
    private final long keepAliveDurationNs;

    /**
     * Socket清理的Runnable，每当put操作时，就会被主动调用
     * 注意put操作是在网络线程，而清理线程在线程池{@link ConnectionPool#executor}中调用
     */
    private final Runnable cleanupRunnable = new Runnable() {
        @Override
        public void run() {
            while (true) {
                // 执行清理并返回下次需要清理的间隔时间
                long waitNanos = cleanup(System.nanoTime());
                if (waitNanos == -1) return;//没有connection的情况，跳出循环
                if (waitNanos > 0) {
                    long waitMillis = waitNanos / 1000000L;
                    waitNanos -= (waitMillis * 1000000L);
                    synchronized (ConnectionPool.this) {
                        try {
                            //在timeout内释放锁与时间片
                            ConnectionPool.this.wait(waitMillis, (int) waitNanos);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
    };

    private final Deque<RealConnection> connections = new ArrayDeque<>();
    /**
     * 记录连接失败的Route的黑名单，当连接失败的时候就会把失败的线路加进去
     */
    final RouteDatabase routeDatabase = new RouteDatabase();
    boolean cleanupRunning;

    /**
     * Create a new connection pool with tuning parameters appropriate for a single-user application.
     * The tuning parameters in this pool are subject to change in future OkHttp releases. Currently
     * this pool holds up to 5 idle connections which will be evicted after 5 minutes of inactivity.
     */
    public ConnectionPool() {
        this(5, 5, TimeUnit.MINUTES);
    }

    public ConnectionPool(int maxIdleConnections, long keepAliveDuration, TimeUnit timeUnit) {
        this.maxIdleConnections = maxIdleConnections;
        this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);

        // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
        if (keepAliveDuration <= 0) {
            throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
        }
    }

    /**
     * Returns the number of idle connections in the pool.
     */
    public synchronized int idleConnectionCount() {
        int total = 0;
        for (RealConnection connection : connections) {
            if (connection.allocations.isEmpty()) total++;
        }
        return total;
    }

    /**
     * Returns total number of connections in the pool. Note that prior to OkHttp 2.7 this included
     * only idle connections and HTTP/2 connections. Since OkHttp 2.7 this includes all connections,
     * both active and inactive. Use {@link #idleConnectionCount()} to count connections not currently
     * in use.
     */
    public synchronized int connectionCount() {
        return connections.size();
    }

    /**
     * Returns a recycled connection to {@code address}, or null if no such connection exists. The
     * route is null if the address has not yet been routed.
     */
    @Nullable
    RealConnection get(Address address, StreamAllocation streamAllocation, Route route) {
        assert (Thread.holdsLock(this));
        for (RealConnection connection : connections) {
            //如果这个connection可用
            if (connection.isEligible(address, route)) {
                // 在connection的allocations（引用记录列表List<Reference<StreamAllocation>>）
                // 中加入streamAllocation
                streamAllocation.acquire(connection, true);
                return connection;
            }
        }
        return null;
    }

    /**
     * Replaces the connection held by {@code streamAllocation} with a shared connection if possible.
     * This recovers when multiple multiplexed connections are created concurrently.
     */
    @Nullable
    Socket deduplicate(Address address, StreamAllocation streamAllocation) {
        assert (Thread.holdsLock(this));
        for (RealConnection connection : connections) {
            if (connection.isEligible(address, null)
                    && connection.isMultiplexed()
                    && connection != streamAllocation.connection()) {
                return streamAllocation.releaseAndAcquire(connection);
            }
        }
        return null;
    }

    /**
     * 用户socket连接成功，向连接池中put新的socket
     * 会调用回收函数，线程池就会执行cleanupRunnable
     *
     * @param connection
     */
    void put(RealConnection connection) {
        assert (Thread.holdsLock(this));
        if (!cleanupRunning) {
            cleanupRunning = true;
            executor.execute(cleanupRunnable);
        }
        connections.add(connection);
    }

    /**
     * Notify this pool that {@code connection} has become idle. Returns true if the connection has
     * been removed from the pool and should be closed.
     * <p>
     * connection变成空闲。在{@link StreamAllocation#deallocate(boolean, boolean, boolean)}中调用。
     * 返回true表示connection已经从连接池中移除，需要进行关闭。
     * 只是空闲，还不需要立即关闭的，唤醒清理线程池线程，返回false
     */
    boolean connectionBecameIdle(RealConnection connection) {
        assert (Thread.holdsLock(this));
        if (connection.noNewStreams || maxIdleConnections == 0) {
            connections.remove(connection);
            return true;
        } else {
            notifyAll(); // Awake the cleanup thread: we may have exceeded the idle connection limit.
            return false;
        }
    }

    /**
     * Close and remove all idle connections in the pool.
     * 关闭和清理所有空闲的连接
     */
    public void evictAll() {
        List<RealConnection> evictedConnections = new ArrayList<>();
        synchronized (this) {
            for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
                RealConnection connection = i.next();
                if (connection.allocations.isEmpty()) {
                    connection.noNewStreams = true;
                    evictedConnections.add(connection);
                    i.remove();
                }
            }
        }

        for (RealConnection connection : evictedConnections) {
            closeQuietly(connection.socket());
        }
    }

    /**
     * Performs maintenance on this pool, evicting the connection that has been idle the longest if
     * either it has exceeded the keep alive limit or the idle connections limit.
     * <p>
     * <p>Returns the duration in nanos to sleep until the next scheduled call to this method. Returns
     * -1 if no further cleanups are required.
     * <p>
     * 使用了类似于GC的标记-清除算法，也就是首先标记出最不活跃的连接(我们可以叫做泄漏连接，或者空闲连接)，
     * 接着进行清除
     */
    long cleanup(long now) {
        int inUseConnectionCount = 0;
        int idleConnectionCount = 0;
        // 记录空闲时间最长的connection
        RealConnection longestIdleConnection = null;
        // 记录空闲时间最长的connection空闲的时间
        long longestIdleDurationNs = Long.MIN_VALUE;

        // Find either a connection to evict, or the time that the next eviction is due.
        synchronized (this) {
            // 遍历Deque中所有的RealConnection
            for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
                RealConnection connection = i.next();

                // If the connection is in use, keep searching.
                // 查询此连接内部StreamAllocation的引用数量，大于0就是in use，否则为空闲
                if (pruneAndGetAllocationCount(connection, now) > 0) {
                    inUseConnectionCount++;
                    continue;
                }

                idleConnectionCount++;

                // If the connection is ready to be evicted, we're done.
                // 找出空闲时间最长的connection
                long idleDurationNs = now - connection.idleAtNanos;
                if (idleDurationNs > longestIdleDurationNs) {
                    longestIdleDurationNs = idleDurationNs;
                    longestIdleConnection = connection;
                }
            }

            // 如果最长空闲时间大于keepAliveDurationNs（默认是5min）
            // 或者空闲connection数大于maxIdleConnections（默认为5）
            // 从connections中移除，并在下面关闭，返回0，即需要立即再次清理
            // 其他情况返回下次执行清理的时间，没有connection返回-1
            if (longestIdleDurationNs >= this.keepAliveDurationNs
                    || idleConnectionCount > this.maxIdleConnections) {
                // We've found a connection to evict. Remove it from the list, then close it below (outside
                // of the synchronized block).
                connections.remove(longestIdleConnection);
            } else if (idleConnectionCount > 0) {
                // A connection will be ready to evict soon.
                return keepAliveDurationNs - longestIdleDurationNs;
            } else if (inUseConnectionCount > 0) {
                // All connections are in use. It'll be at least the keep alive duration 'til we run again.
                return keepAliveDurationNs;
            } else {
                // No connections, idle or in use.
                cleanupRunning = false;
                return -1;
            }
        }

        closeQuietly(longestIdleConnection.socket());

        // Cleanup again immediately.
        return 0;
    }

    /**
     * Prunes any leaked allocations and then returns the number of remaining live allocations on
     * {@code connection}. Allocations are leaked if the connection is tracking them but the
     * application code has abandoned them. Leak detection is imprecise and relies on garbage
     * collection.
     * <p>
     * 清理connection中的allocation列表中已经为空（泄漏）的弱引用，返回非空的引用数
     */
    private int pruneAndGetAllocationCount(RealConnection connection, long now) {
        List<Reference<StreamAllocation>> references = connection.allocations;
        for (int i = 0; i < references.size(); ) {
            Reference<StreamAllocation> reference = references.get(i);

            //如果正在被使用，计数，跳过，接着循环
            if (reference.get() != null) {
                i++;
                continue;
            }

            // We've discovered a leaked allocation. This is an application bug.
            StreamAllocation.StreamAllocationReference streamAllocRef =
                    (StreamAllocation.StreamAllocationReference) reference;
            String message = "A connection to " + connection.route().address().url()
                    + " was leaked. Did you forget to close a response body?";
            Platform.get().logCloseableLeak(message, streamAllocRef.callStackTrace);

            //否则移除引用，且不再给这个connection分配新的Stream
            references.remove(i);
            connection.noNewStreams = true;

            // If this was the last allocation, the connection is eligible for immediate eviction.
            // 如果所有分配的流都泄漏然后被remove了
            // （这不是正常的空闲）标记已经空闲时间为keepAliveDurationNs（表示需要立即清理）
            if (references.isEmpty()) {
                connection.idleAtNanos = now - keepAliveDurationNs;
                return 0;
            }
        }

        // 返回清理后的引用数
        return references.size();
    }
}
