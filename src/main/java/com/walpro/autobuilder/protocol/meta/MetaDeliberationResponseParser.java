package com.walpro.autobuilder.protocol.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.walpro.autobuilder.protocol.meta.MetaActionStep.Need;

public class MetaDeliberationResponseParser {

    public MetaDeliberationPlan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new MetaDeliberationPlan(
                    false,
                    MetaDeliberationPlan.Mode.NOOP,
                    "Blank response.",
                    List.of(),
                    raw
            );
        }

        String stop = null, mode = null, rationale = null;

        List<MetaActionStep> steps = new ArrayList<>();
        boolean inSteps = false;

        Integer stepIndex = null;
        Need need = null;
        String target = null;
        String req = null;
        String stopIf = null;

        for (String line : raw.lines().toList()) {
            String t = line.trim();
            String lc = t.toLowerCase(Locale.ROOT);

            if (lc.startsWith("stop:")) stop = t.substring("stop:".length()).trim();
            else if (lc.startsWith("mode:")) mode = t.substring("mode:".length()).trim();
            else if (lc.startsWith("rationale:")) rationale = t.substring("rationale:".length()).trim();
            else if (lc.startsWith("steps:")) {
                inSteps = true;
            }
            else if (inSteps && lc.startsWith("- step:")) {
                flushStepIfPresent(steps, stepIndex, need, target, req, stopIf);

                stepIndex = parseIntSafe(t.substring(t.indexOf(':') + 1).trim());
                need = null; target = null; req = null; stopIf = null;
            }
            else if (inSteps && lc.startsWith("need:")) {
                need = parseNeed(t.substring("need:".length()).trim());
            }
            else if (inSteps && lc.startsWith("targetrole:")) {
                target = t.substring("targetrole:".length()).trim();
            }
            else if (inSteps && lc.startsWith("request:")) {
                req = t.substring("request:".length()).trim();
            }
            else if (inSteps && lc.startsWith("stopif:")) {
                stopIf = t.substring("stopif:".length()).trim();
            }
        }

        flushStepIfPresent(steps, stepIndex, need, target, req, stopIf);

        boolean stopBool = parseBool(stop);
        MetaDeliberationPlan.Mode m = parseMode(mode);
        String rat = (rationale == null || rationale.isBlank()) ? "No rationale." : rationale;

        if (stopBool) {
            steps = List.of();
        }

        return new MetaDeliberationPlan(stopBool, m, rat, steps, raw);
    }

    private void flushStepIfPresent(List<MetaActionStep> out,
                                    Integer idx,
                                    Need need,
                                    String target,
                                    String req,
                                    String stopIf) {
        if (idx == null) return;

        Need n = (need == null) ? Need.NEXT_STEP : need;
        String tr = (target == null) ? "" : target.trim();
        String rq = (req == null) ? "" : req.trim();
        String si = (stopIf == null || stopIf.isBlank()) ? "Never" : stopIf.trim();

        if (n == Need.NONE) {
            tr = "";
            rq = "";
        }

        out.add(new MetaActionStep(idx, n, tr, rq, si));
    }

    private boolean parseBool(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("yes") || t.equals("1");
    }

    private MetaDeliberationPlan.Mode parseMode(String s) {
        if (s == null) return MetaDeliberationPlan.Mode.NOOP;
        String t = s.trim().toUpperCase(Locale.ROOT);
        try { return MetaDeliberationPlan.Mode.valueOf(t); }
        catch (Exception e) { return MetaDeliberationPlan.Mode.NOOP; }
    }

    private Need parseNeed(String s) {
        if (s == null) return Need.NEXT_STEP;
        String t = s.trim().toUpperCase(Locale.ROOT);
        try { return Need.valueOf(t); }
        catch (Exception e) { return Need.NEXT_STEP; }
    }

    private Integer parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return null; }
    }
}
