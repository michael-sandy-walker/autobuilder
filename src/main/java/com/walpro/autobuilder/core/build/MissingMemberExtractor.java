package com.walpro.autobuilder.core.build;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-BB: parses Maven compile stderr into the set of missing-member entries the next mutation
 * must add to existing source files. Sister extractor to
 * {@link com.walpro.autobuilder.core.mutation.BlockerExtractor} (which reports missing
 * whole-class / package symbols).
 *
 * <p>Recognised shapes:
 * <ul>
 *   <li><b>Missing constructor</b> — {@code no suitable constructor found for T(argTypes)}.
 *       Single-line; the target type is in the message itself.</li>
 *   <li><b>Missing method</b> — {@code cannot find symbol} followed by
 *       {@code symbol: method m(argTypes)} and a {@code location:} line naming the owning
 *       type — {@code class T}, {@code interface T}, or {@code variable v of type T}.
 *       Multi-line; the target type is on the {@code location:} line.</li>
 * </ul>
 *
 * <p>Each entry names the simple type that should grow a new member, the member kind, the
 * member name (constructor name = type name), and the argument-type list verbatim. The
 * {@code MissingMemberPromptBlock} resolves the simple type to a file path in
 * {@code previousOutput()} and surfaces the gap to the evolution LLM.
 *
 * <p>Out of scope: ambiguous calls, overload mismatches that the compiler reports under a
 * different message shape, and member-access errors on third-party types (the
 * {@code definedSymbolsInOutput()} resolver check in the prompt block filters those).
 */
public final class MissingMemberExtractor {

    public enum Kind { CONSTRUCTOR, METHOD }

    public record Entry(Kind kind, String targetType, String memberName, String argTypes) {}

    /**
     * T2-FD: a call that chained a container method onto a bare project type — the caller treated
     * an unwrapped value as if it were the container a method had returned. {@link #extract} drops
     * these deliberately (T1-FQ: adding the member to the project type is always the wrong repair,
     * and doing so merged a {@code Book.isPresent()} into an artifact), so they are surfaced here
     * instead. One recogniser, two consumers — the suppression and the steering block.
     */
    public record ContainerCall(String targetType, String memberName, String referencedBy) {}

