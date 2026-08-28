package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-DF. The stderr fixtures are the shapes the flight-simulator run actually produced —
 * {@code location: class org.lwjgl.opengl.GL11} for a static call, and
 * {@code location: variable … of type org.joml.Matrix4f} for the instance calls that must be
 * left alone.
 */
class MisplacedMemberResolverTest {

    private ClasspathMemberDeclarationService declarations;
    private MisplacedMemberResolver resolver;

    private static final Classification CANNOT_FIND_SYMBOL =
            new Classification(Kind.CANNOT_FIND_SYMBOL, List.of());

    @BeforeEach
    void setUp() {
        declarations = Mockito.mock(ClasspathMemberDeclarationService.class);
        resolver = new MisplacedMemberResolver(declarations);
    }

    private static String staticCallStderr(String receiverFqn, String member) {
        return String.join("\n",
                "[ERROR] /out/src/main/java/com/flightsim/Renderer.java:[42,9] cannot find symbol",
                "[ERROR]   symbol:   method " + member + "(int)",
                "[ERROR]   location: class " + receiverFqn);
    }

    private static CycleContextData context(Map<String, String> sources) {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath(System.getProperty("java.io.tmpdir"))
                .previousOutput(sources)
                .build();
    }

    @Test
    void retargetsTheCallAndImportsTheDeclaringType() {
        when(declarations.declaringSibling(eq("org.lwjgl.opengl.GL11"), eq("glUseProgram"), any()))
                .thenReturn(Optional.of(new ClasspathMemberDeclarationService.Declaration(
                        "org.lwjgl.opengl.GL11", "glUseProgram", "org.lwjgl.opengl.GL20")));

        String source = """
                package com.flightsim;

                import org.lwjgl.opengl.GL11;

                public class Renderer {
                    void draw(int program) {
                        GL11.glClear(0);
                        GL11.glUseProgram(program);
                    }
                }
                """;

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "glUseProgram",
                staticCallStderr("org.lwjgl.opengl.GL11", "glUseProgram"),
                context(Map.of("src/main/java/com/flightsim/Renderer.java", source)));

