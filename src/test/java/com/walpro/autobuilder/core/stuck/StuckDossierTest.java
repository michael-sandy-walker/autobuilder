package com.walpro.autobuilder.core.stuck;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-DY dossier: a diagnosis request becomes a dossier of the facts the loop holds, the model picks one
 * move from the menu, and each move reaches its actuator. Invented coordinates only.
 */
class StuckDossierTest {

    private final DossierDecider decider = mock(DossierDecider.class);
    private final GoalPlannerService planner = mock(GoalPlannerService.class);
    private final BehaviourLogger events = mock(BehaviourLogger.class);

    @SuppressWarnings("unchecked")
    private StuckLayer layer() {
        StuckLayer layer = new StuckLayer();
        ObjectProvider<GoalPlannerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(planner);
        ReflectionTestUtils.setField(layer, "dossierDecider", decider);
        ReflectionTestUtils.setField(layer, "goalPlannerService", provider);
        ReflectionTestUtils.setField(layer, "behaviourLogger", events);
        when(decider.available()).thenReturn(true);
        Map<String, Boolean> criteria = new LinkedHashMap<>();
        criteria.put("the quill writes blue", true);
        criteria.put("the quill dries in three seconds", false);
        criteria.put("an empty well is refused", false);
        when(planner.criteriaStatus("sg-ink")).thenReturn(new LinkedHashMap<>(criteria));
        return layer;
    }

    private static CycleContextData cycle(String name) {
        return CycleContextData.builder().cycleName(name).currentSubGoalId("sg-ink")
                .currentSubGoalDescription("Make the quill dry quickly")
                .rejectionFeedback(List.of("the drying time is still five seconds")).build();
    }

    /** A flat root has no question in the loop: the request is raised at once and waits for the next cycle. */
    private StuckLayer flatRoot(StuckLayer layer) {
        layer.beginCycle("c7", "sg-ink");
        layer.report(StuckObservation.Source.FLAT_ROOT, "the root has not moved for half its window", List.of());
        return layer;
    }

    @Test
    void theDossierCarriesTheFactsTheLoopHolds() {
        StuckLayer layer = flatRoot(layer());
        when(decider.decide(any(), any())).thenReturn(DossierDecision.none("nothing helps"));

        layer.consultDossier(cycle("c8"));

        var captor = org.mockito.ArgumentCaptor.forClass(StuckDossier.class);
        verify(decider).decide(captor.capture(), eq("c8"));
        String shown = captor.getValue().render();
        assertThat(shown).contains("FLAT_ROOT", "the root has not moved for half its window",
                "Make the quill dry quickly", "[met] the quill writes blue", "[not met] an empty well is refused",
                "the drying time is still five seconds");
        assertThat(shown).as("an empty section is named as empty").contains("FAILING TESTS AT THE LAST TEST RUN\n  (none)");
    }

    @Test
    void aHintReachesTheNextChangesPromptForThatSubgoalOnly() {
        StuckLayer layer = flatRoot(layer());
        when(decider.decide(any(), any())).thenReturn(new DossierDecision(DossierDecision.Move.HINT,
                "the rejection repeats", "Dry the ink before the quill is lifted."));

        layer.consultDossier(cycle("c8"));

        assertThat(layer.promptBlock(cycle("c9"))).contains("A READING OF WHERE THE LOOP IS STUCK",
                "Dry the ink before the quill is lifted.");
        assertThat(layer.promptBlock(CycleContextData.builder().cycleName("c9").currentSubGoalId("sg-other").build()))
                .isNull();
    }

    @Test
    void aReplanIsRequestedOfThePlanner() {
        StuckLayer layer = flatRoot(layer());
        when(decider.decide(any(), any())).thenReturn(new DossierDecision(DossierDecision.Move.REPLAN, "the plan", "Split drying from writing."));

        CycleContextData out = layer.consultDossier(cycle("c8"));

        assertThat(out.hasStrongRequest(BasicSignals.REPLAN_REQUESTED, 0.5)).isTrue();
    }

    @Test
    void aDivideSplitsTheSubgoalOnItsUnmetCriteria() {
        StuckLayer layer = flatRoot(layer());
        when(decider.decide(any(), any())).thenReturn(new DossierDecision(DossierDecision.Move.DIVIDE, "too big", null));
        when(planner.divideExhaustedSubGoal(anyString(), anyList())).thenReturn(List.of("a", "b"));

        layer.consultDossier(cycle("c8"));

        verify(planner).divideExhaustedSubGoal("sg-ink",
                List.of("the quill dries in three seconds", "an empty well is refused"));
        verify(events).dossierDecision(eq("c8"), eq("FLAT_ROOT"), anyString(), eq("DIVIDE"), anyString(), any(),
                eq(true), eq("divided into 2 work item(s)"), anyList());
    }

    @Test
    void theNextDossierIsShownTheMoveAlreadyTried() {
        StuckLayer layer = flatRoot(layer());
        when(decider.decide(any(), any())).thenReturn(new DossierDecision(DossierDecision.Move.HINT, "x", "Dry it."));
        layer.consultDossier(cycle("c8"));
        StuckLedger.Entry e = layer.find(new StuckObservation(StuckObservation.Source.FLAT_ROOT,
                "the root has not moved for half its window", "sg-ink", "c7", List.of()));

        StuckDossier next = layer.dossierFor(e, "again", cycle("c20"));

        assertThat(next.earlierMoves()).singleElement().asString().startsWith("HINT at c8");
    }

    @Test
    void withNoPendingRequestNothingIsAsked() {
        StuckLayer layer = layer();
        CycleContextData ctx = cycle("c1");

        assertThat(layer.consultDossier(ctx)).isSameAs(ctx);
        verify(decider, never()).decide(any(), any());
        verify(events, never()).dossierDecision(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyList());
    }

    @Test
    void aReplyIsReadStrictly() {
        assertThat(DossierDecision.parse("MOVE: HINT\nBECAUSE: the test says so\nHINT: Dry the ink first.\nIt takes: three seconds."))
                .isEqualTo(new DossierDecision(DossierDecision.Move.HINT, "the test says so", "Dry the ink first.\nIt takes: three seconds."));
        assertThat(DossierDecision.parse("MOVE: divide\nBECAUSE: too big").move()).isEqualTo(DossierDecision.Move.DIVIDE);
        assertThat(DossierDecision.parse("MOVE: HALT\nBECAUSE: stop").move()).as("not on the menu").isEqualTo(DossierDecision.Move.NONE);
        assertThat(DossierDecision.parse("MOVE: HINT\nBECAUSE: x").move()).as("a hint without text").isEqualTo(DossierDecision.Move.NONE);
        assertThat(DossierDecision.parse("I would replan.").move()).isEqualTo(DossierDecision.Move.NONE);
        assertThat(DossierDecision.parse(null).move()).isEqualTo(DossierDecision.Move.NONE);
    }
}
