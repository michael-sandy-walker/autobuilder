package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.build.AdoptionManifest;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.investigate.LogInvestigator;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import com.walpro.autobuilder.core.merge.CapabilityCut.Choice;
import com.walpro.autobuilder.core.merge.CapabilityCut.Mode;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.SeedObligationsGenerator;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-EI: a capability import -- discovered, chosen, narrowed, and brought in through the ordinary import, which keeps and
 * cancels it as any other.
 */
class CapabilityImportServiceTest {

    @TempDir
    Path base;
    @TempDir
    Path source;

    private FileService files;
    private GoalPlannerService goals;
    private ProjectInputService inputs;
    private CapabilityImportService service;
    private final LogInvestigator investigator = mock(LogInvestigator.class);

    private static final String P = "output/src/main/java/org/lend/";
    private static final String ANSWER = """
            CAPABILITY: In-memory store | keeps loans and gives each an id
            DECLARATIONS: LoanRepository, Loan
            DEPENDENCIES: none
            REFERENCE: no | a list with ids
            CAPABILITY: Lending rules | at most 3 active loans a member
            DECLARATIONS: LoanService, LoanRepository.countActive, Loan, NoSuchThing
            DEPENDENCIES: none
            REFERENCE: yes | the rule sits across two types
            CAPABILITY: Typed refusals | a refusal is an exception of its own, with its message
            DECLARATIONS: LimitException
            DEPENDENCIES: none
            REFERENCE: yes | small, but the mapping matters
            """;

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        files = new FileService(base.toString(), new ObjectMapper());
        write(base.resolve("output/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>o</groupId><artifactId>ledger</artifactId><version>1</version></project>");
        write(base.resolve("output/src/main/java/org/ledger/Ledger.java"), "package org.ledger;\npublic class Ledger { }\n");
        write(source.resolve(P + "Loan.java"), "package org.lend;\npublic class Loan {\n    private boolean returned;\n    public boolean isReturned() { return returned; }\n}\n");
        write(source.resolve(P + "LoanRepository.java"), """
                package org.lend;

                import java.util.ArrayList;
                import java.util.List;

                public class LoanRepository {
                    private final List<Loan> loans = new ArrayList<>();
                    public Loan save(Loan loan) { loans.add(loan); return loan; }
                    public long countActive() { return loans.stream().filter(l -> !l.isReturned()).count(); }
                }
                """);
        write(source.resolve(P + "LoanService.java"), """
                package org.lend;

                public class LoanService {
                    private final LoanRepository repository = new LoanRepository();
                    public Loan lend(Loan loan) {
                        if (repository.countActive() >= 3) throw new LimitException("at most 3");
                        return repository.save(loan);
                    }
                }
                """);
        write(source.resolve(P + "LimitException.java"), "package org.lend;\npublic class LimitException extends RuntimeException {\n    public LimitException(String m) { super(m); }\n}\n");
        write(source.resolve("meta/GoalStack.json"), """
                {"goalTree": {"id": "r", "description": "Lend books", "status": "COMPLETED", "subGoals": [
                  {"id": "s", "description": "At most 3 active loans", "status": "COMPLETED", "subGoals": []}]}}
                """);
        write(source.resolve("meta/run-identity.json"), "{\"lineageId\": \"must-not-travel\"}");

        goals = new GoalPlannerService(files, mock(GoalGraphService.class), mock(GoalPlannerConfigProperties.class));
        goals.init();
        goals.addInitialGoal("root", "Keep a ledger.");
        goals.getRootGoal().setStatus(GoalStatus.COMPLETED);
        TestRunGate gate = mock(TestRunGate.class);
        when(gate.runOnTree(anyString(), anyBoolean())).thenReturn(Optional.empty());
        when(gate.freshReport()).thenReturn(Optional.empty());
        CriteriaVerificationService verifier = mock(CriteriaVerificationService.class);
        when(verifier.verifyWithTestRun(anyString(), any(), any())).thenReturn(Map.of());
        ObjectProvider<SeedObligationsGenerator> none = mock(ObjectProvider.class);
        inputs = new ProjectInputService(goals, files, new AdoptionManifest(files), gate, verifier, none, mock(InputComparison.class));
        // Discovery answers ANSWER; the cut answers for whichever capability is taken as code.
        when(investigator.investigate(anyString(), eq(Map.of()), any(), anyInt(), anyString())).thenAnswer(call -> {
            String q = call.getArgument(0);
            String answer = !q.contains("TAKEN AS CODE:") ? ANSWER
                    : q.contains("TAKEN AS CODE:\n- Lending rules") ? "TAKE: LoanService, LoanRepository, Loan\nLEAVE: none"
                    : "TAKE: LoanRepository, Loan\nLEAVE: LoanRepository.countActive (Lending rules)";
            return new LogInvestigator.Investigation(q, List.of(new LogInvestigator.Step("#Probe files .*", "4 files")), answer, true);
        });
        service = new CapabilityImportService(files, new CapabilityDiscovery(investigator));
    }

