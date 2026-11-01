package com.walpro.autobuilder.core.merge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EC: whether the project's own code uses a type an input brought, the one kind of intent line structure can answer.
 * A reading could not: field122 closed on "not read with a second reader" over an import with its own parser, field119
 * imported the reader, never called it and named its own parser after it (the rule and the trees:
 * {@code ~/.autobuilder/replay-intent}). So an import is not a use (field126), and neither are comments or strings.
 *
 * <p>Referred to is what this answers, not reached, and not every call site an intent means.
 */
public final class IntentUse {

    /** Under an input's {@code meta/inputs/<id>}: the placed paths of the types its intent says to use. */
    public static final String USES_FILE = "/uses.txt";

    private static final String MAIN_SOURCE = "src/main/java/";
    private static final Pattern NOT_CODE = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\"|//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])+'");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;");

    private IntentUse() {
    }

    public static boolean isMainType(String path) {
        return path.contains(MAIN_SOURCE) && path.endsWith(".java");
    }

    public static String simpleName(String path) {
        return path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
    }

    /**
     * Those of {@code named} the project's own code does not use: directly, or through another of the input's types it
     * uses, as a project using {@code CatalogueEntry} uses the {@code Isbn} inside it. An input's own files prove nothing
     * by themselves: clitarget's {@code Main} calls its reader in every run.
     *
     * @param placed every path the input placed
     * @param tree   the project's files by path
     */
    public static List<String> unused(List<String> named, Set<String> placed, Map<String, String> tree) {
        Map<String, String> code = new LinkedHashMap<>();
        tree.forEach((path, text) -> {
            if (text != null && isMainType(path)) code.put(path, NOT_CODE.matcher(text).replaceAll("\"\""));
        });
        Map<String, String> brought = new LinkedHashMap<>();
        code.forEach((path, text) -> {
            if (placed.contains(path)) brought.put(path, qualified(path, text));
        });
        List<String> frontier = new ArrayList<>(code.keySet().stream().filter(p -> !placed.contains(p)).toList());
        Set<String> used = new HashSet<>();
        while (!frontier.isEmpty()) {
            String text = code.get(frontier.remove(frontier.size() - 1));
            brought.forEach((path, type) -> {
                if (!used.contains(path) && refersTo(text, type)) {
                    used.add(path);
                    frontier.add(path);
                }
            });
        }
        return named.stream().filter(p -> !used.contains(p)).toList();
    }

    private static String qualified(String path, String code) {
        Matcher p = PACKAGE.matcher(code);
        return p.find() ? p.group(1) + "." + simpleName(path) : simpleName(path);
    }

    private static boolean refersTo(String code, String type) {
        String body = IMPORT.matcher(PACKAGE.matcher(code).replaceAll("")).replaceAll("");
        if (Pattern.compile("(?<![\\w.])" + Pattern.quote(type) + "\\b").matcher(body).find()) return true;
        int dot = type.lastIndexOf('.');
        String simple = type.substring(dot + 1);
        String pkg = dot < 0 ? "" : type.substring(0, dot);
        boolean named = Pattern.compile("(?<![\\w.])" + simple + "\\b").matcher(body).find();
        Matcher own = PACKAGE.matcher(code);
        if (named && (own.find() ? own.group(1) : "").equals(pkg)) return true;
        Matcher i = IMPORT.matcher(code);
        while (i.find()) {
            String imported = i.group(2);
            if (i.group(1) != null) {
                if (imported.startsWith(type + ".")) return true;   // what a static import brings has no type name at its uses
            } else if (named && (imported.equals(type) || imported.equals(pkg + ".*"))) {
                return true;
            }
        }
        return false;
    }
}
