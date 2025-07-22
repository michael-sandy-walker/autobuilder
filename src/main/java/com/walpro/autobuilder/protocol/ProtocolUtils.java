package com.walpro.autobuilder.protocol;

import java.util.List;

public class ProtocolUtils {

    public static String formatRulesBlock(String title, List<ContractRule> rules) {
        StringBuilder sb = new StringBuilder(title).append("\n\n");
        for (ContractRule rule : rules) {
            sb.append("RULE: ").append(rule.id()).append("\n")
              .append("Description: ").append(rule.description()).append("\n")
              .append("Example: ").append(rule.example()).append("\n\n");
        }
        return sb.toString();
    }
}
