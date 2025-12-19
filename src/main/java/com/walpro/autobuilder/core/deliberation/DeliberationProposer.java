package com.walpro.autobuilder.core.deliberation;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface DeliberationProposer {
    DeliberationResult propose(CycleContextData context);
}
