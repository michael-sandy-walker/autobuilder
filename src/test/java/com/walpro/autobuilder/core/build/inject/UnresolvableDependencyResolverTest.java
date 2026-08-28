package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.inject.CoordinateSearchClient.ArtifactVersions;
import com.walpro.autobuilder.core.build.inject.CoordinateSearchClient.Availability;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T4-DH. The stderr fixture is the shape run {@code 2026-08-28_03-51-21} produced 29 times, and
 * the two repository answers are the ones measured against Maven Central on 2026-08-28:
 * {@code gl4java} is not published at all, {@code gluegen-rt-main} is published with the
 * requested version invented.
 */
class UnresolvableDependencyResolverTest {

    private CoordinateSearchClient repository;
    private UnresolvableDependencyResolver resolver;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(CoordinateSearchClient.class);
        resolver = new UnresolvableDependencyResolver(repository);
    }

    private static String stderr(String coordinate) {
        return String.join("\n",
                "[ERROR] Failed to execute goal on project flightsim: Could not resolve dependencies:",
                "[ERROR] Could not find artifact " + coordinate + " in central "
                        + "(https://repo.maven.apache.org/maven2) -> [Help 1]");
    }

    private static String pomWith(String group, String artifact, String version) {
        return """
                <project>
                  <artifactId>flightsim</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                    </dependency>
                    <dependency>
                      <groupId>org.keep</groupId>
                      <artifactId>keeper</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(group, artifact, version);
    }

    private static CycleContextData context(String pom) {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath(System.getProperty("java.io.tmpdir"))
                .previousOutput(Map.of("pom.xml", pom))
                .build();
    }

    private static Classification classify(String stderr) {
        return BuildErrorClassifier.classify(stderr);
    }

    @Test
    void theFailureShapeIsRecognisedAtAll() {
        // It matched no pattern before this ticket, so it classified UNKNOWN and the injector
        // returned at its first gate.
        Classification c = classify(stderr("org.jogamp.gl4java:gl4java:jar:2.4.0-rc-2"));

        assertThat(c.kind()).isEqualTo(Kind.DEPENDENCY_NOT_RESOLVABLE);
        assertThat(c.category()).isEqualTo(BuildErrorClassifier.Category.CONFIG_FIXABLE);
        assertThat(BuildErrorClassifier.extractToken(stderr("g:a:jar:1"), Kind.DEPENDENCY_NOT_RESOLVABLE))
                .contains("g:a:jar:1");
    }

    @Test
    void anArtifactThatIsNotPublishedAtAll_isRemoved() {
        when(repository.versionsOf(eq("org.jogamp.gl4java"), eq("gl4java")))
                .thenReturn(new ArtifactVersions(Availability.ABSENT, List.of()));
        String pom = pomWith("org.jogamp.gl4java", "gl4java", "2.4.0-rc-2");

        Optional<FixProposal> proposal = resolver.resolve(
                classify(stderr("org.jogamp.gl4java:gl4java:jar:2.4.0-rc-2")), null,
                stderr("org.jogamp.gl4java:gl4java:jar:2.4.0-rc-2"), context(pom));

        assertThat(proposal).isPresent();
        String updated = proposal.get().mutations().get(0).content();
        assertThat(updated).doesNotContain("gl4java");
        assertThat(updated).contains("keeper");          // untouched
        assertThat(proposal.get().record().description()).contains("no such artifact");
    }

    @Test
    void anArtifactThatExistsWithAnInventedVersion_isRePinnedToTheNewestPublished() {
        when(repository.versionsOf(eq("org.jogamp.gluegen"), eq("gluegen-rt-main")))
                .thenReturn(new ArtifactVersions(Availability.PRESENT,
                        List.of("2.3.2", "2.3.1", "2.3.0", "2.2.4")));
        String pom = pomWith("org.jogamp.gluegen", "gluegen-rt-main", "2.4.0");

        Optional<FixProposal> proposal = resolver.resolve(
                classify(stderr("org.jogamp.gluegen:gluegen-rt-main:jar:2.4.0")), null,
                stderr("org.jogamp.gluegen:gluegen-rt-main:jar:2.4.0"), context(pom));

        assertThat(proposal).isPresent();
        String updated = proposal.get().mutations().get(0).content();
        assertThat(updated).contains("<version>2.3.2</version>");
        assertThat(updated).doesNotContain("<version>2.4.0</version>");
        assertThat(updated).contains("gluegen-rt-main");  // kept, not removed
    }

    @Test
    void aRepositoryThatCannotBeConsulted_neverDeletesADependency() {
        // The safety property. A rate limit, an offline box or a changed response shape must not
        // read as "this artifact does not exist", because the repair for absence is deletion.
        when(repository.versionsOf(any(), any())).thenReturn(new ArtifactVersions(Availability.UNKNOWN, List.of()));
        String pom = pomWith("org.jogamp.gl4java", "gl4java", "2.4.0-rc-2");

        Optional<FixProposal> proposal = resolver.resolve(
                classify(stderr("org.jogamp.gl4java:gl4java:jar:2.4.0-rc-2")), null,
                stderr("org.jogamp.gl4java:gl4java:jar:2.4.0-rc-2"), context(pom));

        assertThat(proposal).isEmpty();
    }

    @Test
    void aCoordinateThatIsPublishedAtTheRequestedVersion_isLeftAlone() {
        // Real coordinate, real version — the failure is repository configuration or offline mode,
        // which this resolver cannot see and must not paper over.
        when(repository.versionsOf(eq("org.keep"), eq("keeper")))
                .thenReturn(new ArtifactVersions(Availability.PRESENT, List.of("1.0")));
        String pom = pomWith("org.keep", "keeper", "1.0");

        Optional<FixProposal> proposal = resolver.resolve(
                classify(stderr("org.keep:keeper:jar:1.0")), null,
                stderr("org.keep:keeper:jar:1.0"), context(pom));

        assertThat(proposal).isEmpty();
    }

    @Test
    void everyDistinctFailingCoordinateIsRead_notOnlyTheFirst() {
        String multi = stderr("org.a:one:jar:1.0") + "\n" + stderr("org.b:two:jar:2.0")
                + "\n" + stderr("org.a:one:jar:1.0");

        assertThat(BuildErrorClassifier.unresolvableCoordinates(multi))
                .containsExactly("org.a:one:jar:1.0", "org.b:two:jar:2.0");
    }

    @Test
    void aCoordinateCarryingAClassifier_isParsedGroupArtifactVersion() {
        // groupId:artifactId:type:classifier:version — the native-library shape.
        UnresolvableDependencyResolver.Gav gav =
                UnresolvableDependencyResolver.Gav.parse("org.lwjgl:lwjgl:jar:natives-linux:3.3.1");

        assertThat(gav).isNotNull();
        assertThat(gav.groupId()).isEqualTo("org.lwjgl");
        assertThat(gav.artifactId()).isEqualTo("lwjgl");
        assertThat(gav.version()).isEqualTo("3.3.1");
    }

    @Test
    void everyDeclarationOfAnAbsentArtifactIsRemoved_includingItsClassifierRows() {
        // A native library is legitimately declared several times with different classifiers. If
        // the coordinate does not exist, none of those rows can resolve — leaving any behind
        // fails the next build identically.
        when(repository.versionsOf(any(), any())).thenReturn(new ArtifactVersions(Availability.ABSENT, List.of()));
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.ghost</groupId><artifactId>ghost</artifactId><version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.ghost</groupId><artifactId>ghost</artifactId><version>1.0</version>
                      <classifier>natives-linux</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """;

        Optional<FixProposal> proposal = resolver.resolve(
                classify(stderr("org.ghost:ghost:jar:1.0")), null,
                stderr("org.ghost:ghost:jar:1.0"), context(pom));

        assertThat(proposal).isPresent();
        assertThat(proposal.get().mutations().get(0).content()).doesNotContain("ghost");
    }

    @Test
    void aVersionPinnedThroughAProperty_isNotRewrittenBehindTheProjectsBack() {
        when(repository.versionsOf(any(), any()))
                .thenReturn(new ArtifactVersions(Availability.PRESENT, List.of("3.3.3")));
        String pom = pomWith("org.lwjgl", "lwjgl", "${lwjgl.version}");

        Optional<FixProposal> proposal = resolver.resolve(
                classify(stderr("org.lwjgl:lwjgl:jar:9.9.9")), null,
                stderr("org.lwjgl:lwjgl:jar:9.9.9"), context(pom));

        assertThat(proposal).isEmpty();
    }

    // ── the two wordings Maven actually emits ────────────────────────────────

    /**
     * Verbatim from run {@code 2026-08-28_03-51-21}. This LIST wording occurred 31 times against
     * the singular's 29, and one line carried sixteen coordinates — so a pattern that reads only
     * the singular sees a fraction of the failure and would repair one artifact per build attempt
     * while the rest stay broken.
     */
    @Test
    void theListWordingIsRead_andCarriesEveryFailingCoordinate() {
        String listForm = "[ERROR] Failed to execute goal on project flightsim: Could not resolve "
                + "dependencies for project com.flightsim:flightsim:jar:0.0.1-SNAPSHOT: The following "
                + "artifacts could not be resolved: org.jogamp.gl:gl:jar:2.5.0, "
                + "org.jogamp.gl:glu:jar:2.5.0, org.jogamp.gl:glawt-natives-linux-amd64:jar:2.5.0: "
                + "Cannot access central -> [Help 1]";

        assertThat(BuildErrorClassifier.classify(listForm).kind())
                .isEqualTo(Kind.DEPENDENCY_NOT_RESOLVABLE);
        assertThat(BuildErrorClassifier.unresolvableCoordinates(listForm))
                .containsExactly("org.jogamp.gl:gl:jar:2.5.0",
                                 "org.jogamp.gl:glu:jar:2.5.0",
                                 "org.jogamp.gl:glawt-natives-linux-amd64:jar:2.5.0");
    }

    /**
     * The list line opens by naming the project being built. Offering that as a failing dependency
     * would have the resolver delete the project's own artifact from its own pom.
     */
    @Test
    void theProjectsOwnCoordinate_isNeverOfferedAsAFailingDependency() {
        String listForm = "Could not resolve dependencies for project com.flightsim:flightsim:jar:"
                + "0.0.1-SNAPSHOT: The following artifacts could not be resolved: org.ghost:ghost:jar:1.0";

        assertThat(BuildErrorClassifier.unresolvableCoordinates(listForm))
                .containsExactly("org.ghost:ghost:jar:1.0")
                .doesNotContain("com.flightsim:flightsim:jar:0.0.1-SNAPSHOT");
    }

    @Test
    void bothWordingsInOneOutput_deduplicateToOneCoordinateSet() {
        String both = "Could not resolve dependencies for project com.x:x:jar:1.0-SNAPSHOT: "
                + "The following artifacts could not be resolved: org.a:one:jar:1.0\n"
                + "[ERROR] Could not find artifact org.a:one:jar:1.0 in central";

        assertThat(BuildErrorClassifier.unresolvableCoordinates(both))
                .containsExactly("org.a:one:jar:1.0");
    }

    @Test
    void prosePunctuationAndUrls_areNotMistakenForCoordinates() {
        String noisy = "The following artifacts could not be resolved: org.a:one:jar:1.0: "
                + "Cannot access central (https://repo.maven.apache.org/maven2) at 2026-08-28T03:58:21";

        assertThat(BuildErrorClassifier.unresolvableCoordinates(noisy))
                .containsExactly("org.a:one:jar:1.0");
    }

    // ── the repository interface itself ──────────────────────────────────────

    @Test
    void zeroResults_meanAbsent_andAnUnreadableBody_meansUnknown() {
        assertThat(CoordinateSearchClient.parseVersions("{\"response\":{\"numFound\":0,\"docs\":[]}}")
                .availability()).isEqualTo(Availability.ABSENT);
        assertThat(CoordinateSearchClient.parseVersions("not json").availability())
                .isEqualTo(Availability.UNKNOWN);
        assertThat(CoordinateSearchClient.parseVersions("").availability())
                .isEqualTo(Availability.UNKNOWN);
    }

    @Test
    void versionsComeBackNewestFirst_byPublishTimeRatherThanStringOrder() {
        // "2.10" vs "2.9" is exactly where string ordering goes wrong, and version ORDERING is
        // ecosystem semantics; publish time is a fact the repository states.
        String body = """
                {"response":{"numFound":3,"docs":[
                  {"v":"2.9","timestamp":100},
                  {"v":"2.10","timestamp":300},
                  {"v":"2.1","timestamp":200}]}}
                """;

        ArtifactVersions versions = CoordinateSearchClient.parseVersions(body);

        assertThat(versions.availability()).isEqualTo(Availability.PRESENT);
        assertThat(versions.versions()).containsExactly("2.10", "2.1", "2.9");
        assertThat(versions.newest()).contains("2.10");
        assertThat(versions.declaresVersion("2.1")).isTrue();
        assertThat(versions.declaresVersion("2.4.0")).isFalse();
    }
}
