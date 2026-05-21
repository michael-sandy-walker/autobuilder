package com.walpro.autobuilder.core.build.inject;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-AZ: resolves {@link Kind#CANNOT_FIND_SYMBOL} by adding the missing import (and, when a Maven
 * artifact is named, the missing dependency) instead of fabricating a stub class. Sits between
 * {@link KnownStarterResolver} (package-level fixes) and {@link IdiomSkeletonResolver}
 * (skeleton-file fixes) in the {@link CompileFixResolver} chain.
 *
 * <p>Two paths, one resolver:
 * <ol>
 *   <li>If the missing token already names a class on disk under {@code output/} but in a
 *       different package, emit a local cross-package import (no GAV).</li>
 *   <li>Otherwise consult {@link SymbolImportLookupService}; on a hit, emit
 *       {@code import <fqn>;} in every output file that references the bare name, and append
 *       the artifact to {@code pom.xml} if absent.</li>
 * </ol>
 * If neither path produces an edit, the resolver returns empty so the chain falls through to
 * {@link IdiomSkeletonResolver}.
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class SymbolImportResolver implements CompileFixResolver {

    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String OUTPUT_DIR = "output";
    private static final String POM_PATH = "pom.xml";

    private final FileService fileService;
    private final SymbolImportLookupService lookupService;
    private final SymbolGroundingService groundingService;

    @Override
    public Optional<FixProposal> resolve(Kind kind, String missingToken, CycleContextData context) {
        if (kind != Kind.CANNOT_FIND_SYMBOL) return Optional.empty();
        if (missingToken == null || missingToken.isBlank()) return Optional.empty();
        if (context == null || context.outputPath() == null) return Optional.empty();
        if (!isSimpleIdentifier(missingToken)) return Optional.empty();

        Optional<FixProposal> local = tryLocalCrossPackageImport(missingToken);
        if (local.isPresent()) return local;

        Optional<Resolution> resolution = lookupService.lookup(missingToken);
        if (resolution.isEmpty()) return Optional.empty();

        return buildExternalFixProposal(missingToken, resolution.get(), context);
    }

    private Optional<FixProposal> tryLocalCrossPackageImport(String missingToken) {
        // If a user class with this simple name already exists somewhere under output/, this is a
        // cross-package import case — not a missing-framework-symbol case. Add the import locally
        // and let the LLM keep ownership of the broader code.
        Set<String> defined = groundingService.definedSymbolsInOutput();
        if (!defined.contains(missingToken)) return Optional.empty();

        // T2-DC populates `defined` with BOTH the simple name and the FQN of every type under
        // output/, so we can locate the owner FQN with a suffix lookup instead of a second file walk.
        String suffix = "." + missingToken;
        Optional<String> ownerFqn = defined.stream()
                .filter(s -> s.endsWith(suffix) && s.length() > missingToken.length())
                .findFirst();
        if (ownerFqn.isEmpty()) return Optional.empty();

        List<ReferencingFile> referencingFiles = findReferencingFiles(missingToken, ownerFqn.get());
        if (referencingFiles.isEmpty()) return Optional.empty();

        List<FileMutation> mutations = new ArrayList<>();
        List<String> touched = new ArrayList<>();
        for (ReferencingFile ref : referencingFiles) {
            String patched = insertImport(ref.content, ownerFqn.get());
            if (patched == null) continue;
            mutations.add(new FileMutation(ref.relPath, patched));
            touched.add(ref.relPath);
        }
        if (mutations.isEmpty()) return Optional.empty();

        InjectorFixRecord record = new InjectorFixRecord(
                "symbol-import:local",
                Kind.CANNOT_FIND_SYMBOL,
                missingToken,
                touched,
                "Added local import " + ownerFqn.get() + " to " + touched.size()
                        + " file" + (touched.size() == 1 ? "" : "s")
        );
        log.info("CompileFixInjector: symbol-import:local — added import {} to {} file(s)",
                ownerFqn.get(), touched.size());
        return Optional.of(new FixProposal(mutations, record));
    }

    private Optional<FixProposal> buildExternalFixProposal(String missingToken, Resolution resolution, CycleContextData context) {
        List<ReferencingFile> referencingFiles = findReferencingFiles(missingToken, resolution.fqn());
        List<FileMutation> mutations = new ArrayList<>();
        List<String> touched = new ArrayList<>();
        for (ReferencingFile ref : referencingFiles) {
            String patched = insertImport(ref.content, resolution.fqn());
            if (patched == null) continue;
            mutations.add(new FileMutation(ref.relPath, patched));
            touched.add(ref.relPath);
        }

        Optional<FileMutation> pomMutation = maybeAddDependency(resolution, context);
        pomMutation.ifPresent(m -> {
            mutations.add(m);
            touched.add(POM_PATH);
        });

        if (mutations.isEmpty()) {
            // Already imported and dep already present (or no referencing file found) — nothing
            // useful to inject. Defer to LLM rather than no-op the cycle.
            return Optional.empty();
        }

        String ruleSuffix = resolution.hasGav() ? resolution.artifactId() : "import-only";
        String rule = "symbol-import:" + ruleSuffix;
        String description = "Added import " + resolution.fqn()
                + (pomMutation.isPresent()
                        ? " + dependency " + resolution.groupId() + ":" + resolution.artifactId()
                        : "");
        InjectorFixRecord record = new InjectorFixRecord(
                rule,
                Kind.CANNOT_FIND_SYMBOL,
                missingToken,
                touched,
                description
        );
        log.info("CompileFixInjector: {} — {} (touched {})", rule, description, touched);
        return Optional.of(new FixProposal(mutations, record));
    }

    private Optional<FileMutation> maybeAddDependency(Resolution resolution, CycleContextData context) {
        if (!resolution.hasGav()) return Optional.empty();
        String pomRel = context.outputPath() + "/" + POM_PATH;
        try {
            if (!fileService.exists(pomRel)) return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
        String pom;
        try {
            pom = fileService.readString(pomRel);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (PomDependencyEditor.containsDependency(pom, resolution.groupId(), resolution.artifactId())) {
            return Optional.empty();
        }
        String patched = PomDependencyEditor.appendDependency(
                pom, resolution.groupId(), resolution.artifactId(), resolution.version());
        if (patched == null) return Optional.empty();
        return Optional.of(new FileMutation(POM_PATH, patched));
    }

    /**
     * Finds {@code .java} files under {@code output/} that reference the bare symbol name and
     * don't already import it. Returns relative paths suitable for {@link FileMutation}.
     */
    private List<ReferencingFile> findReferencingFiles(String simpleName, String fqn) {
        Path outputRoot;
        try {
            outputRoot = fileService.resolveSecurePath(OUTPUT_DIR);
        } catch (IOException e) {
            return List.of();
        }
        if (!Files.isDirectory(outputRoot)) return List.of();

        Pattern bareReference = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b");
        Set<ReferencingFile> hits = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(outputRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        String content;
                        try {
                            content = Files.readString(p);
                        } catch (IOException e) {
                            return;
                        }
                        if (alreadyImports(content, fqn, simpleName)) return;
                        if (definesType(content, simpleName)) return;
                        if (!bareReference.matcher(content).find()) return;
                        // FileMutation paths are relative to output/, mirroring the convention used
                        // by IdiomSkeletonResolver and KnownStarterResolver.
                        String rel = outputRoot.relativize(p).toString().replace('\\', '/');
                        hits.add(new ReferencingFile(rel, content));
                    });
        } catch (IOException e) {
            log.debug("findReferencingFiles walk failed: {}", e.getMessage());
        }
        return new ArrayList<>(hits);
    }

    /**
     * Whether the source file declares a top-level or nested type named {@code simpleName}.
     * Tries the JavaParser AST first (correctly handles nested types, modifiers, annotations);
     * falls back to a regex grep when the file is syntactically invalid — the T5-BC pattern
     * mirrored from {@link SymbolGroundingService#definedSymbolsInOutput()}.
     */
    static boolean definesType(String content, String simpleName) {
        CompilationUnit cu = tryParse(content);
        if (cu != null) {
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                if (simpleName.equals(td.getNameAsString())) return true;
            }
            return false;
        }
        // Fallback: regex grep over the raw source.
        Pattern p = Pattern.compile(
                "(?:public\\s+|abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*"
                        + "(?:class|interface|record|enum)\\s+" + Pattern.quote(simpleName) + "\\b");
        return p.matcher(content).find();
    }

    /**
     * Whether the file already imports {@code fqn}, either directly or via a wildcard covering
     * its package. Static imports also count. AST first, regex fallback for broken files.
     */
    static boolean alreadyImports(String content, String fqn, String simpleName) {
        int lastDot = fqn.lastIndexOf('.');
        String pkg = lastDot > 0 ? fqn.substring(0, lastDot) : "";

        CompilationUnit cu = tryParse(content);
        if (cu != null) {
            for (ImportDeclaration imp : cu.getImports()) {
                String name = imp.getNameAsString();
                if (imp.isAsterisk()) {
                    if (name.equals(pkg)) return true;
                } else {
                    if (name.equals(fqn)) return true;
                }
            }
            return false;
        }
        // Fallback: textual check.
        if (content.contains("import " + fqn + ";")) return true;
        if (content.contains("import static " + fqn + ";")) return true;
        if (lastDot <= 0) return false;
        return content.contains("import " + pkg + ".*;");
    }

    private static CompilationUnit tryParse(String content) {
        try {
            return new JavaParser().parse(content).getResult().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Splices {@code import <fqn>;} into a Java source file. Insertion site, in priority order:
     *   - directly after the last existing {@code import} line;
     *   - directly after the {@code package} declaration;
     *   - at the very top of the file.
     */
    static String insertImport(String source, String fqn) {
        if (source == null) return null;
        String stmt = "import " + fqn + ";";
        String[] lines = source.split("\n", -1);
        int lastImport = -1;
        int packageLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("import ")) lastImport = i;
            else if (trimmed.startsWith("package ")) packageLine = i;
        }
        StringBuilder out = new StringBuilder();
        int insertAfter;
        if (lastImport >= 0) {
            insertAfter = lastImport;
        } else if (packageLine >= 0) {
            insertAfter = packageLine;
        } else {
            // No package or imports — prepend with a blank line to keep the file readable.
            return stmt + "\n\n" + source;
        }
        for (int i = 0; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
            if (i == insertAfter) {
                if (lastImport < 0) out.append('\n'); // blank line between package and imports
                out.append(stmt);
                if (i < lines.length - 1) out.append('\n');
            }
        }
        return out.toString();
    }

    private static boolean isSimpleIdentifier(String s) {
        return JAVA_IDENTIFIER.matcher(s).matches();
    }

    private record ReferencingFile(String relPath, String content) {}
}
