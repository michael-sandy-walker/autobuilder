package com.walpro.autobuilder.protocol.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.protocol.meta.MetaActionStep.Need;

public class MetaDeliberationResponseParser {

    public MetaDeliberationPlan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new MetaDeliberationPlan(
                    false,
                    DeliberationMode.NOOP,
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
        String stopCycleIf = null;

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
                flushStepIfPresent(steps, stepIndex, need, target, req, stopIf, stopCycleIf);

                stepIndex = parseIntSafe(t.substring(t.indexOf(':') + 1).trim());
                need = null; target = null; req = null; stopIf = null; stopCycleIf = null;
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
            else if (inSteps && lc.startsWith("stop_if:")) {
                stopIf = t.substring("stop_if:".length()).trim();
            }
            else if (inSteps && lc.startsWith("stop_cycle_if:")) {
                stopCycleIf = t.substring("stop_cycle_if:".length()).trim();
            }
        }

        flushStepIfPresent(steps, stepIndex, need, target, req, stopIf, stopCycleIf);

        boolean stopBool = parseBool(stop);
        DeliberationMode m = parseMode(mode);
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
                                    String stopIf,
                                    String stopCycleIf) {
        if (idx == null) return;

        Need n = (need == null) ? Need.NEXT_STEP : need;
        String tr = (target == null) ? "" : target.trim();
        String rq = (req == null) ? "" : req.trim();
        String si = (stopIf == null || stopIf.isBlank()) ? "Never" : stopIf.trim();
        String sci = (stopCycleIf == null || stopCycleIf.isBlank()) ? "Never" : stopCycleIf.trim();

        if (n == Need.NONE) {
            tr = "";
            rq = "";
        }

        out.add(new MetaActionStep(idx, n, tr, rq, si, sci));
    }

    private boolean parseBool(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("yes") || t.equals("1");
    }

    private DeliberationMode parseMode(String s) {
        if (s == null) return DeliberationMode.NOOP;
        String t = s.trim().toUpperCase(Locale.ROOT);
        try { return DeliberationMode.valueOf(t); }
        catch (Exception e) { return DeliberationMode.NOOP; }
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
