package com.walpro.autobuilder.core.lifecycle;

import java.util.Objects;

public record SignalType(String name) {
    public SignalType {
        Objects.requireNonNull(name, "name must not be null");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("SignalType.name must not be blank");
        }
        name = name.toUpperCase();
    }
    public static SignalType of(String name) { return new SignalType(name); }
}