    private CapabilityImportService.Selection discovered() throws IOException {
        return service.discover("lending", "lineage-l", source, "operator");
    }

    @Test
    void discoveryListsTheCapabilities_citedByDeclaration_andDropsWhatTheSourceDoesNotDeclare() throws IOException {
        CapabilityImportService.Selection s = discovered();

        assertThat(s.capabilities()).extracting(Capability::name).containsExactly("In-memory store", "Lending rules", "Typed refusals");
        assertThat(s.capabilities().get(1).declarations()).containsExactly("LoanService", "LoanRepository.countActive", "Loan");
        assertThat(s.capabilities().get(1).reference()).isTrue();
        assertThat(s.unknown()).containsExactly("NoSuchThing");
        assertThat(s.probes()).containsExactly("#Probe files .*");
        assertThat(service.get(s.id()).capabilities()).hasSize(3);
        assertThat(service.open()).extracting(CapabilityImportService.Selection::id).containsExactly(s.id());
        assertThat(base.resolve("output/src/main/java/org/lend")).as("discovery touches nothing").doesNotExist();
    }

    @Test
    void theGoalsAndTheDeclarationsAreWhatTheModelIsGiven() {
        String goalsText = CapabilityImportService.goalsOf(source);
        assertThat(goalsText).isEqualTo("- Lend books [COMPLETED]\n- At most 3 active loans [COMPLETED]");
        String q = CapabilityDiscovery.question(CapabilityCut.inventory(Map.of("src/main/java/org/lend/Loan.java",
                "package org.lend;\npublic class Loan { public boolean isReturned() { return false; } }")), goalsText);
        assertThat(q).contains("- At most 3 active loans [COMPLETED]", "src/main/java/org/lend/Loan.java",
                "  Loan.isReturned  --  public boolean isReturned()", "CAPABILITY: <a short name>");
    }

    @Test
    void choicesNeedAPurpose_andThePreviewSaysWhatTheyBringIn() throws IOException {
        CapabilityImportService.Selection s = discovered();
        assertThatThrownBy(() -> service.choose(s.id(), List.of(new Choice("c1", Mode.CODE, " ", false))))
                .hasMessageContaining("say what In-memory store is for here");

        CapabilityImportService.Selection chosen = service.choose(s.id(), List.of(
                new Choice("c1", Mode.CODE, "Keep the ledger's records in it.", false),
                new Choice("c3", Mode.ADAPT, "Refusals of the ledger become typed, with their message.", true)));

        CapabilityImportService.Preview p = chosen.preview();
        assertThat(p.problems()).isEmpty();
        assertThat(p.files()).containsExactly("src/main/java/org/lend/Loan.java", "src/main/java/org/lend/LoanRepository.java");
        assertThat(p.cutOut()).singleElement().asString().startsWith("LoanRepository.countActive -- belongs to Lending rules");
        assertThat(p.intent()).isEqualTo("""
                Capabilities taken from lending, as chosen:
                - In-memory store, taken as code (its code is placed in this project): Keep the ledger's records in it.
                - Typed refusals, adapted -- re-implemented in this project's own structure, none of lending's code for it placed; \
                lending's source is kept to read while doing it: Refusals of the ledger become typed, with their message.
                Not taken (nothing is to be done about them; code placed for a capability taken as code stays): Lending rules.""");
    }

