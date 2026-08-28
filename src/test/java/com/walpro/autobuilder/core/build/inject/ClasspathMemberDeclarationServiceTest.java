package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.ProjectClasspathService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * T4-DF. The corpus cases come from the flight-simulator run {@code lineage-76eda78f} — the
 * receiver/member pairs are read off its own compile diagnostics, and the expected declaring
 * types are what the resolved classpath actually says.
 */
class ClasspathMemberDeclarationServiceTest {

    @TempDir
    Path projectRoot;

    private ClasspathMemberDeclarationService serviceWith(String classpath) {
        ProjectClasspathService classpathService = Mockito.mock(ProjectClasspathService.class);
        when(classpathService.resolveClasspath(any(), anyLong())).thenReturn(classpath);
        return new ClasspathMemberDeclarationService(classpathService);
    }

    // ── the platform half of the corpus: no jars involved at all ─────────────

    @Test
    void platformSibling_isFoundWithoutAnyClasspathJar() {
        // `FloatBuffer.allocateDirect(n)` was written by the model; the member is declared on
        // ByteBuffer, a sibling in the same package. Platform types live in the runtime image,
        // so a jar scan alone can never answer this.
        Optional<ClasspathMemberDeclarationService.Declaration> found =
                serviceWith("").declaringSibling("java.nio.FloatBuffer", "allocateDirect", projectRoot);

        assertThat(found).isPresent();
        assertThat(found.get().declaringType()).isEqualTo("java.nio.ByteBuffer");
    }

    @Test
    void memberNoTypeInThePackageDeclares_yieldsSilence() {
        assertThat(serviceWith("").declaringSibling("java.nio.FloatBuffer", "notAMemberOfAnything", projectRoot))
                .isEmpty();
    }

    @Test
    void instanceMemberOfASibling_isNotAnAnswerToAStaticCall() {
        // `flip()` is declared all over java.nio, but only as an instance method — no
        // `Type.flip()` call can bind to it, so proposing one would not compile.
        assertThat(serviceWith("").declaringSibling("java.nio.FloatBuffer", "flip", projectRoot))
                .isEmpty();
    }

    @Test
    void receiverWithoutAPackage_yieldsSilence() {
        assertThat(serviceWith("").declaringSibling("Standalone", "anything", projectRoot)).isEmpty();
    }

    @Test
    void receiverItself_isNeverProposedAsItsOwnAnswer() {
        // ByteBuffer DOES declare allocateDirect; asked about ByteBuffer the service must not
        // answer "ByteBuffer", because that is the call that already failed to compile.
        Optional<ClasspathMemberDeclarationService.Declaration> found =
                serviceWith("").declaringSibling("java.nio.ByteBuffer", "allocateDirect", projectRoot);

        assertThat(found).satisfiesAnyOf(
                f -> assertThat(f).isEmpty(),
                f -> assertThat(f.get().declaringType()).isNotEqualTo("java.nio.ByteBuffer"));
    }

    @Test
    void unresolvableProjectRoot_yieldsSilence() {
        assertThat(serviceWith("").declaringSibling("java.nio.FloatBuffer", "allocateDirect",
                Path.of("/does/not/exist"))).isEmpty();
    }

    // ── the field corpus, against the jars the run actually resolved ─────────

    /**
     * Every {@code GL11}/{@code GL15} case from the run, plus the three that must stay silent.
     * Each of these was answered by re-adding a dependency the pom already declared.
     *
     * <p>Skipped when the artifacts are not in the local repository — the assertion is about the
     * real classpath, and a fabricated stand-in would prove nothing about it.
     */
    @ParameterizedTest(name = "{0}#{1} -> {2}")
    @CsvSource({
            "org.lwjgl.opengl.GL11, glUseProgram,              org.lwjgl.opengl.GL20",
            "org.lwjgl.opengl.GL11, glUniformMatrix4fv,        org.lwjgl.opengl.GL20",
            "org.lwjgl.opengl.GL11, glVertexAttribPointer,     org.lwjgl.opengl.GL20",
            "org.lwjgl.opengl.GL11, glEnableVertexAttribArray, org.lwjgl.opengl.GL20",
            "org.lwjgl.opengl.GL11, glDeleteBuffers,           org.lwjgl.opengl.GL15",
            "org.lwjgl.opengl.GL11, glBindVertexArray,         org.lwjgl.opengl.GL30",
            "org.lwjgl.opengl.GL11, glDeleteVertexArrays,      org.lwjgl.opengl.GL30",
            "org.lwjgl.opengl.GL15, glBindVertexArray,         org.lwjgl.opengl.GL30",
            "org.lwjgl.opengl.GL15, glDeleteVertexArrays,      org.lwjgl.opengl.GL30",
            "org.lwjgl.opengl.GL15, glGenVertexArrays,         org.lwjgl.opengl.GL30",
    })
    void fieldCorpus_resolvesToTheTypeTheClasspathDeclares(String receiver, String member, String expected) {
        String classpath = lwjglClasspath();
        assumeTrue(!classpath.isBlank(), "lwjgl artifacts absent from the local repository");

        Optional<ClasspathMemberDeclarationService.Declaration> found =
                serviceWith(classpath).declaringSibling(receiver, member, projectRoot);

        assertThat(found).as("%s#%s", receiver, member).isPresent();
        assertThat(found.get().declaringType()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}#{1} stays silent")
    @CsvSource({
            // Lives in org.lwjgl.system, not the receiver's package — out of scope by design.
            "org.lwjgl.opengl.GL11,        nioMallocDirect",
            "org.lwjgl.glfw.GLFWKeyCallback, clear",
            // The model invented these two: no type in org.joml declares either.
            "org.joml.Matrix4f,           setIdentity",
            "org.joml.Matrix4f,           toArray",
    })
    void fieldCorpus_membersNothingDeclares_yieldSilence(String receiver, String member) {
        String classpath = lwjglClasspath();
        assumeTrue(!classpath.isBlank(), "lwjgl artifacts absent from the local repository");

        assertThat(serviceWith(classpath).declaringSibling(receiver, member, projectRoot))
                .as("%s#%s", receiver, member)
                .isEmpty();
    }

    /**
     * The disambiguation rule earns its keep here: {@code glBindVertexArray} has three claimants
     * in {@code org.lwjgl.opengl}, and only one of them sits below the receiver.
     */
    @Test
    void severalClaimants_areSeparatedByTheReceiverBeingTheirSupertype() {
        String classpath = lwjglClasspath();
        assumeTrue(!classpath.isBlank(), "lwjgl artifacts absent from the local repository");

        Optional<ClasspathMemberDeclarationService.Declaration> found =
                serviceWith(classpath).declaringSibling("org.lwjgl.opengl.GL11", "glBindVertexArray", projectRoot);

        // ARBVertexArrayObject and GL30C declare it too; GL30 is the only one GL11 is above.
        assertThat(found).isPresent();
        assertThat(found.get().declaringType()).isEqualTo("org.lwjgl.opengl.GL30");
    }

    private static String lwjglClasspath() {
        Path repository = Path.of(System.getProperty("user.home"), ".m2", "repository", "org", "lwjgl");
        if (!Files.isDirectory(repository)) return "";
        List<String> jars = new ArrayList<>();
        try (var walk = Files.walk(repository)) {
            walk.filter(p -> p.toString().endsWith(".jar"))
                .filter(p -> !p.toString().contains("-sources"))
                .filter(p -> !p.toString().contains("-natives"))
                .forEach(p -> jars.add(p.toString()));
        } catch (Exception e) {
            return "";
        }
        return String.join(File.pathSeparator, jars);
    }
}
