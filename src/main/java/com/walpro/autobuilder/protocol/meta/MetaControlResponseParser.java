package com.walpro.autobuilder.protocol.meta;

import java.util.Locale;

public class MetaControlResponseParser {

    public MetaControlDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new MetaControlDecision(MetaControlDecision.NextRole.PLANNER, false, "Invalid meta response (blank).", raw);
        }

        String nextRole = null;
        String stop = null;
        String message = null;

        for (String line : raw.lines().toList()) {
            String t = line.trim();
            String lc = t.toLowerCase(Locale.ROOT);

            if (lc.startsWith("nextrole:")) {
                nextRole = t.substring("nextrole:".length()).trim();
            } else if (lc.startsWith("stop:")) {
                stop = t.substring("stop:".length()).trim();
            } else if (lc.startsWith("message:")) {
                message = t.substring("message:".length()).trim();
            }
        }

        MetaControlDecision.NextRole role = parseRole(nextRole);
        boolean stopBool = parseBool(stop);
        String msg = (message == null || message.isBlank()) ? "No message provided." : message;

        return new MetaControlDecision(role, stopBool, msg, raw);
    }

    private MetaControlDecision.NextRole parseRole(String s) {
        if (s == null) return MetaControlDecision.NextRole.PLANNER;
        String t = s.trim().toUpperCase(Locale.ROOT);
        try {
            return MetaControlDecision.NextRole.valueOf(t);
        } catch (Exception e) {
            return MetaControlDecision.NextRole.PLANNER;
        }
    }

    private boolean parseBool(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("yes") || t.equals("1");
    }
}
