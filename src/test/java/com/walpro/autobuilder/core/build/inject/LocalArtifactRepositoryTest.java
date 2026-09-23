package com.walpro.autobuilder.core.build.inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-GF: the offline answer — which locally present artifact provides a class, and which of its
 * present versions is most suitable. Invented coordinates throughout: the rules are about the
 * repository layout and the group-prefix convention, not about any one library.
 */
class LocalArtifactRepositoryTest {

    @TempDir Path repo;
    private LocalArtifactRepository local;

    @BeforeEach
    void setUp() {
        local = new LocalArtifactRepository();
        local.setRoot(repo.toString());
    }

    private void jar(String group, String artifact, String version, String... classes) throws IOException {
        Path dir = repo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        try (OutputStream out = Files.newOutputStream(dir.resolve(artifact + "-" + version + ".jar"));
             JarOutputStream jar = new JarOutputStream(out)) {
            for (String fqn : classes) {
                jar.putNextEntry(new ZipEntry(fqn.replace('.', '/') + ".class"));
                jar.closeEntry();
            }
        }
    }

    @Test
    void findsTheArtifactByTheGroupPrefixConvention_andPicksTheNewestPlainRelease() throws IOException {
        jar("org.acme.kit", "kit-core", "2.9.0", "org.acme.kit.core.Engine");
        jar("org.acme.kit", "kit-core", "2.10.1", "org.acme.kit.core.Engine");
        jar("org.acme.kit", "kit-core", "3.0.0-RC1", "org.acme.kit.core.Engine");
        jar("org.acme.kit", "kit-extras", "2.10.1", "org.acme.kit.extras.Widget");

        var hit = local.findByFullyQualifiedName("org.acme.kit.core.Engine", null);

        assertThat(hit).isPresent();
        assertThat(hit.get().artifactId()).isEqualTo("kit-core");
        assertThat(hit.get().version()).as("numeric, not lexical; a release candidate is not chosen")
                .isEqualTo("2.10.1");
    }

    @Test
    void alignsToAVersionTheManifestAlreadyUses_whenItIsPresent() throws IOException {
        jar("org.acme.kit", "kit-core", "2.9.0", "org.acme.kit.core.Engine");
        jar("org.acme.kit", "kit-core", "2.10.1", "org.acme.kit.core.Engine");

        assertThat(local.findByFullyQualifiedName("org.acme.kit.core.Engine", "2.9.0").get().version())
                .isEqualTo("2.9.0");
        assertThat(local.findByFullyQualifiedName("org.acme.kit.core.Engine", "1.0.0").get().version())
                .as("an aligned version that is not present is not invented")
                .isEqualTo("2.10.1");
    }

    @Test
    void abstainsWhenNoGroupPrefixingThePackageHoldsTheClass() throws IOException {
        jar("org.other", "thing", "1.0.0", "org.other.Thing");

        assertThat(local.findByFullyQualifiedName("org.acme.kit.core.Engine", null)).isEmpty();
        assertThat(local.findByFullyQualifiedName("org.other.Missing", null)).isEmpty();
    }

    @Test
    void versionOrder_isNumericPerSegment_withAQualifiedVersionJustBelowItsPlainTwin() {
        List<String> v = new java.util.ArrayList<>(List.of("5.5.2", "5.10.0", "5.9.3", "5.10.0-M1", "5.11.0"));
        v.sort(LocalArtifactRepository.NEWEST_FIRST);

        assertThat(v).containsExactly("5.11.0", "5.10.0", "5.10.0-M1", "5.9.3", "5.5.2");
    }
}
