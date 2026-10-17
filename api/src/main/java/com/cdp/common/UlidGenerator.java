package com.cdp.common;

import java.time.Clock;

import org.springframework.stereotype.Component;

/** ULID：時間取自注入的 Clock，隨機段用 SecureRandom；不引入第三方套件。 */
@Component
public class UlidGenerator implements IdGenerator {

    private final Clock clock;

    public UlidGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String newId() {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
