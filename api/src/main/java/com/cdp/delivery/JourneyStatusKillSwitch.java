package com.cdp.delivery;

import org.springframework.stereotype.Component;

import com.cdp.journey.JourneyDefinitionStore;

/** 綁定版本的 status 不是 ACTIVE 即視為停止。 */
@Component
public class JourneyStatusKillSwitch implements KillSwitch {

    private final JourneyDefinitionStore journeys;

    public JourneyStatusKillSwitch(JourneyDefinitionStore journeys) {
        this.journeys = journeys;
    }

    @Override
    public boolean isStopped(String tenantId, String journeyId, int journeyVersion) {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }
}
