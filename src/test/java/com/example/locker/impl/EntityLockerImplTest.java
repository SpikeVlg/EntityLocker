package com.example.locker.impl;

import com.example.locker.domain.TestEntity;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class EntityLockerImplTest {

    private static final int THREAD_POOL_SIZE = 10;
    private static final int ONE_TASK_INCREMENT_NUMBER = 5;
    private static final int ONE_TASK_DECREMENT_NUMBER = 5;
    private static final int NUMBER_TASKS = 100000;

    @Test
    void checkTwoEntitiesWereIncrementParallelWithoutLosingAndLockHoldersWasEmptyAfterFinishing() {
        EntityLockerImpl entityLocker = new EntityLockerImpl();

        TestEntity entity1 = new TestEntity("1");
        TestEntity entity2 = new TestEntity("2");

        executeAllTasks(THREAD_POOL_SIZE, taskGenerator(NUMBER_TASKS, i -> {
            TestEntity entity = i % 2 == 0 ? entity1 : entity2;
            return () -> entityLocker.lockExecute(entity, () -> entity.notAtomicIncrement(ONE_TASK_INCREMENT_NUMBER));
        }));

        assertThat(entity1.getCount()).isEqualTo((NUMBER_TASKS / 2) * ONE_TASK_DECREMENT_NUMBER);
        assertThat(entity2.getCount()).isEqualTo((NUMBER_TASKS / 2) * ONE_TASK_DECREMENT_NUMBER);

        assertThat(entityLocker.getEntitiesLockHolder()).isEmpty();
    }

    @Test
    void checkNotAtomicOperationInGlobalLockAndEntityLocksAtTheSameTime() {
        EntityLockerImpl entityLocker = new EntityLockerImpl();

        TestEntity entity1 = new TestEntity("1");
        TestEntity entity2 = new TestEntity("2");

        executeAllTasks(THREAD_POOL_SIZE, taskGenerator(3 * NUMBER_TASKS, i -> {
            if (i % 3 == 0) {
                return () -> entityLocker.lockExecute(entity1, () -> entity1.notAtomicIncrement(ONE_TASK_INCREMENT_NUMBER));
            } else if (i % 3 == 1) {
                return () -> entityLocker.lockExecute(entity2, () -> entity2.notAtomicIncrement(ONE_TASK_INCREMENT_NUMBER));
            } else {
                return () -> entityLocker.globalLockExecute(() -> {
                    entity1.notAtomicDecrement(ONE_TASK_INCREMENT_NUMBER);
                    entity2.notAtomicDecrement(ONE_TASK_INCREMENT_NUMBER);
                });
            }
        }));

        assertThat(entity1.getCount()).isZero();
        assertThat(entity1.getCount()).isZero();

        assertThat(entityLocker.getEntitiesLockHolder()).isEmpty();
    }

    @Test
    void checkTwoEntitiesAreChangedWithReentrantLocking() {
        EntityLockerImpl entityLocker = new EntityLockerImpl();

        TestEntity entity1 = new TestEntity("1");
        TestEntity entity2 = new TestEntity("2");

        executeAllTasks(THREAD_POOL_SIZE, taskGenerator(NUMBER_TASKS, i -> {
            TestEntity entity = i % 2 == 0 ? entity1 : entity2;
            return () -> entityLocker.lockExecute(entity, () -> {
                entity.notAtomicIncrement(ONE_TASK_INCREMENT_NUMBER);
                entityLocker.lockExecute(entity, () -> entity.notAtomicIncrement(ONE_TASK_INCREMENT_NUMBER));
            });
        }));

        assertThat(entity1.getCount()).isEqualTo(NUMBER_TASKS * ONE_TASK_DECREMENT_NUMBER);
        assertThat(entity2.getCount()).isEqualTo(NUMBER_TASKS * ONE_TASK_DECREMENT_NUMBER);

        assertThat(entityLocker.getEntitiesLockHolder()).isEmpty();
    }

    @Test
    void checkTwoEntitiesAreIncrementWithTimeout() {
        EntityLockerImpl entityLocker = new EntityLockerImpl();

        TestEntity entity1 = new TestEntity("1");
        TestEntity entity2 = new TestEntity("2");

        executeAllTasks(THREAD_POOL_SIZE, taskGenerator(NUMBER_TASKS, i -> {
            TestEntity entity = i % 2 == 0 ? entity1 : entity2;
            return () -> entityLocker.lockExecute(1, TimeUnit.SECONDS, entity, () -> entity.notAtomicIncrement(ONE_TASK_INCREMENT_NUMBER));
        }));

        assertThat(entity1.getCount()).isEqualTo((NUMBER_TASKS / 2) * ONE_TASK_DECREMENT_NUMBER);
        assertThat(entity2.getCount()).isEqualTo((NUMBER_TASKS / 2) * ONE_TASK_DECREMENT_NUMBER);

        assertThat(entityLocker.getEntitiesLockHolder()).isEmpty();
    }

    @Test
    void checkOneThreadIncrementsTwoEntities() {
        EntityLockerImpl entityLocker = new EntityLockerImpl();

        TestEntity entity1 = new TestEntity("1");
        TestEntity entity2 = new TestEntity("2");

        executeAllTasks(1, taskGenerator(NUMBER_TASKS, i -> {
            TestEntity entity = i % 2 == 0 ? entity1 : entity2;
            return () -> entityLocker.lockExecute(entity, () -> entity.notAtomicIncrement(ONE_TASK_INCREMENT_NUMBER));
        }));

        assertThat(entity1.getCount()).isEqualTo((NUMBER_TASKS / 2) * ONE_TASK_DECREMENT_NUMBER);
        assertThat(entity2.getCount()).isEqualTo((NUMBER_TASKS / 2) * ONE_TASK_DECREMENT_NUMBER);

        assertThat(entityLocker.getEntitiesLockHolder()).isEmpty();
    }

    private Collection<Callable<Void>> taskGenerator(Integer numberTasks, Function<Integer, Runnable> taskNumberToRunnable) {
        return IntStream.range(0, numberTasks)
                .boxed()
                .map(taskNumberToRunnable)
                .map(this::runnableToCallable)
                .collect(Collectors.toList());
    }

    private Callable<Void> runnableToCallable(Runnable runnable) {
        return () -> {
            runnable.run();
            return null;
        };
    }

    private void executeAllTasks(Integer threadPoolSize, Collection<Callable<Void>> tasks) {
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        try {
            executor.invokeAll(tasks);
        } catch (InterruptedException ex) {
            log.error("Get error during execution tasks", ex);
        }
        executor.shutdownNow();
    }
}