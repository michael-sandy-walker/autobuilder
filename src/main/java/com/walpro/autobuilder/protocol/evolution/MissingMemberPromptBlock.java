package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.MissingMemberExtractor;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-BB: formatter for the {@code --- MISSING MEMBER — ADD TO <T>.java ---} evolution prompt
 * block. Steering-only variant of T4-BB: when the previous cycle's compile probe reported a
 * call to a constructor or method that does not exist on an already-defined type, surface the
 * gap so the LLM edits the callee (where the member should live) instead of the caller (where
 * it tried to use the member). Mirrors {@link PackageFragmentationPromptBlock} in shape; reads
 * the probe stderr the same way {@link EvolutionProtocolContract#extractMissingSymbols} does.
 *
 * <p>Target-type-to-file resolution: each entry's simple type name is matched against
 * {@code previousOutput()} key paths ending in {@code "/<T>.java"}. Entries whose target type
 * is not present in {@code output/} are dropped — the sibling resolver families (T4-AY for
 * missing classes, T4-AZ for missing imports) handle those.
 */
public final class MissingMemberPromptBlock {

    private MissingMemberPromptBlock() {}

    public static String render(CycleContextData ctx) {
        if (ctx == null) return null;

        // T4-BD: read the typed probe outcome instead of string-matching feedbackMessage.
        BuildOutcome probe = ctx.lastProbeOutcome();
        if (probe == null
                || probe.result() != BuildResult.COMPILE_ERROR
                || probe.stderrTail() == null) {
            return null;
        }

        List<MissingMemberExtractor.Entry> entries = MissingMemberExtractor.extract(probe.stderrTail());
        if (entries.isEmpty()) return null;

        Map<String, String> previousOutput = ctx.previousOutput();
        if (previousOutput == null || previousOutput.isEmpty()) return null;

        // Group entries by the resolved target file. An entry is kept only if its target type
        // resolves to a file already in output/ — sibling resolver families own the missing-
        // class / missing-import cases.
        Map<String, List<MissingMemberExtractor.Entry>> byFile = new LinkedHashMap<>();
        for (MissingMemberExtractor.Entry e : entries) {
            String targetPath = resolveTargetFile(e.targetType(), previousOutput);
            if (targetPath == null) continue;
            byFile.computeIfAbsent(targetPath, k -> new java.util.ArrayList<>()).add(e);
        }

        if (byFile.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<MissingMemberExtractor.Entry>> grp : byFile.entrySet()) {
            String path = grp.getKey();
            List<MissingMemberExtractor.Entry> es = grp.getValue();
            String simpleType = es.get(0).targetType();
            sb.append("--- MISSING MEMBER — ADD TO ").append(simpleType).append(".java ---\n");
            sb.append("Previous compile probe failed because the following ")
              .append(es.size() == 1 ? "member" : "members")
              .append(" do not exist on ").append(simpleType).append(", but ")
              .append(simpleType).append(" itself is already defined at ").append(path)
              .append(". Edit ").append(path).append(" and add the missing ")
              .append(es.size() == 1 ? "member" : "members").append(" — do NOT change the caller(s) ")
              .append("to work around the gap.\n");
            for (MissingMemberExtractor.Entry e : es) {
                if (e.kind() == MissingMemberExtractor.Kind.CONSTRUCTOR) {
                    sb.append("  - constructor ").append(simpleType).append("(").append(e.argTypes()).append(")\n");
                } else {
                    sb.append("  - method ").append(e.memberName()).append("(").append(e.argTypes()).append(")\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String resolveTargetFile(String simpleType, Map<String, String> previousOutput) {
        String suffix = "/" + simpleType + ".java";
        for (String path : previousOutput.keySet()) {
            if (path.endsWith(suffix) || path.equals(simpleType + ".java")) return path;
        }
        return null;
    }
}
