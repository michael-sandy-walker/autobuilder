package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.core.stuck.StuckLayer;
import com.walpro.autobuilder.core.stuck.StuckObservation;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-CQ: notices when the loop repeats the same failing move, and says so -- to the operator and to
 * the model.
 *
 * <p>The second adopted project spent twenty-five minutes on one move: a test called
 * {@code getChildren()} on a record that declares {@code children()}, the compile failed, the model
 * rewrote the protected record, the write was refused, and the next cycle did it again. The loop
 * saw it -- the repetition signal fired 72 times -- and told nobody: the signal only re-ranks agents,
 * and the recurring-defect tracker counts missing TYPES under two or more subgoals, by design. The
 * user: "Whenever there is a grind, which seems to be repeating itself (seemingly forever), it
 * should be noted."
 *
 * <p>A failure is reduced to a SIGNATURE -- what failed, in the build's own terms, without file
 * positions -- and each signature is counted at most once per cycle, so one cycle that meets the
 * same refusal at the merge and at the probe is one occurrence. A signature is noted at its third
 * occurrence and again each time its count doubles: a grind that goes on forever gets louder, not
 * quieter. Three is the smallest count that is a repetition of a repetition; the doubling keeps a
 * long grind from flooding the log while never letting it go silent.
 *
 * <p>T4-DY slice B: a REPORTER only. It used to add a paragraph to the evolution prompt -- "change the
 * approach, not the wording" -- which named the grind and supplied nothing; every grind in the archive
 * that outlived it was ended by a FACT instead. Each occurrence now goes to {@link StuckLayer}, which
 * asks what the code declares and delivers the answer; the paragraph is deleted.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "per-run failure-signature counts; a restored run starts counting afresh")
public class GrindDetector {

    static final int FIRST_NOTICE = 3;

    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Autowired(required = false)
    private StuckLayer stuckLayer;

    private final Map<String, Entry> bySignature = new LinkedHashMap<>();
    private String currentCycle;
    private final Set<String> seenThisCycle = new HashSet<>();

    private static final class Entry {
        int occurrences;
        String firstCycle;
        String lastCycle;
        int nextNotice = FIRST_NOTICE;
    }

    /** Marks the start of a cycle; occurrences are counted at most once per cycle. */
    public synchronized void beginCycle(String cycleId) {
        if (cycleId != null && !cycleId.equals(currentCycle)) {
            currentCycle = cycleId;
            seenThisCycle.clear();
        }
    }

    /** Records one build failure. {@code cycleId} may be null, then the current cycle is assumed. */
    public void observe(String cycleId, String signature) {
        observe(cycleId, StuckObservation.Source.BUILD, signature);
    }

    /** Records one failure the {@code source} detector saw. */
    public void observe(String cycleId, StuckObservation.Source source, String signature) {
        observe(cycleId, source, signature, List.of());
    }

    private void observe(String cycleId, StuckObservation.Source source, String signature, List<String> evidence) {
        if (signature == null || signature.isBlank()) return;
        // Every occurrence, before this detector's per-cycle count: the layer must also see one that
        // follows its own answer within the same cycle.
        if (stuckLayer != null) stuckLayer.report(source, signature, evidence);
        count(cycleId, signature);
    }

    private synchronized void count(String cycleId, String signature) {
        if (cycleId != null) beginCycle(cycleId);
        String cycle = currentCycle;
        if (!seenThisCycle.add(signature)) return;
        Entry e = bySignature.computeIfAbsent(signature, k -> new Entry());
        e.occurrences++;
        if (e.firstCycle == null) e.firstCycle = cycle;
        e.lastCycle = cycle;
        if (e.occurrences >= e.nextNotice) {
            e.nextNotice = e.occurrences * 2;
            log.warn("T4-CQ: GRIND -- the same failure has now happened in {} cycles (since {}): {}",
                    e.occurrences, e.firstCycle, signature);
            if (behaviourLogger != null) {
                behaviourLogger.grindDetected(cycle, signature, e.occurrences, e.firstCycle);
            }
        }
    }

