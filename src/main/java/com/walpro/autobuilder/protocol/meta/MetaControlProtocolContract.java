package com.walpro.autobuilder.protocol.meta;

import java.util.List;

import com.walpro.autobuilder.protocol.core.ContractRule;

public class MetaControlProtocolContract {

    public static final String VERSION = "0.1-meta-control";

    public static List<ContractRule> rules() {
        return List.of(
                new ContractRule(
                        "STRUCTURED_ONLY",
                        "Respond using ONLY the required headers. No prose outside the headers.",
                        """
                        NextRole: EVOLUTION
                        Stop: false
                        Message: Continue evolving for current subgoal.
                        """
                ),
                new ContractRule(
                        "NEXT_ROLE_REQUIRED",
                        "Output exactly one NextRole from: PLANNER, EVOLUTION, EVALUATION, MERGE.",
                        "NextRole: EVALUATION"
                ),
                new ContractRule(
                        "STOP_REQUIRED",
                        "Output Stop: true or Stop: false.",
                        "Stop: true"
                ),
                new ContractRule(
                        "MESSAGE_REQUIRED",
                        "Output a one-line Message: explaining the decision.",
                        "Message: Stop because the root goal is fulfilled."
                ),
                new ContractRule(
                        "NO_CODE",
                        "Do not output Java code blocks or file content.",
                        "WRONG: ```java ...```"
                )
        );
    }

    public static String asPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("META CONTROL PROTOCOL CONTRACT v").append(VERSION).append("\n\n");
        for (ContractRule rule : rules()) {
            sb.append("RULE: ").append(rule.id()).append("\n")
                    .append("Description: ").append(rule.description()).append("\n")
                    .append("Example: ").append(rule.example()).append("\n\n");
        }
        return sb.toString();
    }

    public static String getSystemPrompt() {
        return "You are the Autobuilder meta-controller. Choose the next role (planner/evolution/evaluation/merge) and whether the system should stop.\n\n"
                + asPromptBlock();
    }

    public static boolean isValidResponse(String response) {
        if (response == null || response.isBlank()) return false;
        String r = response.toLowerCase();
        return r.contains("nextrole:") && r.contains("stop:") && r.contains("message:");
    }
}
