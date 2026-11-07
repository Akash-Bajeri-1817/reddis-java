package com.reddis.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** A Clock for tests: time stands still until you call advance(). Thread-safe. */
public final class MutableClock extends Clock {

    private final AtomicLong nowMillis;

    public MutableClock(long startMillis) {
        this.nowMillis = new AtomicLong(startMillis);
    }

    public void advance(long millis) {
        nowMillis.addAndGet(millis);
    }

    @Override
    public long millis() {
        return nowMillis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(nowMillis.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this; // zones are irrelevant to us
    }
}