package com.walpro.autobuilder.core.lifecycle;

public interface SignalBus {

    void publish(Signal signal);

    void subscribe(SignalType type, SignalHandler handler);
}
