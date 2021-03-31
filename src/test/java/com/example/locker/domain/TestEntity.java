package com.example.locker.domain;

import com.example.locker.EntityId;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TestEntity implements EntityId {

    private final String id;
    private volatile int count = 0;

    public TestEntity(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public void notAtomicIncrement(int incrementNumber) {
        for (int i = 0; i < incrementNumber; i++) {
            count++;
        }
    }

    public void notAtomicDecrement(int decrementNumber) {
        for (int i = 0; i < decrementNumber; i++) {
            count--;
        }
    }

    public int getCount() {
        return count;
    }

    @Override
    public String toString() {
        return "id = " + id + " cnt = " + count;
    }
}
