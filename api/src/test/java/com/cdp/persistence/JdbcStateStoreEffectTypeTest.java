package com.cdp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.engine.node.Effect;

class JdbcStateStoreEffectTypeTest {

    @Test
    void sendMessageMapsToStableName() {
        Effect e = new Effect.SendMessage("k", "tpl", "ch", Map.of());
        assertThat(JdbcStateStore.effectType(e)).isEqualTo("SendMessage");
    }
}
