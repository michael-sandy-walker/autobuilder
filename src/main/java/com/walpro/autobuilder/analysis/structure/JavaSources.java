package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;

/**
 * T6-T: the one parser the framework reads a target's Java sources with, set to a language level that reads what
 * targets write.
 *
 * <p>Eleven places each built a parser of their own, at the library's default level, which reads
 * neither a record nor a text block: a source using either came back as a failed parse, and each caller took that
 * for "nothing declared here". The same fact cost the loop's own output a test in every ladder-grind run until
 * 2026-10-13 (a class written by the model parsed a record at the default level and got an empty answer); the
 * framework's readers of structure, wiring, grounding and merge regressions had it too, unobserved. One level, in
 * one place, the same as {@code JavaParserConfig} sets for the static parser.
 */
public final class JavaSources {

    private JavaSources() {
    }

    /** A parser of its own for the caller: a {@code JavaParser} keeps state while it parses. */
    public static JavaParser parser() {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
    }
}
