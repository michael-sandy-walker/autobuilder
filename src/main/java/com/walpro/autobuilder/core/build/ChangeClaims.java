package com.walpro.autobuilder.core.build;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T6-T: claims about one named file that the tree itself can CONTRADICT -- that it was changed. Only ever a
 * contradiction (FALSE) or no answer (null): a change does not prove "to support the new behaviour", so a met claim
 * still goes to the reading.
 *
 * <p><b>Not a claim about a file's TEXT.</b> A first version also contradicted "X includes '&lt;text&gt;'" when X's
 * source lacked the text; on field42 run 1 it refused a correct tree (28/28): the claims quoted a template
 * ("ran with: &lt;row&gt;"), and text a class outputs may live in a constant of another class (the reference
 * implementation's ParameterizedCaseInput.LABEL). A file's text is no authority on what it outputs.
 *
 * <p>Ladder-rows run 2026-10-06_18-38-56: the completion verifier, shown TestAssertionVocabulary whole, confirmed
 * "TestAssertionVocabulary is modified to support the new behavior" and "... includes the 'ran with:' format" --
 * the file was byte for byte as adopted and never held that text. A reading invented both facts; the changes since
 * adoption and the file's own text answer them for nothing ([[project-invented-facts-have-authorities]]).
 */
public final class ChangeClaims {

    private ChangeClaims() {
    }

    /** "X is modified / updated / changed / extended ..." about one class. */
    private static final Pattern MODIFIED = Pattern.compile(
            "^`?([A-Z][A-Za-z0-9_]*)`?(?:\\.java)? (?:is|are|was|were|has been|have been|gets|get) "
                    + "(?:modified|updated|changed|extended|adapted)\\b.*$");

    /**
     * FALSE when the tree contradicts {@code criterion}; null when it is not such a claim, names no file of the tree,
     * or the tree cannot settle it.
     *
     * @param changes the changes since adoption, or null when the project was not adopted
     * @param files   the tree, by relative path (to know the claim names a file of it)
     */
    public static Boolean answer(String criterion, AdoptionManifest.Changes changes, Map<String, String> files) {
        if (criterion == null || files == null) return null;
        String claim = criterion.strip().replaceAll("[.\\s]+$", "");
        Matcher modified = MODIFIED.matcher(claim);
        if (modified.matches() && changes != null) {
            String name = modified.group(1);
            if (pathOf(name, files) == null) return null;
            boolean changed = changes.changedOrAdded().stream().anyMatch(p -> simpleName(p).equals(name));
            return changed ? null : Boolean.FALSE;
        }
        return null;
    }

    private static String pathOf(String simple, Map<String, String> files) {
        for (String p : files.keySet()) {
            String n = p.replace('\\', '/');
            if (n.endsWith("/" + simple + ".java") || n.equals(simple + ".java")) return p;
        }
        return null;
    }

    private static String simpleName(String path) {
        String p = path.replace('\\', '/');
        String file = p.substring(p.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file;
    }
}
