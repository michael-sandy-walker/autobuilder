package com.walpro.autobuilder.core.coldread.entry;

/**
 * T4-EK: how a project is exercised from outside -- the three ways in, named for a person reading them (the names
 * are the user's choice; their definitions go wherever a person sees them).
 */
public enum EntryKind {

    /** Exercised inside the same process, by a test that calls it: libraries, frameworks, any method of any project. */
    LIBRARY_STYLE("library-style",
            "exercised inside the same process, by a test that calls it"),

    /** Started and kept running, exercised by requests it answers: web applications, APIs, servers. */
    SERVICE_STYLE("service-style",
            "started and kept running, exercised by requests it answers"),

    /** Started with arguments and input, runs to its end, exercised by what it leaves: exit status, output, files. */
    COMMAND_STYLE("command-style",
            "started with arguments and input, runs to its end, exercised by its exit status, output and files");

    private final String label;
    private final String definition;

    EntryKind(String label, String definition) {
        this.label = label;
        this.definition = definition;
    }

    public String label() {
        return label;
    }

    public String definition() {
        return definition;
    }
}
