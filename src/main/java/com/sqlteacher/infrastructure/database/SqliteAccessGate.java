package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Process-level read/write gate that serializes SQLite maintenance actions
 * (backup, restore, demo reset) against each other. A single gate instance must
 * be shared by every maintenance entry point of one application process, because
 * mutual exclusion only holds inside one gate.
 *
 * <p>Maintenance actions run through {@link #exclusively(Supplier)} so two
 * restores or a backup and a restore never replace database files concurrently.
 * The {@link #shared(Supplier)} side is reserved for read paths that may adopt
 * the gate later; it uses a bounded {@code tryLock} so a queued writer cannot
 * deadlock a re-entrant reader.
 */
public final class SqliteAccessGate {
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public <T> T exclusively(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public <T> T shared(Supplier<T> action) {
        boolean acquired = false;
        try {
            acquired = lock.readLock().tryLock(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            throw new SqlTeacherException(
                "SQLITE_ACCESS_GATE_TIMEOUT", "Timed out waiting for SQLite maintenance to finish");
        }
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }
}