    /**
     * Records every signature of one failed build outcome. A build that succeeds ends whatever was
     * grinding: without that, the prompt kept naming a grind for as long as no later build failed
     * (structdiff run 2 still warned about a getter error forty minutes after the last one).
     */
    public void observeBuild(String cycleId, BuildOutcome outcome) {
        observeBuild(cycleId, outcome, Map.of());
    }

    /**
     * As {@link #observeBuild(String, BuildOutcome)}, with the files the failed change proposed (path
     * relative to the project root -> content). They are rolled back before the failure is observed, so
     * this is the only moment the line the compiler pointed at can still be read: each signature
     * carries it, and the declarations the change gave the types that line constructs (T4-DY).
     */
    public void observeBuild(String cycleId, BuildOutcome outcome, Map<String, String> candidate) {
        if (outcome != null && outcome.result() == BuildResult.COMPILE_OK) {
            if (stuckLayer != null) stuckLayer.buildSucceeded(cycleId);
            return;
        }
        for (String s : signaturesOf(outcome, candidate == null ? java.util.Set.of() : candidate.keySet())) {
            if (stuckLayer != null) stuckLayer.rememberChange(s, candidate);
            observe(cycleId, StuckObservation.Source.BUILD, s, evidenceFor(s, outcome, candidate));
        }
    }

    private static final java.util.regex.Pattern ERROR_AT =
            java.util.regex.Pattern.compile("(\\S*?([A-Za-z0-9_]+\\.java)):\\[(\\d+),\\d+]\\s*(.+)$");
    private static final java.util.regex.Pattern CONSTRUCTED = java.util.regex.Pattern.compile("\\bnew\\s+([A-Z]\\w*)\\s*(?:<[^>]*>)?\\s*\\(");

