package com.example.locker;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public interface EntityLocker {

    <T> T globalLockExecute(Supplier<T> supplier);

    void globalLockExecute(Runnable runnable);

    <T> T globalLockExecute(long timeout, TimeUnit unit, Supplier<T> supplier);

    void globalLockExecute(long timeout, TimeUnit unit, Runnable runnable);

    void lockExecute(EntityId entityId, Runnable runnable);

    <T> T lockExecute(EntityId entityId, Supplier<T> supplier);

    <T> T lockExecute(long timeout, TimeUnit unit, EntityId entityId, Supplier<T> supplier);

    void lockExecute(long timeout, TimeUnit unit, EntityId entityId, Runnable runnable);
}
