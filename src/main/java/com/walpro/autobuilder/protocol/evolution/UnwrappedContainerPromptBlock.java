package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.ContainerMismatchExtractor;
import com.walpro.autobuilder.core.build.MissingMemberExtractor;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.List;

/**
 * T2-FD: formatter for the {@code --- UNWRAPPED VALUE — FIX THE CALL, NOT THE TYPE ---} evolution
 * prompt block.
 *
 * <p>Why this is its own block rather than a branch of {@link MissingMemberPromptBlock}: it carries
 * the OPPOSITE instruction. That block says "add the member to the callee". Here the callee must
 * not grow anything — the caller chained a container method onto a plain value, so either the
 * method it came from should return the container, or the chain should not be there. Two contrary
 * instructions for one diagnostic inside one block is the shape this project files as a missing
 * capability, so the recogniser is shared (
 * {@link MissingMemberExtractor#unwrappedContainerCalls}) and the advice is not.
 *
 * <p><b>Two shapes, one invariant.</b> The tree can disagree about wrapping from either end, and
 * javac reports the two completely differently: a container method chained onto a plain value
 * surfaces as a MISSING METHOD ({@link MissingMemberExtractor#unwrappedContainerCalls}), while a
 * plain value handed where the container is expected surfaces as INCOMPATIBLE TYPES
 * ({@link com.walpro.autobuilder.core.build.ContainerMismatchExtractor}). Measured 2026-08-02, they
 * barely overlap — 6 of 70 — so each is mostly its own occurrence, and the second is the larger:
 * 169 cycles across 15 runs against 70 diagnostics across 9. The advice is identical for both, so
 * they share this block rather than getting one each; that is the invariant having one home.
 *
 * <p>History, because the sequencing is the point. T4-BB's block used to answer this shape with
 * "add {@code orElseThrow} to Book.java — do NOT change the caller(s)", which is backwards; run
 * 2026-07-30_18-39-00 shipped a {@code Book} carrying {@code public boolean isPresent()} because
 * of it. T1-FQ suppressed that on 2026-08-02, correctly, and thereby turned a wrong actuator into
 * no actuator: run 2026-08-02_17-05-13 lost a proposal to
 * {@code LoanService.java:[37,17] symbol: method orElseThrow(()->new Lo…)} with nothing steering
 * it. The shape is 70 diagnostics across 9 runs.
 */
public final class UnwrappedContainerPromptBlock {

    private UnwrappedContainerPromptBlock() {}

    public static String render(CycleContextData ctx) {
        if (ctx == null) return null;

        BuildOutcome probe = ctx.lastProbeOutcome();
        if (probe == null
                || probe.result() != BuildResult.COMPILE_ERROR
                || probe.stderrTail() == null) {
            return null;
        }

        List<MissingMemberExtractor.ContainerCall> calls =
                MissingMemberExtractor.unwrappedContainerCalls(probe.stderrTail());
        List<ContainerMismatchExtractor.Entry> mismatches =
                ContainerMismatchExtractor.extract(probe.stderrTail());
        if (calls.isEmpty() && mismatches.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("--- UNWRAPPED VALUE — FIX THE CALL, NOT THE TYPE ---\n");
        sb.append("The code disagrees with itself about whether a value is wrapped in a container. ")
          .append("Each line below is one such disagreement. ")
          .append("Do NOT add container methods to your own classes and do NOT change a domain ")
          .append("type to satisfy the compiler — that cannot work and it corrupts the type. ")
          .append("Fix ONE of: the method that produces the value returns the container type, or ")
          .append("the code that consumes it stops treating it as one. Make the producer and the ")
          .append("consumer agree. The location below is where the compiler noticed the ")
          .append("disagreement, which is the CONSUMER — if you have already corrected the ")
          .append("consumer and the error persists, the producer's return type is what is wrong, ")
          .append("and it is declared in another file.\n");
        for (MissingMemberExtractor.ContainerCall c : calls) {
            sb.append("  - ").append(c.memberName()).append("() chained onto a plain ")
              .append(c.targetType());
            if (c.referencedBy() != null) {
                sb.append(" at ").append(projectRelative(c.referencedBy()));
            }
            sb.append("\n");
        }
        for (ContainerMismatchExtractor.Entry m : mismatches) {
            sb.append("  - a plain ").append(m.plainType()).append(" supplied where ")
              .append(m.containerType()).append("<").append(m.plainType())
              .append("> is expected");
            if (m.referencedBy() != null) {
                sb.append(" at ").append(projectRelative(m.referencedBy()));
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** javac reports absolute host paths; the prompt carries the path the model writes with. */
    private static String projectRelative(String path) {
        int src = path.indexOf("src/main/java/");
        if (src < 0) src = path.indexOf("src/test/java/");
        return src < 0 ? path : path.substring(src);
    }
}
