package com.walpro.autobuilder.core.merge;

import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import com.walpro.autobuilder.core.merge.CapabilityCut.Choice;
import com.walpro.autobuilder.core.merge.CapabilityCut.Mode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EI: a capability import's cut is made by declaration, and refuses rather than guess. */
class CapabilityCutTest {

    private static final String P = "src/main/java/org/lend/";

    /** A store whose repository also answers the lending rule's questions -- the coupling a file-level cut misses. */
    private static final Map<String, String> SOURCE = Map.of(
            P + "Loan.java", """
                    package org.lend;

                    public class Loan {
                        private Long id;
                        private boolean returned;
                        public Long getId() { return id; }
                        public void setId(Long id) { this.id = id; }
                        public boolean isReturned() { return returned; }
                    }
                    """,
            P + "LoanRepository.java", """
                    package org.lend;

                    import java.util.ArrayList;
                    import java.util.List;

                    public class LoanRepository {
                        private final List<Loan> loans = new ArrayList<>();

                        public Loan save(Loan loan) {
                            loan.setId((long) loans.size() + 1);
                            loans.add(loan);
                            return loan;
                        }

                        /** The lending rule asks this. */
                        public long countActive() {
                            return loans.stream().filter(l -> !l.isReturned()).count();
                        }
                    }
                    """,
            P + "LoanService.java", """
                    package org.lend;

                    public class LoanService {
                        private final LoanRepository repository = new LoanRepository();

                        public Loan lend(Loan loan) {
                            if (repository.countActive() >= 3) throw new LimitException("at most 3");
                            return repository.save(loan);
                        }
                    }
                    """,
            P + "LimitException.java", """
                    package org.lend;

                    public class LimitException extends RuntimeException {
                        public LimitException(String message) { super(message); }
                    }
                    """);

    private static final Capability STORE = new Capability("c1", "In-memory store", "keeps loans with ids",
            List.of("LoanRepository", "LoanRepository.save", "Loan"), List.of(), false, null);
    private static final Capability RULES = new Capability("c2", "Lending rules", "at most 3 active loans",
            List.of("LoanService", "LoanRepository.countActive", "Loan"), List.of(), false, null);
    private static final Capability ERRORS = new Capability("c3", "Typed errors", "a refusal is an exception of its own",
            List.of("LimitException"), List.of(), true, "small");

    @Test
    void theInventoryNamesTypesAndMembers() {
        Map<String, CapabilityCut.Declaration> inv = CapabilityCut.inventory(SOURCE);
        assertThat(inv).containsKeys("Loan", "Loan.getId", "LoanRepository", "LoanRepository.save",
                "LoanRepository.countActive", "LoanRepository.loans", "LoanService.lend", "LimitException");
        assertThat(inv.get("LoanRepository.save").signature()).isEqualTo("public Loan save(Loan loan)");
        assertThat(inv.get("LoanRepository.save").path()).isEqualTo(P + "LoanRepository.java");
    }

    @Test
    void aMemberOfALeftOutCapabilityIsCutOutOfAFileThatComesIn() {
        CapabilityCut.Result r = CapabilityCut.cut(SOURCE, List.of(STORE, RULES, ERRORS),
                List.of(new Choice("c1", Mode.CODE, "keep loans here", false), new Choice("c3", Mode.ADAPT, "typed refusals", true)));

        assertThat(r.problems()).isEmpty();
        assertThat(r.files()).containsOnlyKeys(P + "Loan.java", P + "LoanRepository.java");
        String repo = r.files().get(P + "LoanRepository.java");
        assertThat(repo).contains("public Loan save(Loan loan)").doesNotContain("countActive", "lending rule");
        assertThat(r.cutOut()).singleElement().asString().startsWith("LoanRepository.countActive -- belongs to Lending rules");
        assertThat(r.shared()).as("the type both list comes in for the store, and that is shown")
                .anyMatch(s -> s.startsWith("Loan -- brought in for In-memory store; Lending rules lists it too"));
        assertThat(r.unattributed()).as("a listed type covers its members").isEmpty();
        assertThat(r.provenance().get(P + "LoanRepository.java")).containsExactly("In-memory store");
    }

    @Test
    void whatNobodyListedTravelsWithItsFile_named() {
        Capability saving = new Capability("c1", "Saving", "", List.of("LoanRepository.save", "Loan"), List.of(), false, null);
        CapabilityCut.Result r = CapabilityCut.cut(SOURCE, List.of(saving, RULES),
                List.of(new Choice("c1", Mode.CODE, "", false)));

        assertThat(r.unattributed()).containsExactly("LoanRepository", "LoanRepository.loans");
        assertThat(r.files().get(P + "LoanRepository.java")).contains("loans").doesNotContain("countActive");
    }

    @Test
    void keptCodeThatStillNeedsWhatWasLeftOutIsRefused() {
        CapabilityCut.Result r = CapabilityCut.cut(SOURCE, List.of(STORE, RULES, ERRORS),
                List.of(new Choice("c2", Mode.CODE, "the rule", false), new Choice("c3", Mode.LEAVE_OUT, "", false)));

        assertThat(r.problems()).anyMatch(p -> p.startsWith(P + "LoanService.java uses LimitException, which is not brought in")
                && p.contains("Typed errors"));
    }

    @Test
    void aMemberCalledByKeptCodeCannotBeCutOut() {
        Capability rulesOnly = new Capability("c2", "Lending rules", "", List.of("LoanService", "LimitException"), List.of(), false, null);
        Capability counting = new Capability("c4", "Counting", "", List.of("LoanRepository.countActive"), List.of(), false, null);
        CapabilityCut.Result r = CapabilityCut.cut(SOURCE, List.of(STORE, rulesOnly, counting),
                List.of(new Choice("c1", Mode.CODE, "", false), new Choice("c2", Mode.CODE, "", false)));

        assertThat(r.problems()).anyMatch(p -> p.startsWith(P + "LoanService.java still uses countActive, which belongs to Counting"));
    }

    @Test
    void nothingTakenAsCodeBringsNoFiles() {
        CapabilityCut.Result r = CapabilityCut.cut(SOURCE, List.of(STORE, RULES, ERRORS),
                List.of(new Choice("c3", Mode.IDEA, "refusals as types", false)));
        assertThat(r.files()).isEmpty();
        assertThat(r.problems()).isEmpty();
    }
}
