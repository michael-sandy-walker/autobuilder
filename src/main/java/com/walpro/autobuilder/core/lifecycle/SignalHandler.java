package com.walpro.autobuilder.core.lifecycle;

@FunctionalInterface
public interface SignalHandler {
    void onSignal(Signal signal);
}