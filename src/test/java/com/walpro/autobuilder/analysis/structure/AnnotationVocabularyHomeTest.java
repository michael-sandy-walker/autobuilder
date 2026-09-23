package com.walpro.autobuilder.analysis.structure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-BN: {@link AnnotationVocabularies} is the one home for what an annotation name MEANS, and the
 * convention had been restated twice and drifted twice, so it is held here rather than in prose.
 *
 * <p>Any vocabulary name written as a string literal outside its home fails the build. The names
 * are read from the class itself, so a row added there is guarded from the moment it exists.
 */
class AnnotationVocabularyHomeTest {

    private static final Path MAIN = Path.of("src/main/java");

    /**
     * Files that carry these names for a DIFFERENT question than what an annotation means, each
     * with the question it answers. They are lookup tables, not role decisions, and whether they
     * should exist at all is T4-BM's discovery work.
     */
    private static final Map<String, String> OTHER_QUESTIONS = Map.of(
            "WellKnownPackageRegistry.java", "which package declares a simple name (symbol lookup)",
            "SymbolGroundingService.java", "which simple names need no declaration in the project",
            "StoreCoherenceHeuristic.java", "class-name SUFFIXES that are not entity types",
            "DiagramGenerator.java", "class-name SUFFIXES used to group a diagram");

    @Test
    void noVocabularyNameIsWrittenOutsideItsHome() throws IOException {
        Set<String> names = vocabularyNames();
        Pattern literal = Pattern.compile("\"@?([A-Z]\\w+)\"");
        List<String> strays = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String file = f.getFileName().toString();
                if (file.equals("AnnotationVocabularies.java") || OTHER_QUESTIONS.containsKey(file)) continue;
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).strip();
                    if (t.startsWith("//") || t.startsWith("*")) continue;
                    Matcher m = literal.matcher(lines.get(i));
                    while (m.find()) {
                        if (names.contains(m.group(1))) strays.add(MAIN.relativize(f) + ":" + (i + 1) + " " + m.group());
                    }
                }
            }
        }
        assertThat(strays)
                .as("an annotation name's meaning lives in AnnotationVocabularies; reference its row instead")
                .isEmpty();
    }

    @Test
    void theGuardKnowsTheNamesItGuards() {
        // A guard reading an empty vocabulary passes everything.
        assertThat(vocabularyNames()).contains("RestController", "ExceptionHandler", "Entity", "GetMapping");
    }

    /** Every annotation name held in a public row: sets, the keys of a map, single names. */
    private static Set<String> vocabularyNames() {
        Set<String> names = new TreeSet<>();
        for (Field f : AnnotationVocabularies.class.getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers()) || !Modifier.isStatic(f.getModifiers())) continue;
            try {
                Object v = f.get(null);
                if (v instanceof String s) names.add(s);
                else if (v instanceof Map<?, ?> m) m.keySet().forEach(k -> names.add(String.valueOf(k)));
                else if (v instanceof Collection<?> c) c.forEach(x -> names.add(String.valueOf(x)));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return names;
    }
}
