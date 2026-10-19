package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.MissingTypeExtractor;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T6-T: a nested type moved out to a top-level type of its own -- every reference still written {@code Owner.T}
 * is rewritten to {@code T}, in every file of the candidate, with the import added where the package differs.
 *
 * <p><b>The stall this ends.</b> Rule-extraction runs 2026-10-19_15-23-35 and 2026-10-19_16-19-07: the change moves
 * {@code EndpointSurfaceQuery.Endpoint} to a top-level {@code Endpoint}, so one candidate must update every user of
 * the old name at once. Each answer updated some of them; the build named one it had left out; the next answer
 * fixed that one and dropped another -- the failures rotated between three files for half an hour, and the first run
 * never left the rotation. The users are found by their text; the model need not remember them.
 *
 * <p><b>When.</b> javac could not find {@code class T} inside {@code class Owner} (the code wrote {@code Owner.T}),
 * the candidate declares {@code T} at the top level of a file of its own, and {@code Owner} no longer declares a
 * nested {@code T}. All three facts are the tree's; the re-probe judges the rewrite, as with every injector fix.
 */
@Slf4j
@Component
@Order(19)
public class MovedNestedTypeResolver implements CompileFixResolver {

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken, String stderr,
                                         CycleContextData context) {
        if (classification == null || classification.kind() != Kind.CANNOT_FIND_SYMBOL) return Optional.empty();
        if (stderr == null || stderr.isBlank() || context == null || context.outputPath() == null) return Optional.empty();
        List<MissingTypeExtractor.Entry> moved = MissingTypeExtractor.extract(stderr).stream()
                .filter(e -> e.insideClass() != null).toList();
        if (moved.isEmpty()) return Optional.empty();

        Map<String, String> sources = readSources(Path.of(context.outputPath()));
        for (MissingTypeExtractor.Entry e : moved) {
            String owner = e.insideClass().substring(e.insideClass().lastIndexOf('.') + 1);
            Map<String, String> rewritten = rewrite(sources, owner, e.typeName());
            if (rewritten.isEmpty()) continue;
            List<FileMutation> mutations = new ArrayList<>();
            rewritten.forEach((path, content) -> mutations.add(new FileMutation(path, content)));
            List<String> touched = new ArrayList<>(rewritten.keySet());
            InjectorFixRecord record = new InjectorFixRecord("moved-nested-type:" + owner + "." + e.typeName(),
                    Kind.CANNOT_FIND_SYMBOL, e.typeName(), touched,
                    "Rewrote " + owner + "." + e.typeName() + " to the top-level " + e.typeName() + " in "
                            + touched.size() + " file" + (touched.size() == 1 ? "" : "s"));
            log.info("CompileFixInjector: moved-nested-type -- {}.{} rewritten to the top-level {} in {} file(s)",
                    owner, e.typeName(), e.typeName(), touched.size());
            return Optional.of(new FixProposal(mutations, record));
        }
        return Optional.empty();
    }

    /**
     * The files of {@code sources} (path -> content) that write {@code owner.type}, rewritten to the top-level
     * {@code type}; empty unless {@code type} is declared at the top level of a file of its own and {@code owner}
     * no longer declares it nested.
     */
    static Map<String, String> rewrite(Map<String, String> sources, String owner, String type) {
        String topLevel = null;
        String ownerFile = null;
        for (Map.Entry<String, String> s : sources.entrySet()) {
            String name = fileName(s.getKey());
            if (name.equals(type + ".java") && SymbolImportResolver.definesType(s.getValue(), type)) topLevel = s.getKey();
            if (name.equals(owner + ".java")) ownerFile = s.getKey();
        }
        if (topLevel == null) return Map.of();
        if (ownerFile != null && SymbolImportResolver.definesType(sources.get(ownerFile), type)) return Map.of();
        String typePackage = packageOf(sources.get(topLevel));
        String fqn = typePackage.isEmpty() ? type : typePackage + "." + type;

        // Owner.T as written in code -- qualified or not, and its import -- never part of a longer name.
        Pattern qualified = Pattern.compile("(?<![\\w$])(?:[\\w$]+\\.)*" + Pattern.quote(owner) + "\\." + Pattern.quote(type)
                + "(?![\\w$])");
        Pattern importLine = Pattern.compile("(?m)^\\s*import\\s+(?:[\\w$]+\\.)*" + Pattern.quote(owner) + "\\."
                + Pattern.quote(type) + "\\s*;\\s*\\R?");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> s : sources.entrySet()) {
            if (!s.getKey().endsWith(".java")) continue;
            String content = s.getValue();
            if (content == null || !qualified.matcher(content).find()) continue;
            String changed = importLine.matcher(content).replaceAll("");
            changed = qualified.matcher(changed).replaceAll(Matcher.quoteReplacement(type));
            if (!typePackage.equals(packageOf(changed)) && !imports(changed, fqn)) {
                changed = SymbolImportResolver.insertImport(changed, fqn);
            }
            if (changed != null && !changed.equals(content)) out.put(s.getKey(), changed);
        }
        return out;
    }

    private static boolean imports(String content, String fqn) {
        return Pattern.compile("(?m)^\\s*import\\s+" + Pattern.quote(fqn) + "\\s*;").matcher(content).find();
    }

    private static String packageOf(String content) {
        if (content == null) return "";
        Matcher m = PACKAGE.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    private static String fileName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static Map<String, String> readSources(Path root) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(x -> x.toString().endsWith(".java")).toList()) {
                try {
                    out.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
                } catch (IOException ignored) {
                    // an unreadable file is left as it is
                }
            }
        } catch (IOException e) {
            log.debug("moved-nested-type: walk failed: {}", e.getMessage());
        }
        return out;
    }
}