        assertThat(proposal).isPresent();
        String rewritten = proposal.get().mutations().get(0).content();
        assertThat(rewritten).contains("GL20.glUseProgram(program);");
        assertThat(rewritten).contains("import org.lwjgl.opengl.GL20;");
        // The call that always resolved is untouched, and so is its import.
        assertThat(rewritten).contains("GL11.glClear(0);");
        assertThat(rewritten).contains("import org.lwjgl.opengl.GL11;");
    }

    @Test
    void aMemberOnAVALUE_isNeverRetargeted() {
        // `projectionMatrix.setIdentity()` — the receiver is a value, so naming a different type
        // at the call site would be a different program. The service must not even be consulted.
        String stderr = String.join("\n",
                "[ERROR] /out/src/main/java/com/flightsim/Renderer.java:[9,26] cannot find symbol",
                "[ERROR]   symbol:   method setIdentity()",
                "[ERROR]   location: variable projectionMatrix of type org.joml.Matrix4f");

        String source = """
                package com.flightsim;
                public class Renderer {
                    void go(org.joml.Matrix4f projectionMatrix) { projectionMatrix.setIdentity(); }
                }
                """;

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "setIdentity", stderr,
                context(Map.of("src/main/java/com/flightsim/Renderer.java", source)));

        assertThat(proposal).isEmpty();
        verify(declarations, never()).declaringSibling(any(), any(), any());
    }

    @Test
    void aTypeTheProjectDefines_isLeftToTheMemberSteeringBlock() {
        String source = "package com.flightsim;\npublic class WorldObject {}\n";
        String caller = "package com.flightsim;\npublic class R { void f() { WorldObject.getVertexCount(); } }\n";

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "getVertexCount",
                staticCallStderr("com.flightsim.WorldObject", "getVertexCount"),
                context(Map.of(
                        "src/main/java/com/flightsim/WorldObject.java", source,
                        "src/main/java/com/flightsim/R.java", caller)));

        assertThat(proposal).isEmpty();
        verify(declarations, never()).declaringSibling(any(), any(), any());
    }

    @Test
    void whenTheClasspathDoesNotAnswer_theResolverAbstains() {
        when(declarations.declaringSibling(any(), any(), any())).thenReturn(Optional.empty());

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "glUseProgram",
                staticCallStderr("org.lwjgl.opengl.GL11", "glUseProgram"),
                context(Map.of("src/main/java/com/flightsim/Renderer.java",
                        "package com.flightsim;\npublic class Renderer { void f() { GL11.glUseProgram(1); } }\n")));

        assertThat(proposal).isEmpty();
    }

    @Test
    void aDiagnosticNamingNoPackage_isNotGuessedAt() {
        String stderr = String.join("\n",
                "[ERROR] /out/R.java:[1,1] cannot find symbol",
                "[ERROR]   symbol:   method glUseProgram(int)",
                "[ERROR]   location: class GL11");

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "glUseProgram", stderr,
                context(Map.of("src/main/java/R.java", "public class R {}\n")));

        assertThat(proposal).isEmpty();
        verify(declarations, never()).declaringSibling(any(), any(), any());
    }

    @Test
    void onlyCannotFindSymbolIsHandled() {
        Optional<FixProposal> proposal = resolver.resolve(
                new Classification(Kind.PACKAGE_DOES_NOT_EXIST, List.of()), "org.lwjgl.opengl",
                staticCallStderr("org.lwjgl.opengl.GL11", "glUseProgram"),
                context(Map.of("src/main/java/R.java", "public class R {}\n")));

        assertThat(proposal).isEmpty();
    }

    @Test
    void severalCallSitesOfTheSameMisplacedMember_areAllRewritten() {
        when(declarations.declaringSibling(eq("org.lwjgl.opengl.GL11"), eq("glBindVertexArray"), any()))
                .thenReturn(Optional.of(new ClasspathMemberDeclarationService.Declaration(
                        "org.lwjgl.opengl.GL11", "glBindVertexArray", "org.lwjgl.opengl.GL30")));

        String a = "package com.flightsim;\nimport org.lwjgl.opengl.GL11;\n"
                 + "public class A { void f() { GL11.glBindVertexArray(1); } }\n";
        String b = "package com.flightsim;\nimport org.lwjgl.opengl.GL11;\n"
                 + "public class B { void g() { GL11.glBindVertexArray(2); } }\n";

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "glBindVertexArray",
                staticCallStderr("org.lwjgl.opengl.GL11", "glBindVertexArray"),
                context(Map.of("src/main/java/com/flightsim/A.java", a,
                               "src/main/java/com/flightsim/B.java", b)));

        assertThat(proposal).isPresent();
        assertThat(proposal.get().mutations()).hasSize(2);
        assertThat(proposal.get().mutations())
                .allSatisfy(m -> assertThat(m.content()).contains("GL30.glBindVertexArray"));
    }

    @Test
    void theProposalRecordsWhatItDidAndWhichFilesItTouched() {
        when(declarations.declaringSibling(any(), any(), any()))
                .thenReturn(Optional.of(new ClasspathMemberDeclarationService.Declaration(
                        "org.lwjgl.opengl.GL11", "glUseProgram", "org.lwjgl.opengl.GL20")));

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "glUseProgram",
                staticCallStderr("org.lwjgl.opengl.GL11", "glUseProgram"),
                context(Map.of("src/main/java/com/flightsim/Renderer.java",
                        "package com.flightsim;\npublic class Renderer { void f() { GL11.glUseProgram(1); } }\n")));

        assertThat(proposal).isPresent();
        InjectorFixRecord record = proposal.get().record();
        assertThat(record.rule()).isEqualTo("misplaced-member:glUseProgram");
        assertThat(record.kind()).isEqualTo(Kind.CANNOT_FIND_SYMBOL);
        assertThat(record.touchedPaths()).containsExactly("src/main/java/com/flightsim/Renderer.java");
        assertThat(record.description()).contains("GL11.glUseProgram -> GL20.glUseProgram");
    }

    // ── the receiver's package is not always in the diagnostic ───────────────

    /**
     * javac reports an IMPORTED type by simple name: {@code location: class GL11}, no package.
     * Both shapes appear in the flight-simulator run's own logs, and an end-to-end replay against
     * real javac produced this one — the resolver saw a null package and silently proposed
     * nothing while every unit test stayed green.
     */
    @Test
    void aReceiverNamedBySimpleNameOnly_isResolvedThroughTheProjectsOwnImports() {
        when(declarations.declaringSibling(eq("org.lwjgl.opengl.GL11"), eq("glBindVertexArray"), any()))
                .thenReturn(Optional.of(new ClasspathMemberDeclarationService.Declaration(
                        "org.lwjgl.opengl.GL11", "glBindVertexArray", "org.lwjgl.opengl.GL30")));

        String stderr = String.join("\n",
                "/out/src/main/java/com/flightsim/WorldObject.java:40: error: cannot find symbol",
                "  symbol:   method glBindVertexArray(int)",
                "  location: class GL11");
        String source = "package com.flightsim;\nimport org.lwjgl.opengl.GL11;\n"
                      + "public class WorldObject { void f(int vao) { GL11.glBindVertexArray(vao); } }\n";

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "glBindVertexArray", stderr,
                context(Map.of("src/main/java/com/flightsim/WorldObject.java", source)));

        assertThat(proposal).isPresent();
        assertThat(proposal.get().mutations().get(0).content()).contains("GL30.glBindVertexArray(vao);");
    }

    @Test
    void aSimpleNameNoImportExplains_isNotGuessedAt() {
        // An on-demand import leaves the package genuinely unknown; guessing it would defeat the
        // point of an exact lookup.
        String stderr = String.join("\n",
                "/out/R.java:1: error: cannot find symbol",
                "  symbol:   method glBindVertexArray(int)",
                "  location: class GL11");
        String source = "package com.flightsim;\nimport org.lwjgl.opengl.*;\n"
                      + "public class R { void f() { GL11.glBindVertexArray(1); } }\n";

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "glBindVertexArray", stderr,
                context(Map.of("src/main/java/R.java", source)));

        assertThat(proposal).isEmpty();
        verify(declarations, never()).declaringSibling(any(), any(), any());
    }

    @Test
    void twoFilesImportingDifferentTypesOfTheSameName_abstain() {
        String stderr = String.join("\n",
                "/out/A.java:1: error: cannot find symbol",
                "  symbol:   method render(int)",
                "  location: class Surface");

        Optional<FixProposal> proposal = resolver.resolve(CANNOT_FIND_SYMBOL, "render", stderr,
                context(Map.of(
                        "src/main/java/A.java", "package p;\nimport one.Surface;\npublic class A {}\n",
                        "src/main/java/B.java", "package p;\nimport two.Surface;\npublic class B {}\n")));

        assertThat(proposal).isEmpty();
        verify(declarations, never()).declaringSibling(any(), any(), any());
    }

    // ── import handling ──────────────────────────────────────────────────────

    @Test
    void anOnDemandImportOfThePackage_needsNoNewImport() {
        String source = "package com.flightsim;\nimport org.lwjgl.opengl.*;\n"
                      + "public class R { void f() { GL11.glUseProgram(1); } }\n";

        String out = MisplacedMemberResolver.ensureImport(source, "org.lwjgl.opengl.GL20", "GL20");

        assertThat(out).isEqualTo(source);
    }

    @Test
    void anExistingImportIsNotDuplicated() {
        String source = "package com.flightsim;\nimport org.lwjgl.opengl.GL20;\n"
                      + "public class R { void f() { GL20.glUseProgram(1); } }\n";

        String out = MisplacedMemberResolver.ensureImport(source, "org.lwjgl.opengl.GL20", "GL20");

        assertThat(out).isEqualTo(source);
    }

    @Test
    void aDifferentTypeOfTheSameSimpleNameBlocksTheImport() {
        // Adding `import org.lwjgl.opengl.GL20;` beside `import com.other.GL20;` does not compile,
        // and silently binding to the wrong one would be worse.
        String source = "package com.flightsim;\nimport com.other.GL20;\npublic class R {}\n";

        String out = MisplacedMemberResolver.ensureImport(source, "org.lwjgl.opengl.GL20", "GL20");

        assertThat(out).isEqualTo(source);
    }

    @Test
    void aFileWithNoImportsGetsOneAfterThePackageDeclaration() {
        String source = "package com.flightsim;\n\npublic class R { void f() { GL20.glUseProgram(1); } }\n";

        String out = MisplacedMemberResolver.ensureImport(source, "org.lwjgl.opengl.GL20", "GL20");

        assertThat(out).contains("package com.flightsim;");
        assertThat(out).contains("import org.lwjgl.opengl.GL20;");
        assertThat(out.indexOf("package")).isLessThan(out.indexOf("import"));
        assertThat(out.indexOf("import")).isLessThan(out.indexOf("public class"));
    }
}
