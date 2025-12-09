package com.walpro.autobuilder.core.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class InMemorySignalBus implements SignalBus {

    private final Map<SignalType, List<SignalHandler>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void publish(Signal signal) {
        if (signal == null || signal.type() == null) {
            return;
        }
        var handlers = subscribers.get(signal.type());
        if (handlers == null || handlers.isEmpty()) {
            log.debug("No subscribers for signal type {}", signal.type());
            return;
        }
        for (SignalHandler h : handlers) {
            try {
                h.onSignal(signal);
            } catch (Exception e) {
                log.error("Error delivering signal {} to {}", signal, h, e);
            }
        }
    }

    @Override
    public void subscribe(SignalType type, SignalHandler handler) {
        subscribers
                .computeIfAbsent(type, t -> new CopyOnWriteArrayList<>())
                .add(handler);
        log.info("Registered SignalHandler {} for type {}", handler, type);
    }
}
