package com.cdp.engine.runtime;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.WakeSignal;
import com.cdp.journey.JourneyDefinitionStore;

@Service
public class DefaultEnrollmentAdvancer implements EnrollmentAdvancer {

    private final StateStore stateStore;
    private final JourneyDefinitionStore journeys;
    private final NodeRegistry nodes;
    private final Clock clock;

    public DefaultEnrollmentAdvancer(
            StateStore stateStore,
            JourneyDefinitionStore journeys,
            NodeRegistry nodes,
            Clock clock) {
        this.stateStore = stateStore;
        this.journeys = journeys;
        this.nodes = nodes;
        this.clock = clock;
    }

    @Override
    public EnrollmentState advance(EnrollmentState state, WakeSignal wakeSignal) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
