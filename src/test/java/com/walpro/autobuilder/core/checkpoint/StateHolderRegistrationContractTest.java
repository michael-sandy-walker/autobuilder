package com.walpro.autobuilder.core.checkpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DQ slice 1: THE REGISTRATION CONTRACT — a singleton bean that carries mutable run state and
 * declares no {@link StateBucket} fails the build.
 *
 * <p>This test is the mechanism that stops blocker 2 recurring. The five decision-bearing trackers
 * were never persisted not because anyone decided they should not be, but because nothing ever
 * asked. An inventory maintained by hand falls behind the code silently; an inventory the compiler
 * and the test suite enforce cannot. Adding a stateful bean now costs one annotation and one
 * sentence saying why, and the alternative is a red build.
 *
 * <p><b>What counts as a state holder.</b> A bean-annotated class with at least one non-static
 * instance field that is either mutable-by-assignment (a non-final scalar, string, enum or
 * temporal) or a mutable container (collection, map, atomic). Deliberately excluded, because they
 * are not run state:
 * <ul>
 *   <li>injected configuration — {@code @Value}-annotated fields and {@code @ConfigurationProperties}
 *       classes; identical either side of a rewind;</li>
 *   <li>injected collaborators — {@code @Autowired} fields, final references, and final
 *       collections whose element type is one of our own beans or interfaces (a
 *       constructor-injected {@code List<EvaluationHeuristic>} is a wiring list, not state).</li>
 * </ul>
 * A false positive costs one honest annotation. A false negative costs a silent gap in a restore,
 * so the heuristic errs towards including.
 */
class StateHolderRegistrationContractTest {

    private static final String BASE = "com.walpro.autobuilder";

    @Test
    @DisplayName("every stateful singleton bean declares a state bucket")
    void everyStatefulBeanDeclaresABucket() {
        List<String> undeclared = new ArrayList<>();
        for (Class<?> bean : beanClasses()) {
            if (bean.isAnnotationPresent(StateHolder.class)) continue;
            List<String> state = mutableState(bean);
            if (!state.isEmpty()) {
                undeclared.add(bean.getName() + " :: " + String.join(", ", state));
            }
        }
        assertThat(undeclared)
                .as("""
                    These beans carry mutable run state and declare no @StateHolder bucket, so a \
                    checkpoint does not know whether to capture, reference or deliberately drop \
                    them. Add @StateHolder(value = StateBucket.…, note = "why") to each. \
                    CAPTURE means it gates behaviour and must also implement CheckpointableState; \
                    ALREADY_PERSISTED means it has a durable home of its own; \
                    DERIVED_DO_NOT_RESTORE means restoring it would be a bug.""")
                .isEmpty();
    }

    @Test
    @DisplayName("every declared bucket carries a reason")
    void everyBucketCarriesAReason() {
        List<String> silent = new ArrayList<>();
        for (Class<?> bean : beanClasses()) {
            StateHolder holder = bean.getAnnotation(StateHolder.class);
            if (holder != null && holder.note().isBlank()) silent.add(bean.getName());
        }
        assertThat(silent)
                .as("@StateHolder.note records WHY the bucket is right — the part a later reader "
                        + "needs in order to change it correctly")
                .isEmpty();
    }

    @Test
    @DisplayName("CAPTURE and CheckpointableState imply each other")
    void captureAndCheckpointableAgree() {
        List<String> captureWithoutInterface = new ArrayList<>();
        List<String> interfaceWithoutCapture = new ArrayList<>();
        for (Class<?> bean : beanClasses()) {
            StateHolder holder = bean.getAnnotation(StateHolder.class);
            boolean checkpointable = CheckpointableState.class.isAssignableFrom(bean);
            if (holder != null && holder.value() == StateBucket.CAPTURE && !checkpointable) {
                captureWithoutInterface.add(bean.getName());
            }
            if (checkpointable && (holder == null || holder.value() != StateBucket.CAPTURE)) {
                interfaceWithoutCapture.add(bean.getName());
            }
        }
        assertThat(captureWithoutInterface)
                .as("a CAPTURE holder must implement CheckpointableState — otherwise it is "
                        + "declared as captured and is not")
                .isEmpty();
        assertThat(interfaceWithoutCapture)
                .as("a CheckpointableState bean must be declared CAPTURE — otherwise the manifest "
                        + "restores state the declaration says should not come back")
                .isEmpty();
    }