    /**
     * Field 2026-11-06: discovery listed the store's repositories but put the types they hold under other capabilities,
     * and listed a repository whole under the lending rules too. The cut is the model's, made for the choices: it takes
     * what the store needs and names the rule's member to leave out; structure checks it.
     */
    @Test
    void theCutIsTheModels_forTheChoices_notDiscoverysAttribution() throws IOException {
        Capability store = new Capability("c1", "Store", "ids", List.of("LoanRepository"), List.of(), false, null);
        Capability rules = new Capability("c2", "Lending rules", "3 at most", List.of("LoanService", "LoanRepository", "Loan"), List.of(), false, null);
        CapabilityDiscovery.Cut cut = CapabilityDiscovery.parseCut(
                "TAKE: LoanRepository, Loan, Nope\nLEAVE: LoanRepository.countActive (Lending rules)",
                CapabilityCut.inventory(Map.of("src/main/java/org/lend/LoanRepository.java",
                        Files.readString(source.resolve(P + "LoanRepository.java")),
                        "src/main/java/org/lend/Loan.java", Files.readString(source.resolve(P + "Loan.java")))), List.of());
        assertThat(cut.take()).containsExactly("LoanRepository", "Loan");
        assertThat(cut.leave()).containsEntry("LoanRepository.countActive", "Lending rules");
        assertThat(cut.unknown()).containsExactly("Nope");

        List<Capability> refined = CapabilityImportService.refined(List.of(store, rules),
                List.of(new Choice("c1", Mode.CODE, "keep", false)), cut);
        assertThat(refined.get(0).declarations()).containsExactly("LoanRepository", "Loan");
        assertThat(refined.get(1).declarations()).as("what it listed stays listed, so the overlap is shown")
                .containsExactly("LoanService", "LoanRepository", "Loan", "LoanRepository.countActive");

        CapabilityImportService.Selection s = discovered();
        CapabilityImportService.Selection chosen = service.choose(s.id(), List.of(new Choice("c1", Mode.CODE, "Keep records.", false)));
        assertThat(chosen.cut()).isNotNull();
        assertThat(chosen.cutProbes()).containsExactly("#Probe files .*");
        assertThat(chosen.preview().problems()).isEmpty();
        assertThat(chosen.preview().shared()).anyMatch(x -> x.startsWith("Loan -- brought in for In-memory store; Lending rules lists it too"));
        assertThat(chosen.preview().cutOut()).anyMatch(x -> x.startsWith("LoanRepository.countActive -- belongs to Lending rules"));
    }

    @Test
    void choicesThatCannotBeMetAreRefusedBeforeAnythingLands() throws IOException {
        CapabilityImportService.Selection s = discovered();
        service.choose(s.id(), List.of(new Choice("c2", Mode.CODE, "the rule", false)));

        assertThatThrownBy(() -> service.request(s.id())).hasMessageContaining("cannot be met")
                .hasMessageContaining("LimitException");
        assertThat(goals.purpose().ledger().entries()).hasSize(1);
    }

