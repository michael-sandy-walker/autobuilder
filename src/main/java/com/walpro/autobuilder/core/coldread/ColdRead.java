package com.walpro.autobuilder.core.coldread;

import java.util.List;

/**
 * What a project SAYS ABOUT ITSELF, read from a directory the loop has never seen.
 *
 * <p><b>Why this exists, and what it deliberately is not.</b> Until now the loop has only ever
 * worked on trees it created itself, from a specification a person wrote. Pointed at a project it
 * did not write, it has no starting point at all. This is that starting point: a factual account
 * assembled from the project's own evidence.
 *
 * <p><b>Every field is something observed, never something concluded.</b> That restraint is the
 * whole design. The tempting next step is to infer what the project OUGHT to do and work toward
 * that, and the import ticket has carried a warning about it since July: a loop that writes its
 * own specification and then grades itself against it will always score well, because both sides
 * come from the same guess. So this record holds what is on disk, what the code declares, and what
 * someone else's tests assert. What the project should do differently is not derivable from any of
 * it, and is not attempted here.
 *
 * <p>{@link #uncertainties()} is part of the account rather than an error channel. A cold read that
 * cannot parse half a tree must say so, because the reader's next question is always how much of
 * this to trust.
 *
 * @param root           the directory read
 * @param build          how the project is built, from its build file
 * @param runtime        how it is served, from its own configuration
 * @param prose          what the project says about itself in words, quoted verbatim, never summarised
 * @param surface        the request surface its code declares, as "VERB /path"
 * @param surfaceComplete false when a mapping path could not be resolved to a literal, so the
 *                        surface list is a lower bound rather than the whole of it
 * @param types          the types found, as "package.Name [annotations]"
 * @param tests          the tests someone else already wrote, as "Class.method"
 * @param skipped        files that are present but were not read, with the reason
 * @param uncertainties  what this reading could not establish, stated rather than omitted
 */
public record ColdRead(
        String root,
        Build build,
        Runtime runtime,
        List<String> prose,
        List<String> surface,
        boolean surfaceComplete,
        List<String> types,
        List<String> tests,
        List<String> skipped,
        List<String> uncertainties) {

    /**
     * @param tool         the build tool, named by the build file that was found
     * @param artifact     the artifact coordinates as the build file gives them
     * @param javaVersion  the language level the build file asks for, which may not be the one here
     * @param framework    the application framework and version, when a parent or dependency names one
     * @param dependencies declared dependencies, as "groupId:artifactId"
     */
    public record Build(String tool, String artifact, String javaVersion, String framework,
                        List<String> dependencies) {}

    /**
     * @param contextPath the path every route sits under, which is not visible in the code itself
     * @param port        the configured port, or null when the framework's default applies
     * @param settings    the configuration lines found, quoted
     */
    public record Runtime(String contextPath, String port, List<String> settings) {}

    /** True when there is enough here to act on: something builds it and something is served or tested. */
    public boolean actionable() {
        return build != null && build.tool() != null
                && (!surface.isEmpty() || !tests.isEmpty());
    }
}
