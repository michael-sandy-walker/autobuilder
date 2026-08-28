package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.MissingMemberExtractor;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DF: repairs a call that named the right member on the wrong type.
 *
 * <p>The gap this fills was empty, not merely weak. {@link SymbolImportResolver} declines
 * member-shaped tokens outright ({@code !Character.isUpperCase} → empty, T4-BM step 6b) because
 * no {@code import} can fix a missing method, so nothing deterministic fired and the diagnostic
 * fell through to the LLM build-fix path. Its only available move is to add a dependency — which
 * on the field corpus was already declared, four times over.
 *
 * <p>Runs ahead of {@link SymbolImportResolver}: "the classpath already declares this, on that
 * type" is both cheaper and more certain than "some library might supply it".
 *
 * <p><b>Scope, and why it is this narrow.</b> Only a member addressed on a bare TYPE is
 * retargetable. When the diagnostic reads {@code location: variable v of type T} the receiver is
 * a value, and a value has the type it has — swapping the type named at the call site would be a
 * different program, not a repair. On the corpus that distinction is exactly the line between the
 * cases this fixes ({@code location: class org.lwjgl.opengl.GL11}) and the ones it must leave
 * alone ({@code location: variable projectionMatrix of type org.joml.Matrix4f}, where the member
 * genuinely does not exist anywhere and the model had simply invented it).
 *
 * <p>Types the project itself defines are left to T4-BQ — for those the repair is to add the
 * member, which is what {@code MissingMemberPromptBlock} already asks the model to do.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class MisplacedMemberResolver implements CompileFixResolver {

    private final ClasspathMemberDeclarationService memberDeclarations;

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken,
                                         String stderr, CycleContextData context) {
        if (classification == null || classification.kind() != Kind.CANNOT_FIND_SYMBOL) return Optional.empty();
        if (stderr == null || stderr.isBlank()) return Optional.empty();
        if (context == null || context.outputPath() == null) return Optional.empty();

        Map<String, String> sources = context.previousOutput();
        if (sources == null || sources.isEmpty()) return Optional.empty();

        // The classifier names ONE token and a build reports many errors, so the entries are read
        // from the stderr directly — the SPI blesses this for detail the Classification does not
        // carry.
        List<MissingMemberExtractor.Entry> entries = MissingMemberExtractor.extract(stderr);
        if (entries.isEmpty()) return Optional.empty();

        Path projectRoot = Path.of(context.outputPath());
        Map<String, String> rewritten = new LinkedHashMap<>();
        List<String> summaries = new ArrayList<>();
        String firstMember = null;

        for (MissingMemberExtractor.Entry entry : entries) {
            if (!entry.bareType()) continue;
            if (entry.kind() != MissingMemberExtractor.Kind.METHOD) continue;
            if (definedByProject(entry.targetType(), sources)) continue;      // T4-BQ owns these

            String receiverFqn = receiverFqn(entry, sources);
            if (receiverFqn == null) {
                log.info("T4-DF: {}#{} — no import settles the receiver's package; abstaining",
                        entry.targetType(), entry.memberName());
                continue;
            }

            Optional<ClasspathMemberDeclarationService.Declaration> declaration =
                    memberDeclarations.declaringSibling(receiverFqn, entry.memberName(), projectRoot);
            if (declaration.isEmpty()) continue;

            String declaringFqn = declaration.get().declaringType();
            String declaringSimple = simpleName(declaringFqn);
            int touched = retarget(entry.targetType(), declaringSimple, declaringFqn,
                    entry.memberName(), sources, rewritten);
            if (touched == 0) continue;

            if (firstMember == null) firstMember = entry.memberName();
            summaries.add(entry.targetType() + "." + entry.memberName()
                    + " -> " + declaringSimple + "." + entry.memberName());
        }

        if (rewritten.isEmpty()) return Optional.empty();

        List<FileMutation> mutations = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, String> file : rewritten.entrySet()) {
            mutations.add(new FileMutation(file.getKey(), file.getValue()));
            paths.add(file.getKey());
        }
        String description = "Retargeted " + String.join(", ", summaries)
                + " (declared on the resolved classpath, not on the type the call named)";
        InjectorFixRecord record = new InjectorFixRecord(
                "misplaced-member:" + firstMember, Kind.CANNOT_FIND_SYMBOL, firstMember, paths, description);
        log.info("CompileFixInjector: misplaced-member — {} (touched {})", description, paths);
        return Optional.of(new FixProposal(mutations, record));
    }

    /**
     * The receiver's fully-qualified name. javac writes it out only when it has to — a type the
     * file imports is reported by simple name alone ({@code location: class GL11}), and both
     * shapes occur in the same run's logs. When the package is missing the project's own imports
     * supply it, which is the same reasoning T4-BM applies to type-level discovery: if the project
     * imports {@code a.b.C}, that IS its resolution of {@code C}.
     *
     * <p>Returns null when nothing settles it — an on-demand import, no import at all, or two
     * files importing different types of that name. Guessing the package would defeat the point
     * of a lookup that is supposed to be exact.
     */
    private static String receiverFqn(MissingMemberExtractor.Entry entry, Map<String, String> sources) {
        if (entry.targetTypeFqn() != null) return entry.targetTypeFqn();
        Pattern importOfReceiver = Pattern.compile(
                "(?m)^\\s*import\\s+(?!static\\b)([\\w.]+\\." + Pattern.quote(entry.targetType()) + ")\\s*;");
        String found = null;
        for (String content : sources.values()) {
            if (content == null) continue;
            Matcher m = importOfReceiver.matcher(content);
            while (m.find()) {
                if (found == null) found = m.group(1);
                else if (!found.equals(m.group(1))) return null;   // the project disagrees with itself
            }
        }
        return found;
    }

    /**
     * Rewrites every call of {@code <receiver>.<member>} to name the declaring type instead, in
     * every source that makes one, and imports the declaring type where the file needs it.
     * Returns the number of files changed.
     */
    private static int retarget(String receiverSimple, String declaringSimple, String declaringFqn,
                                String memberName, Map<String, String> sources,
                                Map<String, String> rewritten) {
        // Matches the receiver named plainly or fully qualified, with the member following. The
        // member name is part of the pattern so other calls on the same type — which resolve
        // perfectly well — are left untouched.
        Pattern call = Pattern.compile(
                "(?:[\\w.]+\\.)?\\b" + Pattern.quote(receiverSimple) + "\\s*\\.\\s*"
                        + Pattern.quote(memberName) + "\\b");
        int changed = 0;
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String path = source.getKey();
            if (!path.endsWith(".java")) continue;
            String content = rewritten.getOrDefault(path, source.getValue());
            if (content == null) continue;
            Matcher m = call.matcher(content);
            if (!m.find()) continue;
            String updated = m.replaceAll(Matcher.quoteReplacement(declaringSimple + "." + memberName));
            updated = ensureImport(updated, declaringFqn, declaringSimple);
            if (updated.equals(content)) continue;
            rewritten.put(path, updated);
            changed++;
        }
        return changed;
    }

    /**
     * Adds {@code import <fqn>;} unless the file can already see the type. An on-demand import of
     * the same package covers it, and so does an existing single-type import; a duplicate import
     * is a compile error, so this check is not cosmetic.
     */
    static String ensureImport(String content, String fqn, String simpleName) {
        String packageName = fqn.substring(0, fqn.lastIndexOf('.'));
        if (content.contains("import " + fqn + ";")) return content;
        if (content.contains("import " + packageName + ".*;")) return content;
        // A different type of the same simple name already imported: retargeting the call would
        // bind to the wrong one, so leave the file alone and let the build report it plainly.
        Pattern conflicting = Pattern.compile("^\\s*import\\s+(?!static\\b)([\\w.]+)\\." + Pattern.quote(simpleName) + "\\s*;",
                Pattern.MULTILINE);
        Matcher conflict = conflicting.matcher(content);
        if (conflict.find() && !conflict.group(1).equals(packageName)) return content;

        String statement = "import " + fqn + ";";
        Matcher lastImport = Pattern.compile("(?m)^\\s*import\\s+[^;]+;").matcher(content);
        int insertAt = -1;
        while (lastImport.find()) insertAt = lastImport.end();
        if (insertAt >= 0) {
            return content.substring(0, insertAt) + "\n" + statement + content.substring(insertAt);
        }
        Matcher packageLine = Pattern.compile("(?m)^\\s*package\\s+[^;]+;").matcher(content);
        if (packageLine.find()) {
            return content.substring(0, packageLine.end()) + "\n\n" + statement + content.substring(packageLine.end());
        }
        return statement + "\n" + content;
    }

    private static boolean definedByProject(String simpleType, Map<String, String> sources) {
        String suffix = "/" + simpleType + ".java";
        for (String path : sources.keySet()) {
            if (path.endsWith(suffix) || path.equals(simpleType + ".java")) return true;
        }
        return false;
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