    @Test
    void theNarrowedInputLands_pending_withWhatCameFromWhichCapability_andTheSourceKeptToRead() throws IOException {
        CapabilityImportService.Selection s = discovered();
        service.choose(s.id(), List.of(new Choice("c1", Mode.CODE, "Keep the ledger's records in it.", false),
                new Choice("c3", Mode.ADAPT, "Refusals become typed.", true)));

        ProjectInputService.Report r = inputs.bring(service.request(s.id()));
        service.landed(s.id(), r.inputId());

        assertThat(base.resolve("output/src/main/java/org/lend/LoanRepository.java")).content()
                .contains("save").doesNotContain("countActive");
        assertThat(base.resolve("output/src/main/java/org/lend/LoanService.java")).doesNotExist();
        assertThat(base.resolve("output/src/main/java/org/lend/LimitException.java")).as("adapted: none of its code").doesNotExist();
        assertThat(r.placed()).containsExactly("src/main/java/org/lend/Loan.java", "src/main/java/org/lend/LoanRepository.java");
        assertThat(goals.purpose().ledger().entries().get(1).content()).startsWith("Capabilities taken from lending, as chosen:");

        JsonNode record = service.record(r.inputId());
        assertThat(record.path("capabilities").get(0).path("placed")).hasSize(2);
        assertThat(record.path("capabilities").get(2).path("mode").asText()).isEqualTo("adapt");
        assertThat(record.path("cutOut").get(0).asText()).startsWith("LoanRepository.countActive");
        Path reference = base.resolve("meta/inputs/" + r.inputId() + "/reference");
        assertThat(reference.resolve("src/main/java/org/lend/LoanService.java")).exists();
        assertThat(service.get(s.id()).state()).isEqualTo("imported");

        inputs.keep(r.inputId(), "operator");
        assertThat(reference).as("kept, the source stays readable").isDirectory();
        assertThat(base.resolve("meta/inputs/" + r.inputId() + "/capabilities.json")).exists();
        assertThat(source.resolve("output/src/main/java/org/lend/LoanRepository.java")).as("the source is unchanged")
                .content().contains("countActive");
    }

    /**
     * Field 2026-11-06: split into criteria by a model, "the Book resource is not taken on" was met by deleting the code
     * taken as code, and "the source is kept to read" could never be confirmed. The import writes its obligations: one
     * checkable line per chosen capability, the rest rules that guide and never block closure.
     */
    @Test
    void theCriteriaAreTheDescriptions_whatIsLeftOutAndTheReferenceOnlyGuide() throws IOException {
        CapabilityImportService.Selection s = discovered();
        service.choose(s.id(), List.of(new Choice("c1", Mode.CODE, "Keep the ledger's records in it.", false),
                new Choice("c3", Mode.ADAPT, "Refusals become typed.", true)));

        ProjectInputService.Request request = service.request(s.id());
        assertThat(request.obligations()).containsExactly(
                "In-memory store: Keep the ledger's records in it.",
                "Typed refusals: Refusals become typed.",
                "rule: The code placed for In-memory store was chosen by the operator and stays in this project; make it work here, do not remove it.",
                "rule: Not taken from lending: Lending rules. Nothing is to be done about them -- and code placed for a"
                        + " capability taken as code stays, even where one of these shares its names.",
                "rule: lending's source is kept read-only beside this project, to read while adapting; it is not part of this project.");

        ProjectInputService.Report r = inputs.bring(request);
        JsonNode landing = new ObjectMapper().readTree(files.readFile("meta/inputs/" + r.inputId() + "/landing.json"));
        List<String> criteria = new java.util.ArrayList<>();
        landing.path("criteria").forEach(c -> criteria.add(c.asText()));
        assertThat(criteria).containsExactly("In-memory store: Keep the ledger's records in it.", "Typed refusals: Refusals become typed.");
    }

