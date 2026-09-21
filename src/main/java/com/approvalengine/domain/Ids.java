package com.approvalengine.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Identifier generation.
 *
 * <p>Ids are prefixed and time-ordered: {@code req_m8x2k1p4_a7f2c9}. Three properties earn
 * that shape:
 * <ul>
 *   <li><b>Prefixed</b> — a bare id in a log line or a support ticket says what it is. When
 *       an API takes both a request id and a task id, passing the wrong one is immediately
 *       obvious rather than a confusing 404.</li>
 *   <li><b>Time-ordered</b> — the millisecond timestamp leads, so ids sort chronologically.
 *       That keeps index locality reasonable and makes debugging output readable.</li>
 *   <li><b>Generated in the application, not the database</b> — the engine writes a request,
 *       its stages, its tasks, its audit rows and its outbox row in a single transaction,
 *       and needs the parent ids before any of it is flushed.</li>
 * </ul>
 *
 * <p>A monotonic counter is mixed in alongside randomness so that ids minted within the
 * same millisecond still sort deterministically.
 */
public final class Ids {

    public static final String APPLICATION = "app";
    public static final String WORKFLOW = "wf";
    public static final String REQUEST = "req";
    public static final String STAGE = "stg";
    public static final String TASK = "tsk";
    public static final String AUDIT = "aud";
    public static final String OUTBOX = "evt";
    public static final String MEMBERSHIP = "mem";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger SEQUENCE = new AtomicInteger(RANDOM.nextInt(1 << 16));

    private Ids() {
    }

    public static String newId(String prefix) {
        long millis = Instant.now().toEpochMilli();
        int seq = SEQUENCE.incrementAndGet() & 0xFFFF;
        int noise = RANDOM.nextInt(1 << 24);
        return prefix + '_'
                + Long.toString(millis, 36)
                + '_' + pad(Integer.toString(seq, 36), 4)
                + pad(Integer.toString(noise, 36), 5);
    }

    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        StringBuilder sb = new StringBuilder(width);
        for (int i = value.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(value).toString();
    }
}
