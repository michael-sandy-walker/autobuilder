package com.walpro.autobuilder.agent.meta.protocol;

import java.util.Optional;

public record MetaNegotiationRecord(
    String name,
    String roleType,
    Optional<String> goalId,
    String rejectionReason
) {}