    private static final Pattern CONSTRUCTOR_LINE = Pattern.compile(
            "no suitable constructor found for\\s+([\\w.]+)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ERROR_FILE_LINE = Pattern.compile("(\\S+\\.java):\\[?\\d+");

    private static final Pattern METHOD_SYMBOL_LINE = Pattern.compile(
            "symbol:\\s*method\\s+(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    // javac names the owning type three ways. `class`/`interface` appear when the call is on a
    // type reference; `variable <name> of type <T>` appears when it is on a variable — which is
    // what an injected service or repository call looks like, and is 89% of the real corpus (806
    // of 905 diagnostics over 27 runs, measured 2026-08-02). Matching only the first two made
    // this extractor blind to the shape it exists for.
    private static final Pattern METHOD_LOCATION_LINE = Pattern.compile(
            "location:\\s*(?:class|interface|(?:variable|value)\\s+\\w+\\s+of\\s+type)\\s+([\\w.]+)",
            Pattern.CASE_INSENSITIVE);

    // Whether the owning type was named through a NAMED RECEIVER or as a bare type. The two are
    // different populations, measured over the run corpora on 2026-08-02: of 810 named-receiver
    // diagnostics, 810 name a real member of the project's own type; of 99 bare-type ones, 70
    // (71%) name a method of a JDK container, because `repo.findById(id).orElseThrow()` reports
    // the element type with no receiver to point at.
    private static final Pattern LOCATION_NAMED_RECEIVER = Pattern.compile(
            "location:\\s*(?:variable|value)\\s+\\w+\\s+of\\s+type\\s+([\\w.]+)",
            Pattern.CASE_INSENSITIVE);

    // Language-core container types. Their MEMBER names are read from the running JDK rather than
    // listed here — the point is that no edit to a project type can make `orElseThrow` resolve on
    // it, so proposing one is always the wrong repair.
    private static final List<String> CONTAINER_TYPES = List.of(
            "java.util.Optional", "java.util.stream.Stream", "java.util.Collection",
            "java.util.Map", "java.util.Iterator");

    private static volatile Set<String> containerMembers;

    private static Set<String> containerMembers() {
        Set<String> local = containerMembers;
        if (local != null) return local;
        Set<String> names = new HashSet<>();
        for (String type : CONTAINER_TYPES) {
            try {
                for (Method m : Class.forName(type).getMethods()) {
                    names.add(m.getName());
                }
            } catch (Throwable ignored) {
                // A platform type that will not load simply contributes nothing; abstaining from
                // the suppression is the safe direction (it restores today's behaviour).
            }
        }
        containerMembers = names;
        return names;
    }

    private MissingMemberExtractor() {}

    /**
     * T2-FD: the calls {@link #extract} suppresses, in order of first appearance. The repair for
     * each is at the CALL SITE or in the callee's return type — never a new member on
     * {@link ContainerCall#targetType()}.
     */
    public static List<ContainerCall> unwrappedContainerCalls(String stderr) {
        if (stderr == null || stderr.isBlank()) return List.of();
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");
        Map<String, ContainerCall> dedup = new LinkedHashMap<>();
        String[] lines = plain.split("\\R");
        String currentFile = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher fm = ERROR_FILE_LINE.matcher(lines[i]);
            if (fm.find()) currentFile = fm.group(1);
            Matcher sm = METHOD_SYMBOL_LINE.matcher(lines[i]);
            if (!sm.find()) continue;
            String methodName = sm.group(1);
            if (!containerMembers().contains(methodName)) continue;
            for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                Matcher lm = METHOD_LOCATION_LINE.matcher(lines[j]);
                if (!lm.find()) continue;
                if (LOCATION_NAMED_RECEIVER.matcher(lines[j]).find()) break;   // a real receiver
                String target = lastSegment(lm.group(1));
                dedup.putIfAbsent(target + "|" + methodName,
                        new ContainerCall(target, methodName, currentFile));
                break;
            }
        }
        return new ArrayList<>(dedup.values());
    }

    /**
     * Extract every distinct missing-member entry named in the stderr, in order of first
     * appearance. Returns an empty list when stderr is blank or no member-shaped lines are
     * present. Deduplication key is {@code (kind, targetType, memberName, argTypes)}.
     */
    public static List<Entry> extract(String stderr) {
        if (stderr == null || stderr.isBlank()) return List.of();
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");

        Map<String, Entry> dedup = new LinkedHashMap<>();

        Matcher cm = CONSTRUCTOR_LINE.matcher(plain);
        while (cm.find()) {
            String fqOrSimple = cm.group(1);
            String simple = lastSegment(fqOrSimple);
            String args = normaliseArgs(cm.group(2));
            Entry entry = new Entry(Kind.CONSTRUCTOR, simple, simple, args);
            dedup.putIfAbsent(key(entry), entry);
        }

        // Method shapes pair a `symbol: method m(args)` line with a following
        // `location: class T` line. Walk the plaintext linearly so each symbol/location pair
        // stays associated. A symbol line without a matching location line is skipped — the
        // single-class-resolver path (T4-AZ) handles bare missing symbols.
        String[] lines = plain.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher sm = METHOD_SYMBOL_LINE.matcher(lines[i]);
            if (!sm.find()) continue;
            String methodName = sm.group(1);
            String args = normaliseArgs(sm.group(2));
            String target = null;
            boolean namedReceiver = false;
            for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                Matcher lm = METHOD_LOCATION_LINE.matcher(lines[j]);
                if (lm.find()) {
                    target = lastSegment(lm.group(1));
                    namedReceiver = LOCATION_NAMED_RECEIVER.matcher(lines[j]).find();
                    break;
                }
            }
            if (target == null) continue;
            // A container method reported against a bare type is a receiver mistake, not a gap in
            // the project's type: the caller chained onto an unwrapped value. Steering the model to
            // ADD the member is actively wrong, and it has merged — run 2026-07-30_18-39-00 shipped
            // a `Book` carrying `public boolean isPresent()` because this block asked for it and
            // told the model not to touch the caller. A named receiver never showed this shape, so
            // the suppression is scoped to the bare-type case and leaves the common path alone.
            if (!namedReceiver && containerMembers().contains(methodName)) continue;
            Entry entry = new Entry(Kind.METHOD, target, methodName, args);
            dedup.putIfAbsent(key(entry), entry);
        }

        return new ArrayList<>(dedup.values());
    }

    private static String lastSegment(String fq) {
        int dot = fq.lastIndexOf('.');
        return dot < 0 ? fq : fq.substring(dot + 1);
    }

    private static String normaliseArgs(String raw) {
        if (raw == null) return "";
        // Collapse runs of whitespace, then drop the spaces AROUND separators — but never the
        // space between two tokens. Stripping all whitespace glued an annotation to the type it
        // annotates: run 2026-08-02_12-43-31 rendered
        // `createLoan(@jakarta.validation.Validcom.example.demo.model.Loan)` into the evolution
        // prompt, naming a type that does not exist. Latent until T1-FQ let the variable-shaped
        // diagnostic reach the renderer for the first time.
        return raw.replaceAll("\\s+", " ")
                  .replaceAll("\\s*,\\s*", ",")
                  .trim();
    }

    private static String key(Entry e) {
        return e.kind() + "|" + e.targetType() + "|" + e.memberName() + "|" + e.argTypes();
    }
}
