package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.services.ServiceProposal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EO: the claim proposer is told what exists at each start -- from the operator's recorded answer, never guessed. */
class StartStateTest {

    private static ServiceProposal service(String script) {
        return new ServiceProposal("db", "store:1", 1234, 1234, Map.of(), "/init", List.of("setup.sql"), script, "model",
                List.of(), null);
    }

    @Test
    void aTickedSetupScriptIsQuotedAndAnEmptyServiceIsSaidToBeEmpty(@TempDir Path tree) throws Exception {
        Files.writeString(tree.resolve("setup.sql"), "INSERT INTO people VALUES ('ada');\n");

        String ticked = ReverseEngineeringService.startState(List.of(service("setup.sql")), tree);
        String empty = ReverseEngineeringService.startState(List.of(service(null)), tree);

        assertThat(ticked).contains("filled first by the project's own script setup.sql").contains("VALUES ('ada')");
        assertThat(empty).contains("starts EMPTY").doesNotContain("ada");
        assertThat(ReverseEngineeringService.startState(List.of(), tree)).isNull();
    }

    @Test
    void aScriptThatCannotBeQuotedIsNamedAndAQuestionCarriesTheStartState(@TempDir Path tree) {
        String named = ReverseEngineeringService.startState(List.of(service("setup.sql")), tree);
        assertThat(named).contains("setup.sql -- read it");

        var capability = new com.walpro.autobuilder.core.merge.CapabilityCut.Capability("c1", "things", "keeps things",
                List.of(), List.of(), false, null);
        String with = ClaimProposer.question(Map.of(), Map.of(), capability, List.of(capability), null, Map.of(), named);
        String without = ClaimProposer.question(Map.of(), Map.of(), capability, List.of(capability), null, Map.of());
        assertThat(named).startsWith("WHAT EXISTS EACH TIME IT IS STARTED FOR AN EXERCISE:");
        assertThat(with).contains("WHAT EXISTS EACH TIME IT IS STARTED FOR AN EXERCISE:").contains("setup.sql");
        assertThat(without).doesNotContain("WHAT EXISTS EACH TIME");
    }
}