    @Test
    void cancelledAfterItsImport_itLeavesNothing_theRecordStays() throws IOException {
        CapabilityImportService.Selection s = discovered();
        service.choose(s.id(), List.of(new Choice("c1", Mode.CODE, "Keep records.", false),
                new Choice("c3", Mode.IDEA, "Typed refusals.", true)));
        ProjectInputService.Report r = inputs.bring(service.request(s.id()));
        service.landed(s.id(), r.inputId());

        inputs.cancel(r.inputId(), "operator");

        assertThat(base.resolve("output/src/main/java/org/lend")).isEmptyDirectory();
        assertThat(base.resolve("meta/inputs/" + r.inputId() + "/reference")).doesNotExist();
        assertThat(service.record(r.inputId()).path("source").asText()).isEqualTo("lending");
    }

    @Test
    void ideasAloneBringNoCode_andStillLand() throws IOException {
        CapabilityImportService.Selection s = discovered();
        service.choose(s.id(), List.of(new Choice("c3", Mode.IDEA, "Refusals become typed.", false)));

        ProjectInputService.Report r = inputs.bring(service.request(s.id()));
        service.landed(s.id(), r.inputId());
        inputs.keep(r.inputId(), "operator");

        assertThat(r.placed()).isEmpty();
        GoalEntity landed = goals.getAllGoals().stream().filter(g -> g.getMetadata() != null
                && r.inputId().equals(g.getMetadata().get("inputId"))).findFirst().orElseThrow();
        assertThat(landed.getDescription()).contains("Typed refusals, as an idea only");
        assertThat(base.resolve("meta/inputs/" + r.inputId() + "/reference")).doesNotExist();
    }

    @Test
    void theReferenceIsOfferedOnlyUnderTheImportsGoal_andAnsweredFromTheKeptSource() throws IOException {
        ReferenceConsultation consultation = new ReferenceConsultation(goals, files, service, investigator);
        CapabilityImportService.Selection s = discovered();
        service.choose(s.id(), List.of(new Choice("c3", Mode.ADAPT, "Refusals become typed.", true)));
        ProjectInputService.Report r = inputs.bring(service.request(s.id()));
        service.landed(s.id(), r.inputId());
        assertThat(consultation.offerBlock()).as("pending: nothing works under it yet").isNull();

        inputs.keep(r.inputId(), "operator");
        GoalEntity landed = goals.getCurrentSubGoal();
        assertThat(landed.getMetadata().get("inputId")).isEqualTo(r.inputId());
        goals.addSubGoal("sub-1", "Make the ledger's refusals typed", true, landed.getId(), "planner", null);
        assertThat(goals.getCurrentSubGoal().getId()).isEqualTo("sub-1");

        String offer = consultation.offerBlock();
        assertThat(offer).contains("another project, lending", "Typed refusals (adapt): Refusals become typed.",
                ReferenceConsultation.MARKER + " <your question>");
        assertThat(consultation.answer("a change", "cycle-1")).isNull();
        String answer = consultation.answer(ReferenceConsultation.MARKER + " How is a refusal typed?", "cycle-1");
        assertThat(answer).contains("ANSWER FROM THE REFERENCE PROJECT lending", "Now reply with your change to THIS project");
        org.mockito.Mockito.verify(investigator).investigate(eq("How is a refusal typed?"), eq(Map.of()),
                eq(base.resolve("meta/inputs/" + r.inputId() + "/reference")), anyInt(), eq("cycle-1"));

        goals.addSubGoal("other", "Something else", true, "root", "planner", null);
        assertThat(goals.getCurrentSubGoal().getId()).isEqualTo("other");
        assertThat(consultation.offerBlock()).as("outside the import's goal there is nothing to adapt").isNull();
    }

    @Test
    void aSelectionCancelledBeforeItsImportTouchedNothing() throws IOException {
        CapabilityImportService.Selection s = discovered();
        service.choose(s.id(), List.of(new Choice("c1", Mode.CODE, "Keep records.", false)));

        assertThat(service.cancel(s.id()).state()).isEqualTo("cancelled");
        assertThat(service.open()).isEmpty();
        assertThatThrownBy(() -> service.request(s.id())).hasMessageContaining("cancelled already");
        assertThat(goals.purpose().ledger().entries()).hasSize(1);
    }
}
