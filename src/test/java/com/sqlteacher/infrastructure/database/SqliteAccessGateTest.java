package com.sqlteacher.infrastructure.database;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteAccessGateTest {

    @Test
    void exclusivelyShouldSerializeConcurrentActions() throws Exception {
        SqliteAccessGate gate = new SqliteAccessGate();
        int workers = 4;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(workers);
        CountDownLatch done = new CountDownLatch(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        for (int index = 0; index < workers; index++) {
            executor.submit(() -> {
                start.countDown();
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                gate.exclusively(() -> {
                    int current = inside.incrementAndGet();
                    maxInside.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                    return null;
                });
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, maxInside.get());
    }

    @Test
    void sharedShouldAllowConcurrentReadersUntilAWriterArrives() throws Exception {
        SqliteAccessGate gate = new SqliteAccessGate();
        int readers = 3;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(readers);
        ExecutorService executor = Executors.newFixedThreadPool(readers);
        for (int index = 0; index < readers; index++) {
            executor.submit(() -> {
                gate.shared(() -> {
                    int current = inside.incrementAndGet();
                    maxInside.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                    return null;
                });
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(readers, maxInside.get());
    }

    @Test
    void sharedShouldWaitForAnInFlightExclusiveAction() throws Exception {
        SqliteAccessGate gate = new SqliteAccessGate();
        CountDownLatch writerInside = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicBoolean readerAdmitted = new AtomicBoolean(false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var writer = executor.submit(() -> gate.exclusively(() -> {
            writerInside.countDown();
            try {
                releaseWriter.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertTrue(writerInside.await(10, TimeUnit.SECONDS));
        var reader = executor.submit(() -> gate.shared(() -> {
            readerAdmitted.set(true);
            return null;
        }));
        Thread.sleep(100);
        assertFalse(readerAdmitted.get());
        releaseWriter.countDown();
        reader.get(10, TimeUnit.SECONDS);
        writer.get(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(readerAdmitted.get());
    }
}
