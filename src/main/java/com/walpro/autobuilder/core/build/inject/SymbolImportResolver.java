package com.walpro.autobuilder.core.build.inject;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final WellKnownPackageRegistry registry;
    private final PackageDiscoveryService discoveryService;
    private final BomManagedVersionsService bomManagedVersions;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken, String stderr,
                                         CycleContextData context) {
        if (classification == null) return Optional.empty();
        if (missingToken == null || missingToken.isBlank()) return Optional.empty();
        if (context == null || context.outputPath() == null) return Optional.empty();

        // T4-BG: PACKAGE_DOES_NOT_EXIST takes the registry-only path — the missing token IS a
        // package name (dotted, lowercase), there's no Java import to splice into a source file,
        // only a pom dep to add. Subsumes the deleted KnownStarterResolver.
        // T4-AN-7: this and MISSING_POM both fall under CONFIG_FIXABLE, so the package-vs-symbol
        // split still reads kind() — category alone cannot separate the two config shapes.
        Kind kind = classification.kind();
        if (kind == Kind.PACKAGE_DOES_NOT_EXIST) {
            return resolveMissingPackage(missingToken, context);
        }

        if (kind != Kind.CANNOT_FIND_SYMBOL) return Optional.empty();
        if (!isSimpleIdentifier(missingToken)) return Optional.empty();

        Optional<FixProposal> local = tryLocalCrossPackageImport(missingToken);
        if (local.isPresent()) return local;

        // T4-BM (step 6b): member-shaped tokens (lowercase initial) can never be fixed by a
        // type import — `cannot find symbol: method isPresent` needs code, not `import`.
        // They previously flowed to the LLM lookup and polluted the SymbolImportCache with
        // NONE rows (isPresent, orElseThrow, memberRepository — 22 of the 25 inspected
        // entries). Project-local types of any casing were already handled above.
        if (!Character.isUpperCase(missingToken.charAt(0))) return Optional.empty();

        // T4-BM: project-aware discovery first — the project's own classpath/imports beat any
        // registry row (exact FQN, the project's own resolved version).
        Optional<PackageDiscoveryService.Discovery> discovered = lookupInDiscovery(missingToken, context);
        if (discovered.isPresent()) {
            return buildExternalFixProposal(missingToken, discovered.get().resolution(), context);
        }

        // Registry fallback for the case discovery can't see (artifact absent from classpath,
        // never imported), then the LLM as last resort.
        Optional<Resolution> registryResolution = lookupSimpleNameInRegistry(missingToken, context);
        if (registryResolution.isPresent()) {
            return buildExternalFixProposal(missingToken, registryResolution.get(), context);
        }

        Optional<Resolution> resolution = lookupService.lookup(missingToken);
        if (resolution.isEmpty()) return Optional.empty();

        return buildExternalFixProposal(missingToken, resolution.get(), context);
    }

    private Optional<PackageDiscoveryService.Discovery> lookupInDiscovery(String simpleName, CycleContextData context) {
        if (discoveryService == null) return Optional.empty();
        try {
            return discoveryService.lookupType(simpleName, fileService.resolveSecurePath(OUTPUT_DIR));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<PackageDiscoveryService.Discovery> lookupPackageInDiscovery(String missingPackage) {
        if (discoveryService == null) return Optional.empty();
        try {
            return discoveryService.lookupPackage(missingPackage, fileService.resolveSecurePath(OUTPUT_DIR));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * T4-BG / T4-BI: {@code PACKAGE_DOES_NOT_EXIST} path. Always tries the pom edit; when the
     * registry knows the package has a deterministic source-text replacement (T4-BI — a legacy
     * prefix with a modern successor), bundles source-import rewrites into the same
     * {@link FixProposal} so the next probe sees the full fix.
     */
    private Optional<FixProposal> resolveMissingPackage(String missingPackage, CycleContextData context) {
        String manifest = registry.readManifestOrEmpty(context.outputPath() + "/" + POM_PATH);

        // T4-BM: discovery first — when the package is already on the project's resolved
        // classpath, its OWN coordinates win over any registry assumption (failure mode 3:
        // the registry's worldview overriding the project's).
        Optional<WellKnownPackageRegistry.Gav> gav = lookupPackageInDiscovery(missingPackage)
                .map(d -> new WellKnownPackageRegistry.Gav(
                        d.resolution().groupId(), d.resolution().artifactId(), d.resolution().version()));
        if (gav.isEmpty()) {
            gav = registry.lookup(missingPackage, manifest);
            gav.ifPresent(g -> emitRegistryHit(missingPackage, g));
        }
        if (gav.isEmpty()) return Optional.empty();

        Resolution resolution = new Resolution(
                missingPackage,           // fqn-ish: the package itself; there's no class to import
                gav.get().groupId(),
                gav.get().artifactId(),
                gav.get().version()
        );
        Optional<FileMutation> pomMutation = maybeAddDependency(resolution, context);
        Optional<WellKnownPackageRegistry.PackageReplacement> replacement =
                registry.replacementFor(missingPackage, manifest);

        List<FileMutation> mutations = new ArrayList<>();
        List<String> touched = new ArrayList<>();
        pomMutation.ifPresent(m -> {
            mutations.add(m);
            touched.add(POM_PATH);
        });
        int rewrittenCount = 0;
        if (replacement.isPresent()) {
            List<RewrittenFile> rewrites = rewriteImports(replacement.get());
            for (RewrittenFile r : rewrites) {
                mutations.add(new FileMutation(r.relPath(), r.content()));
                touched.add(r.relPath());
            }
            rewrittenCount = rewrites.size();
        }

        if (mutations.isEmpty()) return Optional.empty();

        // T4-BI: the package-replace rule only fires when an actual source rewrite happened.
        // A registry hit with no source files needing migration is the original T4-BG pom-only path.
        String rule = (replacement.isPresent() && rewrittenCount > 0)
                ? "package-replace:" + replacement.get().oldPrefix() + "->" + replacement.get().newPrefix()
                : "registry:" + resolution.artifactId();
        StringBuilder desc = new StringBuilder();
        if (pomMutation.isPresent()) {
            desc.append("Added ").append(resolution.groupId()).append(":")
                    .append(resolution.artifactId()).append(" to pom.xml");
        }
        if (rewrittenCount > 0) {
            if (desc.length() > 0) desc.append("; ");
            desc.append("rewrote ").append(rewrittenCount).append(" source file")
                    .append(rewrittenCount == 1 ? "" : "s")
                    .append(" from ").append(replacement.get().oldPrefix())
                    .append(" -> ").append(replacement.get().newPrefix());
        }
        InjectorFixRecord record = new InjectorFixRecord(
                rule,
                Kind.PACKAGE_DOES_NOT_EXIST,
                missingPackage,
                touched,
                desc.toString()
        );
        log.info("CompileFixInjector: {} — {} (touched {})", rule, desc, touched);
        return Optional.of(new FixProposal(mutations, record));
    }

    /**
     * T4-BI: walks every {@code .java} file under {@code output/src/main/java/} and rewrites
     * each {@code import <oldPrefix>...} line (specific token or wildcard, with or without
     * {@code static}) by substituting the leading {@code oldPrefix} with {@code newPrefix}.
     * Files whose imports already use the new prefix exclusively are skipped (idempotence guard).
     */
    private List<RewrittenFile> rewriteImports(WellKnownPackageRegistry.PackageReplacement replacement) {
        Path javaRoot;
        try {
            javaRoot = fileService.resolveSecurePath(OUTPUT_DIR + "/src/main/java");
        } catch (IOException e) {
            return List.of();
        }
        if (!Files.isDirectory(javaRoot)) return List.of();
        Path outputRoot;
        try {
            outputRoot = fileService.resolveSecurePath(OUTPUT_DIR);
        } catch (IOException e) {
            return List.of();
        }

        List<RewrittenFile> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(javaRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        String content;
                        try {
                            content = Files.readString(p);
                        } catch (IOException e) {
                            return;
                        }
                        String patched = rewriteImportLines(content, replacement.oldPrefix(), replacement.newPrefix());
                        if (patched == null) return;
                        String rel = outputRoot.relativize(p).toString().replace('\\', '/');
                        out.add(new RewrittenFile(rel, patched));
                    });
        } catch (IOException e) {
            log.debug("rewriteImports walk failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Returns the source with import lines rewritten, or {@code null} if no line changed.
     * Matches {@code import [static] <oldPrefix>(.X | .*)? ;} and substitutes the leading
     * {@code oldPrefix} with {@code newPrefix} on every match.
     */
    static String rewriteImportLines(String source, String oldPrefix, String newPrefix) {
        if (source == null || oldPrefix == null || newPrefix == null) return null;
        String[] lines = source.split("\n", -1);
        Pattern importPattern = Pattern.compile(
                "^(\\s*import\\s+(?:static\\s+)?)" + Pattern.quote(oldPrefix) + "((?:\\.[^;\\s]+)?\\s*;.*)$");
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            var m = importPattern.matcher(lines[i]);
            if (!m.matches()) continue;
            lines[i] = m.group(1) + newPrefix + m.group(2);
            changed = true;
        }
        return changed ? String.join("\n", lines) : null;
    }

    private record RewrittenFile(String relPath, String content) {}

    /** T4-BG/T4-BM: simple-name FALLBACK pass against the registry (discovery abstained). The
     *  registry owns FQN construction now — the resolver-side {@code canonicalFqnFor} that
     *  reconstructed prefixes from GAVs is deleted (T4-BM step 2). */
    private Optional<Resolution> lookupSimpleNameInRegistry(String simpleName, CycleContextData context) {
        String manifest = registry.readManifestOrEmpty(context.outputPath() + "/" + POM_PATH);
        Optional<Resolution> resolution = registry.resolveSimpleName(simpleName, manifest);
        resolution.ifPresent(r -> emitRegistryHit(simpleName,
                new WellKnownPackageRegistry.Gav(r.groupId(), r.artifactId(), r.version())));
        return resolution;
    }

    private void emitRegistryHit(String symbol, WellKnownPackageRegistry.Gav gav) {
        if (behaviourLogger == null) return;
        try {
            behaviourLogger.symbolResolverRegistryHit(symbol, gav.groupId(), gav.artifactId(), gav.version());
        } catch (Exception e) {
            log.debug("symbolResolverRegistryHit emit failed: {}", e.getMessage());
        }
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

        // T2-DY: target-path existence guard. Drop any proposed mutation whose path isn't on
        // disk (pom.xml is allowed to be absent — maybeAddDependency already verified). In
        // practice findReferencingFiles only emits paths it walked, so this rarely fires —
        // it's a defensive check that pays for itself the first time a resolver is added that
        // computes paths without a disk-walk.
        if (dropMissingTargets(mutations, touched, context, "symbol-import:" + (resolution.hasGav() ? resolution.artifactId() : "import-only"))) {
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
                pom, resolution.groupId(), resolution.artifactId(),
                versionUnlessManaged(resolution, context));
        if (patched == null) return Optional.empty();
        return Optional.of(new FileMutation(POM_PATH, patched));
    }

    /**
     * T4-BM step 4: the ONE pom-write point drops the version element whenever the project's own
     * dependency-management chain manages the artifact — the managed version then governs, and a
     * literal here can only drift (field 2026-07-09..14: the coordinate search's latest-version
     * pick wrote {@code 3.1.0-M1} into a pom whose BOM managed 3.0.2; the milestone then
     * propagated into later classpath resolutions). Applies to every resolution source alike
     * (discovery, registry, LLM).
     */
    private String versionUnlessManaged(Resolution resolution, CycleContextData context) {
        String version = resolution.version();
        if (version == null || version.isBlank() || bomManagedVersions == null) return version;
        try {
            Path outputRoot = fileService.resolveSecurePath(OUTPUT_DIR);
            Optional<String> managed = bomManagedVersions.managedVersion(
                    outputRoot, resolution.groupId(), resolution.artifactId());
            if (managed.isPresent()) {
                log.info("T4-BM: {}:{} is managed at {} by the project's own BOM chain — omitting the version literal ({})",
                        resolution.groupId(), resolution.artifactId(), managed.get(), version);
                return null;
            }
        } catch (IOException e) {
            log.debug("T4-BM: managed-version check unavailable — {}", e.getMessage());
        }
        return version;
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

    /**
     * T2-DY: drops any mutation whose target path doesn't exist in the output tree. Returns
     * {@code true} when, after pruning, no mutations remain (caller should give up the proposal).
     * Emits one {@code fix_proposal_skipped_missing_target} event per dropped path so analysis
     * can detect resolvers that consistently target the wrong file. {@code pom.xml} is exempt
     * because the dependency-add path is allowed to create it.
     */
    private boolean dropMissingTargets(List<FileMutation> mutations, List<String> touched,
                                       CycleContextData context, String rule) {
        if (context == null || context.outputPath() == null) return false;
        Path outputRoot;
        try {
            outputRoot = fileService.resolveSecurePath(OUTPUT_DIR);
        } catch (IOException e) {
            return false;
        }
        for (int i = mutations.size() - 1; i >= 0; i--) {
            FileMutation m = mutations.get(i);
            if (m == null || m.path() == null) continue;
            if (POM_PATH.equals(m.path())) continue;
            Path target = outputRoot.resolve(m.path()).normalize();
            if (!Files.exists(target)) {
                mutations.remove(i);
                touched.remove(m.path());
                emitMissingTarget(rule, m.path(), context);
            }
        }
        return mutations.isEmpty();
    }

    private void emitMissingTarget(String rule, String missingPath, CycleContextData context) {
        if (behaviourLogger == null) return;
        try {
            behaviourLogger.fixProposalSkippedMissingTarget(
                    context == null ? null : context.cycleName(), rule, missingPath);
        } catch (Exception e) {
            log.debug("fixProposalSkippedMissingTarget emit failed: {}", e.getMessage());
        }
    }

    private record ReferencingFile(String relPath, String content) {}
}
