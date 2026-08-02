package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
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
        if (calls.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("--- UNWRAPPED VALUE — FIX THE CALL, NOT THE TYPE ---\n");
        sb.append("The previous compile probe reported the following as a missing method, but ")
          .append(calls.size() == 1 ? "it is not one" : "they are not")
          .append(". Each call chains a method that belongs to a container onto a plain value of ")
          .append("your own type, so the value was never wrapped in the first place. ")
          .append("Do NOT add ")
          .append(calls.size() == 1 ? "this method" : "these methods")
          .append(" to your own class — that cannot compile and it corrupts the type. ")
          .append("Fix ONE of: the method that produced the value should return the container ")
          .append("type, or the call site should stop chaining the container method.\n");
        for (MissingMemberExtractor.ContainerCall c : calls) {
            sb.append("  - ").append(c.memberName()).append("() chained onto a plain ")
              .append(c.targetType());
            if (c.referencedBy() != null) {
                sb.append(" at ").append(projectRelative(c.referencedBy()));
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
