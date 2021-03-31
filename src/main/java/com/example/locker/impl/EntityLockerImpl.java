package com.example.locker.impl;

import com.example.locker.EntityId;
import com.example.locker.EntityLocker;
import com.example.locker.TimeoutException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

@Slf4j
public class EntityLockerImpl implements EntityLocker {

    private static final String KEY_DELIMITER = "###";
    private static final int DISABLED_TIMEOUT_FLAG = -1;
    private static final int WAITING_ENTITY_LOCK_TIMEOUT_MS = 50;

    private final ConcurrentMap<String, Lock> entitiesLockHolder = new ConcurrentHashMap<>();
    private final ReadWriteLock globalLock = new ReentrantReadWriteLock();
    private final Lock sharedLock = globalLock.readLock();
    private final Lock exclusiveLock = globalLock.writeLock();
    private final Lock mapManagerLock = new ReentrantLock();

    public <T> T globalLockExecute(Supplier<T> supplier) {
        return runWithLock(exclusiveLock, supplier);
    }

    public void globalLockExecute(Runnable runnable) {
        runWithLock(exclusiveLock, runnableToSupplier(runnable));
    }

    public <T> T globalLockExecute(long timeout, TimeUnit unit, Supplier<T> supplier) {
        return runWithLock(timeout, unit, exclusiveLock, supplier);
    }

    public void globalLockExecute(long timeout, TimeUnit unit, Runnable runnable) {
        runWithLock(timeout, unit, exclusiveLock, runnableToSupplier(runnable));
    }

    /**
     * This is a quick solution for execution with lock by entity id
     * but drawback is that this method doesn't delete locks from entitiesLockHolder
     */
    public void lockExecuteExampleWithoutCleaningMapManagerLock(EntityId entityId, Runnable runnable) {
        runWithLock(sharedLock, runnableToSupplier(() -> {
            String key = getKey(entityId);
            runWithLock(entitiesLockHolder.computeIfAbsent(key, k -> new ReentrantLock()), runnableToSupplier(runnable));
        }));
    }

    /**
     * For reducing boilerplate code - timeout can be passed as DISABLED_TIMEOUT_FLAG, and method will work
     * without timeout.
     */
    @Override
    public <T> T lockExecute(long timeout, TimeUnit unit, EntityId entityId, Supplier<T> supplier) {
        return runWithLock(timeout, unit, sharedLock, () -> {
            String key = getKey(entityId);
            Lock lock = null;
            try {
                long deadline = System.nanoTime() + unit.toNanos(timeout);
                while (true) {
                    try {
                        // mapManagerLock is used for synchronous adding lock to entitiesLockHolder and locking
                        // Without this lock others thread can create different lock because of removing lock in finally block.
                        mapManagerLock.lock();
                        lock = entitiesLockHolder.computeIfAbsent(key, k -> new ReentrantLock());
                        // Try to get lock immediately
                        if (lock.tryLock()) {
                            // If lock was caught then add it again to map, because it may be removed in finally block
                            // in another thread
                            entitiesLockHolder.putIfAbsent(key, lock);
                            break;
                        }
                    } finally {
                        mapManagerLock.unlock();
                    }
                    // If timeout parameter was passed check the time of waiting entity lock is not passed
                    if (timeout != DISABLED_TIMEOUT_FLAG && System.nanoTime() > deadline) {
                        throw new TimeoutException(String.format("Can't get lock for entity %s in given time %s %s", entityId.getId(), timeout, unit));
                    }
                    // This sleep will allow another thread to lock mapManagerLock
                    sleep(WAITING_ENTITY_LOCK_TIMEOUT_MS);
                }
                return supplier.get();
            } finally {
                if (lock != null) {
                    entitiesLockHolder.remove(key);
                    lock.unlock();
                }
            }
        });
    }

    @Override
    public void lockExecute(long timeout, TimeUnit unit, EntityId entityId, Runnable runnable) {
        lockExecute(timeout, unit, entityId, runnableToSupplier(runnable));
    }

    @Override
    public void lockExecute(EntityId entityId, Runnable runnable) {
        lockExecute(DISABLED_TIMEOUT_FLAG, TimeUnit.SECONDS, entityId, runnableToSupplier(runnable));
    }

    @Override
    public <T> T lockExecute(EntityId entityId, Supplier<T> supplier) {
        return lockExecute(DISABLED_TIMEOUT_FLAG, TimeUnit.SECONDS, entityId, supplier);
    }

    private Supplier<Void> runnableToSupplier(Runnable runnable) {
        return () -> {
            runnable.run();
            return null;
        };
    }

    @SneakyThrows
    private <T> T runWithLock(Lock lock, Supplier<T> supplier) {
        return runWithLock(DISABLED_TIMEOUT_FLAG, TimeUnit.SECONDS, lock, supplier);
    }

    @SneakyThrows
    private <T> T runWithLock(long timeout, TimeUnit unit, Lock lock, Supplier<T> supplier) {
        try {
            if (timeout == DISABLED_TIMEOUT_FLAG) {
                lock.lockInterruptibly();
            } else if (!lock.tryLock(timeout, unit)) {
                throw new TimeoutException(String.format("Can't get lock in given time %s %s", timeout, unit));
            }
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    @SneakyThrows
    private void sleep(long millis) {
        Thread.sleep(millis);
    }

    private String getKey(EntityId entityId) {
        return entityId.getClass().getCanonicalName() + KEY_DELIMITER + entityId.getId();
    }

    Map<String, Lock> getEntitiesLockHolder() {
        return entitiesLockHolder;
    }
}