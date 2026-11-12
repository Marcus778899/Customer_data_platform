package com.cdp.delivery;

import org.springframework.stereotype.Component;

import com.cdp.journey.JourneyDefinitionStore;
import com.cdp.journey.JourneyStatus;
import com.cdp.journey.JourneyStore;

/**
 * 旅程已停止（journey.status = ABORTED），或綁定版本的 status 不是 ACTIVE，即視為停止
 * （journeys.md §3.5、spec 05 §5）。已推送但尚未送出的發送由 consumer 以此取消。
 */
@Component
public class JourneyStatusKillSwitch implements KillSwitch {

    private final JourneyDefinitionStore definitions;
    private final JourneyStore journeys;

    public JourneyStatusKillSwitch(JourneyDefinitionStore definitions, JourneyStore journeys) {
        this.definitions = definitions;
        this.journeys = journeys;
    }

    @Override
    public boolean isStopped(String journeyId, int journeyVersion) {
        if (journeys.isAborted(journeyId)) {
            return true;
        }
        return definitions
                .find(journeyId, journeyVersion)
                .map(d -> d.status() != JourneyStatus.ACTIVE)
                .orElse(true);
    }
}
