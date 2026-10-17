package com.cdp.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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
        OffsetDateTime at = OffsetDateTime.ofInstant(wakeAt, ZoneOffset.UTC);
        jdbc.sql("update enrollment_record set next_wake_at = :at where enrollment_id = :id")
                .param("at", at)
                .param("id", enrollmentId)
                .update();
    }

    @Override
    public void cancel(String enrollmentId) {
        jdbc.sql(
                        "update enrollment_record set next_wake_at = null"
                                + " where enrollment_id = :id and next_wake_at is not null")
                .param("id", enrollmentId)
                .update();
    }

    @Override
    public void cancelAll(String tenantId, String journeyId) {
        jdbc.sql(
                        "update enrollment_record set next_wake_at = null"
                                + " where tenant_id = :tenant and journey_id = :journey"
                                + " and next_wake_at is not null")
                .param("tenant", tenantId)
                .param("journey", journeyId)
                .update();
    }
}
