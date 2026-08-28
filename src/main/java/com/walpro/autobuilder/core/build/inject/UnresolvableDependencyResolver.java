package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T4-DH: repairs a build that failed because a DECLARED dependency will not resolve.
 *
 * <p>Nothing recognised this failure before. {@link BuildErrorClassifier} held three patterns and
 * none matched {@code Could not find artifact}, so the build classified {@code UNKNOWN},
 * {@link CompileFixInjector} returned at its first gate, and the only remaining move was to ask
 * the model for another version. Measured on run {@code 2026-08-28_03-51-21}: 29 resolution
 * failures across six invented coordinates, the same nonexistent one re-proposed nine times
 * running, while the run's {@code [BUILD_FIX]} subgoals steered on compile-stage errors the build
 * had not reached.
 *
 * <p><b>Two failures, two repairs.</b> Measured against the repository, the coordinates split:
 * {@code org.jogamp.gl4java:gl4java} does not exist at all, while
 * {@code org.jogamp.gluegen:gluegen-rt-main} exists with sixteen published versions and the
 * requested {@code 2.4.0} is not among them. An existence check alone would leave the second kind
 * guessing, so the repository is asked for the version list and the answer decides:
 * <ul>
 *   <li><b>absent</b> — remove the declaration; no version of it can ever resolve</li>
 *   <li><b>present, requested version not published</b> — re-pin to the newest published version</li>
 *   <li><b>present, requested version published</b> — abstain; the coordinate is real and the
 *       failure is somewhere this resolver cannot see (repository config, offline mode)</li>
 *   <li><b>could not be consulted</b> — abstain</li>
 * </ul>
 *
 * <p><b>The last case is the one that matters.</b> The repair for absence is deleting a declared
 * dependency, so a failed query must never be read as absence — otherwise a rate limit or a
 * dropped network would strip dependencies the project needs. {@link CoordinateSearchClient}
 * keeps that distinction in its own return type rather than collapsing it to a boolean.
 */
@Slf4j
@Component
@Order(15)
@RequiredArgsConstructor
public class UnresolvableDependencyResolver implements CompileFixResolver {

    private final CoordinateSearchClient repository;

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken,
                                         String stderr, CycleContextData context) {
        if (classification == null || classification.kind() != Kind.DEPENDENCY_NOT_RESOLVABLE) {
            return Optional.empty();
        }
        if (context == null || context.outputPath() == null) return Optional.empty();

        Map<String, String> sources = context.previousOutput();
        if (sources == null || sources.isEmpty()) return Optional.empty();
        String pomPath = pomPath(sources);
        if (pomPath == null) return Optional.empty();

        String pom = sources.get(pomPath);
        if (pom == null || pom.isBlank()) return Optional.empty();

        List<String> coordinates = BuildErrorClassifier.unresolvableCoordinates(stderr);
        if (coordinates.isEmpty()) return Optional.empty();

        String updated = pom;
        List<String> summaries = new ArrayList<>();
        String firstCoordinate = null;

        for (String coordinate : coordinates) {
            Gav gav = Gav.parse(coordinate);
            if (gav == null) {
                log.info("T4-DH: {} — not a coordinate this resolver can read; abstaining", coordinate);
                continue;
            }
            CoordinateSearchClient.ArtifactVersions published =
                    repository.versionsOf(gav.groupId(), gav.artifactId());

            String next = switch (published.availability()) {
                case ABSENT -> {
                    String without = PomDependencyEditor.removeDependency(
                            updated, gav.groupId(), gav.artifactId());
                    if (without == null || without.equals(updated)) {
                        log.info("T4-DH: {}:{} is not published, but this pom does not declare it "
                                + "(inherited or managed elsewhere); abstaining",
                                gav.groupId(), gav.artifactId());
                        yield updated;
                    }
                    log.info("T4-DH: {}:{} is not published at all — removing the declaration",
                            gav.groupId(), gav.artifactId());
                    summaries.add("removed " + gav.groupId() + ":" + gav.artifactId() + " (no such artifact)");
                    yield without;
                }
                case PRESENT -> {
                    if (published.declaresVersion(gav.version())) {
                        log.info("T4-DH: {}:{}:{} IS published — the failure is not the coordinate; abstaining",
                                gav.groupId(), gav.artifactId(), gav.version());
                        yield updated;
                    }
                    Optional<String> newest = published.newest();
                    if (newest.isEmpty()) yield updated;
                    String repinned = PomDependencyEditor.setDependencyVersion(
                            updated, gav.groupId(), gav.artifactId(), newest.get());
                    // Report what happened, not what was attempted. The editor declines a version
                    // pinned through a property — the project's own indirection — and a marker
                    // claiming a re-pin that did not occur is worse than no marker at all.
                    if (repinned == null || repinned.equals(updated)) {
                        log.info("T4-DH: {}:{} has no version {}, but its declaration does not carry a "
                                + "literal version to rewrite; abstaining",
                                gav.groupId(), gav.artifactId(), gav.version());
                        yield updated;
                    }
                    log.info("T4-DH: {}:{} has no version {} — re-pinning to {} (of {} published)",
                            gav.groupId(), gav.artifactId(), gav.version(), newest.get(),
                            published.versions().size());
                    summaries.add(gav.groupId() + ":" + gav.artifactId() + " " + gav.version()
                            + " -> " + newest.get());
                    yield repinned;
                }
                case UNKNOWN -> {
                    log.info("T4-DH: {}:{} — the repository could not be consulted; abstaining rather "
                            + "than assuming absence", gav.groupId(), gav.artifactId());
                    yield updated;
                }
            };

            if (next != null && !next.equals(updated)) {
                updated = next;
                if (firstCoordinate == null) firstCoordinate = coordinate;
            }
        }

        if (updated.equals(pom)) return Optional.empty();

        String description = "Repaired unresolvable " + (summaries.size() == 1 ? "dependency: " : "dependencies: ")
                + String.join("; ", summaries) + " (the repository was asked, not the model)";
        InjectorFixRecord record = new InjectorFixRecord(
                "unresolvable-dependency", Kind.DEPENDENCY_NOT_RESOLVABLE, firstCoordinate,
                List.of(pomPath), description);
        log.info("CompileFixInjector: unresolvable-dependency — {}", description);
        return Optional.of(new FixProposal(List.of(new FileMutation(pomPath, updated)), record));
    }

    private static String pomPath(Map<String, String> sources) {
        for (String path : sources.keySet()) {
            if ("pom.xml".equals(path) || path.endsWith("/pom.xml")) return path;
        }
        return null;
    }

    /**
     * Maven prints the coordinate as {@code groupId:artifactId:type:version} and, when the
     * declaration carries one, {@code groupId:artifactId:type:classifier:version}. Group and
     * artifact are always the first two segments and the version is always the last, so the
     * middle is skipped rather than enumerated.
     */
    record Gav(String groupId, String artifactId, String version) {
        static Gav parse(String coordinate) {
            if (coordinate == null) return null;
            String[] parts = coordinate.split(":");
            if (parts.length < 4) return null;
            String group = parts[0];
            String artifact = parts[1];
            String version = parts[parts.length - 1];
            if (group.isBlank() || artifact.isBlank() || version.isBlank()) return null;
            return new Gav(group, artifact, version);
        }
    }
}