    /**
     * For a signature naming a file, the failed change's own line at the position the compiler gave,
     * as {@code line=<text>}, and for each type that line constructs the constructors the change
     * declared for it, as {@code declared-by-change=<Type(...)>}. Empty when the change is not known
     * or does not hold that file.
     */
    static List<String> evidenceFor(String signature, BuildOutcome outcome, Map<String, String> candidate) {
        if (outcome == null || outcome.stderrTail() == null) return List.of();
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(outcome.stderrTail()).replaceAll("");
        String[] lines = plain.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            var m = ERROR_AT.matcher(line);
            if (!m.find()) continue;
            if (!signature.startsWith("the build fails: " + m.group(2) + ": " + m.group(4).strip())) continue;
            // T4-DZ: the compiler's own block for this error, positions and all, for the observer.
            StringBuilder block = new StringBuilder(line.replaceFirst("^\\[ERROR]\\s*", "").strip());
            for (int j = i + 1; j < Math.min(lines.length, i + 5); j++) {
                String next = lines[j].replaceFirst("^\\[ERROR]", "");
                if (!next.startsWith("  ") || ERROR_AT.matcher(next).find()) break;
                block.append('\n').append(next.strip());
            }
            if (candidate == null || candidate.isEmpty()) return List.of("compiler=" + block);
            String path = m.group(1).replace('\\', '/');
            for (Map.Entry<String, String> f : candidate.entrySet()) {
                String key = f.getKey().replace('\\', '/');
                if (f.getValue() == null || !(path.endsWith("/" + key) || path.equals(key))) continue;
                String[] src = f.getValue().split("\\R", -1);
                int n = Integer.parseInt(m.group(3));
                if (n < 1 || n > src.length) return List.of("compiler=" + block);
                List<String> out = new ArrayList<>();
                out.add("compiler=" + block);
                out.add("line=" + src[n - 1].strip());
                var c = CONSTRUCTED.matcher(src[n - 1]);
                Set<String> types = new java.util.LinkedHashSet<>();
                while (c.find()) types.add(c.group(1));
                for (String type : types) {
                    for (String content : candidate.values()) {
                        List<String> declared = content == null ? List.of() : StuckQuestions.constructorsIn(content, type);
                        if (declared.isEmpty()) continue;
                        declared.forEach(d -> out.add("declared-by-change=" + d));
                        break;
                    }
                    out.add("constructs=" + type);
                }
                return out;
            }
            return List.of("compiler=" + block);
        }
        return List.of();
    }

    /**
     * What a failed build failed ON, in the compiler's own terms and without positions: a member
     * missing from a named type where the diagnostic says so, otherwise the diagnostic's message.
     * Several distinct ones per build, since one move often breaks several things at once.
     */
    static List<String> signaturesOf(BuildOutcome outcome) {
        return signaturesOf(outcome, java.util.Set.of());
    }

    /**
     * As {@link #signaturesOf(BuildOutcome)}, knowing which files the failed change wrote ({@code changed}, paths
     * relative to the project root): when the compiler reports errors in those, its errors in files the change did
     * not write are consequences of them and are set aside. When every error is in a file the change did not write
     * -- it removed something others call -- they are what the change broke, and are kept.
     *
     * <p>T6-T, ladder-grind run 2026-10-10_19-22-18: one real error stopped annotation processing, and every class
     * that reads a generated member reported it missing. "FileWatcherService.java: cannot find symbol variable log"
     * and its like became stuck cases of their own, were asked about, and their answers took the prompt room and
     * the five places per build that the real errors needed.
     */
    static List<String> signaturesOf(BuildOutcome outcome, java.util.Collection<String> changed) {
        List<String> out = new ArrayList<>();
        if (outcome == null || outcome.result() != BuildResult.COMPILE_ERROR || outcome.stderrTail() == null) {
            return out;
        }
        for (MissingMemberExtractor.Entry e : MissingMemberExtractor.extract(outcome.stderrTail())) {
            String member = e.kind() == MissingMemberExtractor.Kind.CONSTRUCTOR
                    ? "constructor " + e.targetType() + "(" + e.argTypes() + ")"
                    : e.memberName() + "(" + e.argTypes() + ")";
            String s = "the build fails: " + e.targetType() + " has no " + member;
            if (!out.contains(s)) out.add(s);
        }
        if (!out.isEmpty()) return out;
        // Cause first: what the change's own files report, the rest set aside (CompileErrorCauses, the one home of it).
        String[] lines = CompileErrorCauses.of(outcome.stderrTail(), changed).causes().split("\\R");
        for (int i = 0; i < lines.length; i++) {
            // "[ERROR] /abs/path/File.java:[12,5] message" -> "File.java: message"
            var m = java.util.regex.Pattern.compile("([A-Za-z0-9_]+\\.java):\\[\\d+,\\d+]\\s*(.+)$").matcher(lines[i]);
            if (!m.find()) continue;
            String message = m.group(2).strip();
            if (message.equals("cannot find symbol")) message += symbolAndLocation(lines, i + 1);
            String s = "the build fails: " + m.group(1) + ": " + message;
            if (!out.contains(s)) out.add(s);
        }
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    /**
     * The {@code symbol:} and {@code location:} lines javac prints under "cannot find symbol", as
     * " variable subGoalId in class ChannelRequest", packages dropped. Without them the signature named
     * only the file, and the question it poses ("where is this name declared?") could not be asked:
     * three of the seven grinds that outlived every reaction in the archive were such signatures
     * (T4-DY slice A, 2026-09-26). Empty when the lines are not there.
     */
    static String symbolAndLocation(String[] lines, int from) {
        String symbol = null, location = null;
        for (int j = from; j < Math.min(lines.length, from + 3); j++) {
            var sm = java.util.regex.Pattern.compile("symbol:\\s*(.+)$").matcher(lines[j]);
            var lm = java.util.regex.Pattern.compile("location:\\s*(.+)$").matcher(lines[j]);
            if (symbol == null && sm.find()) symbol = sm.group(1).strip();
            else if (location == null && lm.find()) location = lm.group(1).strip();
        }
        if (symbol == null) return "";
        String out = " " + symbol + (location == null ? "" : " in " + location);
        return out.replaceAll("\\b(?:[a-z_][\\w]*\\.)+([A-Z][\\w$]*)", "$1");
    }

    /** Signatures and their occurrence counts, for tests and the run report. */
    public synchronized Map<String, Integer> occurrences() {
        Map<String, Integer> out = new LinkedHashMap<>();
        bySignature.forEach((k, v) -> out.put(k, v.occurrences));
        return out;
    }
}
