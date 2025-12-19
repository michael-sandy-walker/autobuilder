package com.walpro.autobuilder.core.deliberation;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public record DeliberationDecision(
        CycleContextData context,
        DeliberationResult result
) { }
