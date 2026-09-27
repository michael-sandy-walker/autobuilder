package com.walpro.autobuilder.core.build;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T4-DY slice A: a stuck loop's failure signature read as a QUESTION about the code, answered from
 * the code itself.
 *
 * <p>A signature is the text the grind detector records for a failure that keeps recurring, e.g.
 * {@code "the build fails: Foo has no getBar()"}. When the signature names something the project's
 * own sources can answer for, {@link #answer} returns what those sources declare; otherwise empty.
 * Empty is "the signature alone does not say what to look up", never "nothing is wrong".
 */
public final class StuckQuestions {

    /** What was asked of the sources. */
    public enum Kind {
        /** A type was called with a member it may not declare: what does it declare? */
        MEMBERS,
        /** A record was constructed with arguments its constructor may not take: what does it take? */
        CONSTRUCTOR,
        /** A method was called with arguments it may not take: which overloads does its class declare? */
        OVERLOADS
    }

    /**
     * The answer, read from the type's own source.
     *
     * @param kind     the question
     * @param type     the simple name of the type the question is about
     * @param member   the member the signature names (for MEMBERS and OVERLOADS; null for CONSTRUCTOR)
     * @param declared what the type declares: for MEMBERS, {@link DeclaredMembers#of}'s list; for
     *                 CONSTRUCTOR, each constructor as {@code Type(ParamType name, ...)} starting with
     *                 a record's canonical one; for OVERLOADS, each method of that name as
     *                 {@code name(ParamType name, ...)}
     * @param meant    for MEMBERS, the declared member the missing one names ({@link DeclaredMembers#meantBy}),
     *                 or null when none; null for the other kinds
     */
    public record Answer(Kind kind, String type, String member, List<String> declared, String meant) {
    }

    private StuckQuestions() {
    }

    /**
     * The answer the project's sources give to the question {@code signature} poses, or empty.
     *
     * @param signature the recorded failure signature
     * @param sources   the project's source files, path relative to the project root -> content
     */
    public static Optional<Answer> answer(String signature, Map<String, String> sources) {
        throw new UnsupportedOperationException("not implemented");
    }
}
