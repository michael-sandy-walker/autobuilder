package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CQ: a call to an accessor a project record does not declare becomes the one it does. Invented
 * coordinates; the stderr shape is structdiff run 2026-09-25_03-03-00's, byte for byte in form.
 */
class DeclaredAccessorResolverTest {

    private static final String RECORD_PATH = "src/main/java/shop/model/Gadget.java";
    private static final String RECORD = "package shop.model;\n\npublic record Gadget(String id, String kind, java.util.List<Gadget> parts) {}\n";
    private static final String TEST_PATH = "src/test/java/shop/GadgetTest.java";
    private static final Classification CANNOT_FIND_SYMBOL = new Classification(Kind.CANNOT_FIND_SYMBOL, List.of());

    @TempDir
    Path root;

    private Optional<FixProposal> resolve(String test, String stderr) throws Exception {
        Files.createDirectories(root.resolve(TEST_PATH).getParent());
        Files.writeString(root.resolve(TEST_PATH), test);
        CycleContextData ctx = CycleContextData.builder().cycleName("c").outputPath(root.toString())
                .previousOutput(Map.of(RECORD_PATH, RECORD)).build();
        return new DeclaredAccessorResolver().resolve(CANNOT_FIND_SYMBOL, "getKind", stderr, ctx);
    }

    private String stderr(int line, String member, String variable) {
        return "[ERROR] " + root + "/" + TEST_PATH + ":[" + line + ",27] cannot find symbol\n"
                + "[ERROR]   symbol:   method " + member + "()\n"
                + "[ERROR]   location: variable " + variable + " of type shop.model.Gadget\n";
    }

    @Test
    void aJavaBeanGetterOnARecordBecomesTheRecordAccessor() throws Exception {
        String test = "class GadgetTest {\n  void t(Gadget g) {\n    assertEquals(\"x\", g.getKind());\n    g.getParts().size();\n  }\n}\n";

        Optional<FixProposal> fix = resolve(test, stderr(3, "getKind", "g") + stderr(4, "getParts", "g"));

        assertThat(fix).isPresent();
        String fixed = fix.get().mutations().get(0).content();
        assertThat(fixed).contains("g.kind()").contains("g.parts().size()").doesNotContain("getKind").doesNotContain("getParts");
        assertThat(fixed).isEqualTo(test.replace("g.getKind()", "g.kind()").replace("g.getParts()", "g.parts()"));
        assertThat(fix.get().mutations().get(0).path()).isEqualTo(TEST_PATH);
        assertThat(fix.get().record().rule()).isEqualTo("declared-accessor:getKind");
    }

    /** No declared member is the accessor's name: nothing to copy, so the model keeps the job. */
    @Test
    void anAccessorNamingNothingDeclaredIsLeftAlone() throws Exception {
        String test = "class GadgetTest {\n  void t(Gadget g) {\n    g.getColour();\n  }\n}\n";

        assertThat(resolve(test, stderr(3, "getColour", "g"))).isEmpty();
    }

    /** A type outside the project has no source here, so there is no authority to rewrite by. */
    @Test
    void aTypeWhoseSourceIsNotInTheProjectIsLeftAlone() throws Exception {
        String test = "class GadgetTest {\n  void t(Other o) {\n    o.getKind();\n  }\n}\n";
        String err = stderr(3, "getKind", "o").replace("shop.model.Gadget", "lib.Other");

        assertThat(resolve(test, err)).isEmpty();
    }

    @Test
    void theClassifierReadsThisStderrAsAMissingSymbol() {
        String err = "[ERROR] /x/src/test/java/shop/GadgetTest.java:[3,27] cannot find symbol\n"
                + "[ERROR]   symbol:   method getKind()\n"
                + "[ERROR]   location: variable g of type shop.model.Gadget\n";
        assertThat(BuildErrorClassifier.classify(err).kind()).isEqualTo(Kind.CANNOT_FIND_SYMBOL);
    }
}
