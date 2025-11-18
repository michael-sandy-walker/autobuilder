package com.walpro.autobuilder.core.lifecycle;

public record SignalType(String name) {
    public SignalType {
        name = name.toUpperCase();
    }
    public static SignalType of(String name) { return new SignalType(name); }
}
