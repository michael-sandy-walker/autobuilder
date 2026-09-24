package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * T4-DQ: the live register of {@link StateBucket#CAPTURE} holders — what a checkpoint writes and
 * what a rewind puts back.
 *
 * <p>Holders are resolved lazily BY NAME through the bean factory rather than injected: several of
 * them ({@code CycleExecutor}, {@code AutobuilderService}) sit upstream of the rewind path, so a
 * constructor-injected list would close a dependency cycle.
 *
 * <p><b>And one at a time, which the first field run made non-optional.</b> A single lazily
 * resolved stream was the obvious form and it lost the whole capture to one bean: the bootstrap
 * cycle runs from inside {@code AutobuilderService}'s own construction, so resolving it threw
 * "currently in creation" and the checkpoint for cycle 1 recorded ZERO holders instead of eleven
 * plus one named failure. Resolving per name, each in its own try, makes an unavailable holder cost
 * exactly itself.
 *
 * <p>Capture and restore are both total over the register and never partial-on-error: a holder
 * that throws is recorded as a failure in the report and the rest still run, because a rewind
 * that silently restores nine holders of ten is the failure mode this design exists to avoid
 * being unable to see.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateManifest {

    private final ListableBeanFactory beanFactory;

    /**
     * Every registered CAPTURE holder that can be resolved right now, ordered by state key so a
     * checkpoint file is stable. Holders that cannot be resolved are reported by
     * {@link #unresolvable()} rather than throwing.
     */
    public List<CheckpointableState> holders() {
        List<CheckpointableState> all = new ArrayList<>();
        for (String name : holderNames()) {
            resolve(name).ifPresent(all::add);
        }
        all.sort(java.util.Comparator.comparing(CheckpointableState::stateKey));
        return all;
    }

    /** Registered holders the container could not hand over, with the reason. */
    public Map<String, String> unresolvable() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : holderNames()) {
            try {
                beanFactory.getBean(name, CheckpointableState.class);
            } catch (RuntimeException e) {
                out.put(name, e.getMessage());
            }
        }
        return out;
    }

    private String[] holderNames() {
        try {
            String[] names = beanFactory.getBeanNamesForType(CheckpointableState.class, true, false);
            java.util.Arrays.sort(names);
            return names;
        } catch (RuntimeException e) {
            log.warn("T4-DQ: could not enumerate state holders: {}", e.getMessage());
            return new String[0];
        }
    }

    private java.util.Optional<CheckpointableState> resolve(String name) {
        try {
            return java.util.Optional.of(beanFactory.getBean(name, CheckpointableState.class));
        } catch (RuntimeException e) {
            log.debug("T4-DQ: state holder {} is not resolvable right now: {}", name, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Capture every holder's decision-bearing state. Duplicate keys are a registration error and
     * are reported rather than silently overwritten.
     */
    public Capture capture() {
        Map<String, Object> state = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        unresolvable().forEach((name, why) -> failures.add(name + ": unresolvable — " + why));
        for (CheckpointableState holder : holders()) {
            String key = holder.stateKey();
            try {
                if (state.containsKey(key)) {
                    failures.add(key + ": duplicate state key (" + holder.getClass().getName() + ")");
                    continue;
                }
                state.put(key, holder.captureState());
            } catch (Exception e) {
                failures.add(key + ": " + e.getMessage());
                log.warn("T4-DQ: capture failed for state holder {}: {}", key, e.getMessage());
            }
        }
        return new Capture(state, List.copyOf(failures));
    }

    /**
     * Restore every holder from a previously captured map. A holder with no entry in the node is
     * restored to its start-of-run value (a null node), not left as it is — a rewind past the
     * point a holder first recorded anything must un-record it.
     */
    public Restore restore(JsonNode holdersNode, ObjectMapper mapper) {
        List<String> restored = new ArrayList<>();
        List<String> reset = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        // A holder the container cannot hand over is one the rewind did not put back. Silence here
        // would be the dangerous shape: a restore that looks total and is not.
        unresolvable().forEach((name, why) -> failures.add(name + ": unresolvable — " + why));
        for (CheckpointableState holder : holders()) {
            String key = holder.stateKey();
            JsonNode node = holdersNode == null ? null : holdersNode.get(key);
            try {
                holder.restoreState(node, mapper);
                if (node == null || node.isNull()) {
                    reset.add(key);
                } else {
                    restored.add(key);
                }
            } catch (Exception e) {
                failures.add(key + ": " + e.getMessage());
                log.warn("T4-DQ: restore failed for state holder {}: {}", key, e.getMessage());
            }
        }
        return new Restore(List.copyOf(restored), List.copyOf(reset), List.copyOf(failures));
    }

    /** Captured state plus whatever refused to be captured. */
    public record Capture(Map<String, Object> state, List<String> failures) {
        public boolean complete() {
            return failures.isEmpty();
        }
    }

    /** What a restore actually did — restored, reset to start-of-run, or failed. */
    public record Restore(List<String> restored, List<String> reset, List<String> failures) {
        public boolean complete() {
            return failures.isEmpty();
        }
    }
}