    @Test
    @DisplayName("state keys are unique across all capture holders")
    void stateKeysAreUnique() {
        Map<String, List<String>> byKey = new java.util.LinkedHashMap<>();
        for (Class<?> bean : beanClasses()) {
            if (!CheckpointableState.class.isAssignableFrom(bean)) continue;
            String key = stateKeyOf(bean);
            if (key != null) byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(bean.getName());
        }
        List<String> collisions = byKey.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " -> " + e.getValue())
                .toList();
        assertThat(collisions)
                .as("two holders sharing a state key would overwrite each other in the checkpoint")
                .isEmpty();
    }

    @Test
    @DisplayName("the five trackers the design named are all CAPTURE")
    void theNamedTrackersAreCaptured() {
        List<String> named = List.of(
                "com.walpro.autobuilder.core.build.RecurringDefectTracker",
                "com.walpro.autobuilder.core.build.inject.FixAttemptLedger",
                "com.walpro.autobuilder.core.lifecycle.RootConvergenceTracker",
                "com.walpro.autobuilder.core.mutation.RejectedProposalLedger",
                "com.walpro.autobuilder.core.mutation.DeferredMutationLedger");
        for (String name : named) {
            Class<?> c = load(name);
            StateHolder holder = c.getAnnotation(StateHolder.class);
            assertThat(holder).as("%s declares a bucket", name).isNotNull();
            assertThat(holder.value()).as("%s is decision-bearing", name).isEqualTo(StateBucket.CAPTURE);
            assertThat(CheckpointableState.class.isAssignableFrom(c))
                    .as("%s implements CheckpointableState", name).isTrue();
        }
    }

    // ---------------------------------------------------------------------------------

    /**
     * Every {@code stateKey()} implementation returns a constant, so a Mockito instance with real
     * methods reads it without running a constructor — the holders take collaborators this test
     * has no business standing up.
     */
    private static String stateKeyOf(Class<?> bean) {
        try {
            Object instance = org.mockito.Mockito.mock(bean, org.mockito.Mockito.CALLS_REAL_METHODS);
            return ((CheckpointableState) instance).stateKey();
        } catch (Exception | NoClassDefFoundError e) {
            return null;
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("class named by the T4-DQ design is gone: " + name, e);
        }
    }

    private static List<Class<?>> beanClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Repository.class));
        List<Class<?>> beans = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE)) {
            String name = definition.getBeanClassName();
            if (name == null) continue;
            try {
                Class<?> c = Class.forName(name);
                if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) continue;
                if (c.isAnnotationPresent(ConfigurationProperties.class)) continue;
                beans.add(c);
            } catch (Throwable ignored) {
                // a class we cannot load is a class the contract cannot speak about
            }
        }
        return beans;
    }

    /** The fields that make this bean a state holder — empty for a stateless one. */
    private static List<String> mutableState(Class<?> bean) {
        List<String> state = new ArrayList<>();
        for (Field f : bean.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            if (f.isAnnotationPresent(Value.class) || f.isAnnotationPresent(Autowired.class)) continue;
            Class<?> type = f.getType();
            String typeName = type.getName();
            if (typeName.startsWith("org.springframework")
                    || typeName.startsWith("com.fasterxml")
                    || typeName.startsWith("org.neo4j")
                    || typeName.startsWith("org.slf4j")) {
                continue;
            }
            boolean container = Collection.class.isAssignableFrom(type)
                    || Map.class.isAssignableFrom(type)
                    || typeName.startsWith("java.util.concurrent.atomic")
                    || type == StringBuilder.class;
            boolean isFinal = Modifier.isFinal(f.getModifiers());
            if (isFinal && !container) continue;
            if (container && collaboratorContainer(f)) continue;
            if (!isFinal && !container && !assignableState(type)) continue;
            state.add(type.getSimpleName() + " " + f.getName());
        }
        return state;
    }

    /** A non-final field of one of these types is state; of any other type it is a collaborator. */
    private static boolean assignableState(Class<?> type) {
        return type.isPrimitive() || type.isEnum()
                || type == String.class || type == Instant.class || type == Duration.class
                || type == Integer.class || type == Long.class || type == Double.class
                || type == Boolean.class || type == AtomicBoolean.class;
    }

    /** A final collection of our own beans or interfaces is injected wiring, not state. */
    private static boolean collaboratorContainer(Field f) {
        Type generic = f.getGenericType();
        if (!(generic instanceof ParameterizedType parameterized)) return false;
        for (Type arg : parameterized.getActualTypeArguments()) {
            if (!(arg instanceof Class<?> argClass)) continue;
            if (!argClass.getName().startsWith(BASE)) continue;
            if (argClass.isInterface()) return true;
            if (argClass.isAnnotationPresent(Component.class)
                    || argClass.isAnnotationPresent(Service.class)
                    || argClass.isAnnotationPresent(Repository.class)) {
                return true;
            }
        }
        return false;
    }

}
