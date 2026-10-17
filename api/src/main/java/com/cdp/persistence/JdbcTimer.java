package com.cdp.persistence;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.engine.Timer;

/** 只寫 enrollment_record.next_wake_at（R1）；不另建排程器。 */
@Repository
public class JdbcTimer implements Timer {

    private final JdbcClient jdbc;

    public JdbcTimer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void schedule(String enrollmentId, Instant wakeAt) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public void cancel(String enrollmentId) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public void cancelAll(String tenantId, String journeyId) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
