package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.goal.DeclaredTestSuite;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * T6-T: the one place that says what the operator's suite measures of the tree as merged -- read by the
 * checkpoint, the snapshot stamp and the mid-run preserve, and told by the rewind when the tree was replaced.
 *
 * <p>Null unless the operator declared the suite an authority ({@link DeclaredTestSuite}): on a target that
 * writes its own tests the failing count is the model's own bookkeeping, and ranking trees by it would let a
 * tree that deleted its tests outrank one that kept them.
 */
@Component
public class OperatorSuiteMeasure {

    private final ObjectProvider<TestRunGate> testRunGate;
    private final ObjectProvider<DeclaredTestSuite> declaredSuite;

    /**
     * Off: the suite measures nothing -- no checkpoint, snapshot, rewind, adoption, preserve or reopen decision
     * reads it, and a rewind leaves the gate's baseline alone, exactly as before option A. For ablation runs.
     */
    @org.springframework.beans.factory.annotation.Value("${autobuilder.suite-measure.enabled:true}")
    private boolean enabled = true;

    public OperatorSuiteMeasure(ObjectProvider<TestRunGate> testRunGate, ObjectProvider<DeclaredTestSuite> declaredSuite) {
        this.testRunGate = testRunGate;
        this.declaredSuite = declaredSuite;
    }

    /** The merged tree's measure, or null when unknown or when no suite is declared. */
    public TreeTestMeasure ofTree() {
        if (!enabled) return null;
        TestRunGate gate = testRunGate.getIfAvailable();
        DeclaredTestSuite declared = declaredSuite.getIfAvailable();
        if (gate == null || declared == null || !declared.isDeclared()) return null;
        return gate.treeMeasure();
    }

    /** A rewind put another tree on disk: what was known of the one it replaced no longer holds. */
    public void treeReplaced() {
        if (!enabled) return;
        TestRunGate gate = testRunGate.getIfAvailable();
        if (gate != null) gate.forgetTree();
    }
}
