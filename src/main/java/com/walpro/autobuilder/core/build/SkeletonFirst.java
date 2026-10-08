package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T6-T: skeleton first, as the loop's first move on a change its operator specified with tests. When the target
 * declares its suite an authority and the tree's tests do not compile, the missing surface is stubbed from the
 * compiler's errors ({@link SkeletonStubber}) before any candidate is proposed, and the work becomes one item per
 * stubbed class: write the bodies of these members, done when these test classes pass. Each item is closed by that
 * measurement ({@link BlockerTypes#GATE_SKELETON}), never by a score.
 *
 * <p>Once per run: a tree that still does not compile after it is the evolution's to finish, and the loop's other
 * measures (the compile-file progress, the test regression guard) take over. Switch:
 * {@code autobuilder.skeleton-first.enabled}.
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "a once-per-run flag; the stubs themselves are in the tree, which a checkpoint captures")
public class SkeletonFirst {

    @Value("${autobuilder.skeleton-first.enabled:true}")
    @Setter
    private boolean enabled = true;

    /** How long stubbing may take, every compile pass included. */
    @Value("${autobuilder.skeleton-first.budget-ms:300000}")
    @Setter
    private long budgetMs = 300_000;

    @Autowired(required = false)
    @Setter
    private ProtectedPaths protectedPaths;

    @Autowired(required = false)
    @Setter
    private ProjectClasspathService classpath;

    @Autowired(required = false)
    @Setter
    private GoalPlannerService goals;

    @Autowired(required = false)
    @Setter
    private BehaviourLogger behaviourLogger;

    private volatile boolean attempted;

    /** Whether this run has already stubbed (or tried to). */
    public boolean attempted() {
        return attempted;
    }

    /**
     * Stubs the tree's missing surface once, and raises one work item per stubbed class. True when anything was
     * stubbed (the caller re-runs the tree's tests: the baseline changed).
     */
    public boolean apply(Path project, String cycleId) {
        if (!enabled || attempted || project == null) return false;
        attempted = true;
        String cp = "";
        try {
            if (classpath != null) cp = classpath.resolveClasspath(project, 180_000);
        } catch (RuntimeException e) {
            log.debug("T6-T: no classpath for the skeleton's type discovery: {}", e.getMessage());
        }
        SkeletonStubber.Result r = SkeletonStubber.stub(project, this::writable, cp == null ? "" : cp, budgetMs);
        log.info("T6-T: skeleton first -- {} stub(s) in {} pass(es); the tests {}{}", r.stubs().size(), r.passes(),
                r.compiles() ? "compile" : "still do not compile",
                r.remaining().isEmpty() ? "" : " -- " + String.join(" | ", r.remaining()));
        r.links().forEach(l -> log.info("T6-T: skeleton first -- the tests demand: {}", l.describe()));
        List<String> items = r.stubs().isEmpty() ? List.of() : raiseWorkItems(project, r.stubs(), r.links());
        if (behaviourLogger != null) {
            behaviourLogger.skeletonStubbed(cycleId, r.compiles(), r.passes(),
                    r.stubs().stream().map(SkeletonStubber.Stub::describe).toList(), items.size());
        }
        return !r.stubs().isEmpty();
    }

    /** A main source the loop may write: never a test, never a protected path. */
    boolean writable(String relative) {
        String p = relative.replace('\\', '/');
        if (!p.startsWith("src/main/")) return false;
        return protectedPaths == null || !protectedPaths.isProtected(p);
    }

    /**
     * One work item per stubbed class: its stubbed members and the test classes that use it -- ordered by the chain the
     * tests demand (a class another must call comes first), each naming the calls its class must make.
     */
    List<String> raiseWorkItems(Path project, List<SkeletonStubber.Stub> stubs, List<SkeletonDeduction.Link> links) {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (SkeletonStubber.Stub s : stubs) {
            if (s.kind().equals("return")) continue;
            List<String> members = byType.computeIfAbsent(s.type(), k -> new ArrayList<>());
            if (s.member() != null && !s.kind().equals("class")) members.add(s.member());
        }
        byType = calleesFirst(byType, links);
        List<String> ids = new ArrayList<>();
        if (goals == null) return ids;
        for (Map.Entry<String, List<String>> t : byType.entrySet()) {
            String simple = t.getKey().substring(t.getKey().lastIndexOf('.') + 1);
            List<String> tests = testsUsing(project, simple);
            if (tests.isEmpty() || t.getValue().isEmpty()) continue;
            List<String> mustCall = links.stream().filter(l -> l.caller().equals(t.getKey())).map(SkeletonDeduction.Link::describe).toList();
            String description = "Write the bodies of " + simple + "'s skeleton members -- " + String.join(", ", t.getValue())
                    + " -- each still throws UnsupportedOperationException(\"" + SkeletonStubber.STUB_MESSAGE
                    + "\"). Done when " + String.join(", ", tests) + " pass" + (tests.size() == 1 ? "es" : "") + "."
                    + (mustCall.isEmpty() ? "" : " The tests demand: " + String.join("; ", mustCall) + ".");
            List<String> criteria = tests.stream().map(c -> c + " passes").toList();
            goals.injectSkeletonWorkItem(description, criteria, String.join(",", tests)).ifPresent(ids::add);
        }
        log.info("T6-T: skeleton first -- {} work item(s), one per stubbed class", ids.size());
        return ids;
    }

    /** The stubbed classes in the order the chain allows: a class another must call before that caller. */
    static Map<String, List<String>> calleesFirst(Map<String, List<String>> byType, List<SkeletonDeduction.Link> links) {
        List<String> order = new ArrayList<>(byType.keySet());
        for (SkeletonDeduction.Link l : links) {
            int caller = order.indexOf(l.caller()), callee = order.indexOf(l.callee());
            if (caller >= 0 && callee > caller) {
                order.remove(callee);
                order.add(caller, l.callee());
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String t : order) out.put(t, byType.get(t));
        return out;
    }

    /** The test classes whose source names {@code simpleName}. */
    public static List<String> testsUsing(Path project, String simpleName) {
        Path test = project.resolve("src/test/java");
        if (!Files.isDirectory(test)) return List.of();
        Pattern word = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b");
        try (Stream<Path> walk = Files.walk(test)) {
            return walk.filter(p -> p.toString().endsWith("Test.java") || p.toString().endsWith("Tests.java"))
                    .filter(p -> {
                        try {
                            return word.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
