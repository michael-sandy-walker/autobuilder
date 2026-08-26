package com.walpro.autobuilder.analysis.structure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DD: the command surface a specification declares — the terminating artifact's equivalent of
 * {@link EndpointSurfaceQuery#declaredInSpec}, and seam 2 for a target that has no network.
 *
 * <p>The gap it closes, measured on run {@code 2026-08-26_11-02-00}: the loop authored 30 criteria
 * and its verifier executed NONE, because criteria name a COMMAND WORD and never a full
 * invocation — <i>"check exits 0 when every row is well formed"</i>. Without a declared surface
 * there is no way to know that {@code check} is a command rather than an English verb, so every
 * such criterion abstained and a fulfilled run shipped a defective artifact unchallenged.
 *
 * <p><b>What a declaration looks like, stated without naming an ecosystem:</b> a specification
 * introduces an operation by naming it at the start of its own description, followed by that
 * operation's parameters. The parameters are what give ARITY, which is what makes
 * <i>"the wrong number of arguments"</i> a runnable exercise rather than a sentence.
 *
 * <p>Self-gating, on the same terms as its sibling: a specification declaring no operations
 * yields an empty surface and every consumer holds no opinion. Nothing here knows what any
 * particular operation MEANS.
 */
@Slf4j
@Component
public class CommandSurfaceQuery {

    /**
     * One declared operation and the number of arguments it takes, not counting the word itself.
     * {@code positional} is the parameter names in order, so a consumer can tell which argument is
     * which without re-reading the prose.
     */
    public record Command(String word, List<String> positional) {
        public int arity() {
            return positional.size();
        }
    }

    /**
     * An operation declaration: the word, then its parameters, then a separator introducing the
     * description. Parameters are written as placeholders, which is what distinguishes a
     * declaration from a sentence that merely mentions the word.
     *
     * <p>The separator is required. Without it, <i>"check exits 0 when every row is well formed"</i>
     * — a criterion, not a declaration — would read as an operation taking zero arguments, and the
     * surface would then contain whatever words the prose happened to start a line with.
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^\\s*([a-z][a-z0-9_-]*)((?:\\s+<[^>\\n]+>)+)\\s*[—:-]\\s+");

    private static final Pattern PLACEHOLDER = Pattern.compile("<([^>]+)>");

    /**
     * The operations this specification declares, keyed by word, in declaration order. Empty when
     * the specification declares none — which is every web-service specification this project has
     * driven, so the query is silent there rather than guessing.
     */
    public Map<String, Command> declaredInSpec(String specText) {
        Map<String, Command> declared = new LinkedHashMap<>();
        if (specText == null || specText.isBlank()) return declared;
        Matcher m = DECLARATION.matcher(specText);
        while (m.find()) {
            String word = m.group(1);
            List<String> positional = new ArrayList<>();
            Matcher pm = PLACEHOLDER.matcher(m.group(2));
            while (pm.find()) positional.add(pm.group(1).trim());
            declared.putIfAbsent(word, new Command(word, List.copyOf(positional)));
        }
        return declared;
    }

    /** Whether this specification asks for something invoked by an operation word. */
    public boolean declaresCommandSurface(String specText) {
        return !declaredInSpec(specText).isEmpty();
    }

    /**
     * A word that is NOT one of the declared operations, for exercising "an unrecognised command".
     * Derived by extending a declared word until it collides with nothing, so the result cannot
     * accidentally BE a command however the surface is spelled — and empty when nothing is
     * declared, because "unrecognised" has no meaning against an unknown surface.
     */
    public Optional<String> unrecognisedWord(String specText) {
        Map<String, Command> declared = declaredInSpec(specText);
        if (declared.isEmpty()) return Optional.empty();
        String candidate = declared.keySet().iterator().next() + "x";
        while (declared.containsKey(candidate)) candidate = candidate + "x";
        return Optional.of(candidate);
    }
}
