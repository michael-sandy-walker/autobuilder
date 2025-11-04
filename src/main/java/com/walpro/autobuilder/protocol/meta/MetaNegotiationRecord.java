package com.walpro.autobuilder.protocol.meta;

import java.util.Optional;

public record MetaNegotiationRecord(
    String name,
    String roleType,
    Optional<String> goalId,
    String rejectionReason
) {}
