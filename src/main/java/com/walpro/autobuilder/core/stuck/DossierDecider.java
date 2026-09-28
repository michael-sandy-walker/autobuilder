package com.walpro.autobuilder.core.stuck;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCallContext;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T4-DY dossier: hands the gathered facts to the model and reads back one move. Never throws: a decider
 * that cannot answer decides NONE, and the operator's diagnosis request stands as it did before.
 */
@Slf4j
@Component
public class DossierDecider {

    static final String SYSTEM = "You look after an automated loop that changes a software project step by step."
            + " The loop is stuck. You get the facts it holds about that, and you choose its next move from a fixed"
            + " menu. Choose from the facts given; do not assume facts that are not there.";

    static final String MENU = """
            Choose exactly one move:
              HINT   -- tell the next change what the facts show it keeps missing (give the hint)
              REPLAN -- the plan for this subgoal is the problem; ask the planner to change it (say why)
              DIVIDE -- the subgoal is too big; split it into one work item per unmet criterion
              NONE   -- nothing on this menu would help; the operator has to look
            Reply in exactly this form:
            MOVE: <HINT|REPLAN|DIVIDE|NONE>
            BECAUSE: <which fact above your choice rests on, and why>
            HINT: <for HINT or REPLAN: one short paragraph the next step should act on; otherwise leave it out>""";

    @Autowired(required = false)
    private LLMClient llmClient;

    @Value("${autobuilder.stuck.dossier.enabled:true}")
    private boolean enabled = true;

    public boolean available() {
        return enabled && llmClient != null;
    }

    public DossierDecision decide(StuckDossier dossier, String cycleId) {
        if (!available()) return DossierDecision.none("the dossier decider is off");
        String user = "Here is what the loop knows.\n\n" + dossier.render() + "\n\n" + MENU;
        try {
            ResponseEntity<ChatResponse> response;
            try (var scope = LlmCallContext.scope("stuck-dossier", cycleId)) {
                response = llmClient.ask(List.of(new Message("system", SYSTEM), new Message("user", user)));
            }
            String reply = response == null || response.getBody() == null ? null : response.getBody().getFirstContent();
            DossierDecision d = DossierDecision.parse(reply);
            log.warn("T4-DY: dossier for '{}' -> {} ({})", dossier.signature(), d.move(), d.because());
            return d;
        } catch (Exception e) {
            log.warn("T4-DY: the dossier decider could not answer for '{}': {}", dossier.signature(), e.getMessage());
            return DossierDecision.none("the model did not answer: " + e.getMessage());
        }
    }
}
