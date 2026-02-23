package com.walpro.autobuilder.protocol.scope;

import com.walpro.autobuilder.analysis.scope.ScopeAssessment;
import com.walpro.autobuilder.analysis.scope.ScopeFit;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SubgoalScopeResponseParser {

    private static final Pattern FIT = Pattern.compile("(?im)^\\s*Fit\\s*:\\s*(IN_SCOPE|PARTIAL|OUT_OF_SCOPE)\\s*$");
    private static final Pattern CONF = Pattern.compile("(?im)^\\s*Confidence\\s*:\\s*([0-9]+(?:[\\.,][0-9]+)?)\\s*$");
    private static final Pattern MSG = Pattern.compile("(?im)^\\s*Message\\s*:\\s*(.+)\\s*$");
    private static final Pattern BEST = Pattern.compile("(?im)^\\s*BestMatchingSubGoalId\\s*:\\s*(.+)\\s*$");

    public ScopeAssessment parse(String content) {
        if (content == null) return null;

        String fitToken = matchGroup(FIT, content);
        String confToken = matchGroup(CONF, content);
        String msg = matchGroup(MSG, content);
        String best = matchGroup(BEST, content);

        if (fitToken == null || confToken == null || msg == null || best == null) return null;

        ScopeFit fit = ScopeFit.valueOf(fitToken.trim().toUpperCase(Locale.ROOT));

        double conf;
        try {
            conf = Double.parseDouble(confToken.trim().replace(',', '.'));
        } catch (Exception e) {
            conf = 0.0;
        }

        String bestId = best.trim();
        if (bestId.equalsIgnoreCase("(none)") || bestId.isBlank()) {
            bestId = null;
        }

        return new ScopeAssessment(
                fit,
                clamp01(conf),
                msg.trim(),
                bestId
        );
    }

    private static String matchGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
