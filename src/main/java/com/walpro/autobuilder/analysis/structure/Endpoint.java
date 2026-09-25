package com.walpro.autobuilder.analysis.structure;

/**
 * One normalized endpoint: upper-case verb (or {@code *} = any) + normalized path.
 *
 * <p>T5-BR: moved out of {@link EndpointSurfaceQuery}, where it was a nested record, so the pure
 * readers of specification prose ({@link SpecEndpointBehaviourQuery}, and through it the criterion
 * rules in {@code core.build}) no longer pull a Spring component and a Java parser into their
 * dependency closure just to name an endpoint.
 */
public record Endpoint(String verb, String path) {
    @Override
    public String toString() {
        return verb + " " + path;
    }
}
