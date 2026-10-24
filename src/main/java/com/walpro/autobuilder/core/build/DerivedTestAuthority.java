package com.walpro.autobuilder.core.build;

/**
 * T6-U: the one party that may change a test written from the requirements' wording ({@link DerivedTestsFile}).
 * Writers never do: they PROPOSE, and the proposal is judged against the requirement's wording alone, never
 * against the code -- otherwise a frozen test could be bent to fit wrong code, which is what freezing it is for.
 */
public interface DerivedTestAuthority {

    /**
     * A writer's proposed text for a frozen test. True when it is (now) the accepted version -- judged against
     * its requirement's wording, or identical to what was accepted -- and the write may go ahead.
     */
    boolean contest(String writer, String pathUnderOutput, String content);

    /** Writes every accepted test into the tree as it was accepted; called before the suite runs. */
    void restore();

    /** What became of the proposals since the last call, for the model; null or blank when nothing. */
    String drainNotes();
}
