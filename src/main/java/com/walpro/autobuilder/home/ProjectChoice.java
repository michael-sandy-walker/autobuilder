package com.walpro.autobuilder.home;

import java.util.Map;
import java.util.Properties;

/**
 * T4-ED: what a run is started on -- the project, and optionally how it begins and which profiles it runs under.
 *
 * <p>The home screen's "Start" fills it from the page; a startup parameter fills it from the command line
 * (the fast-forward). Both go to {@link ProjectLauncher#start}, so a scripted run and an operator's run
 * take the same path.
 *
 * @param project  the project directory, as given (a relative path stays relative to the working directory,
 *                 which is how scripted runs name their target and how their logs are found)
 * @param mode     a start mode name, or null to leave it to the startup arguments and the default
 * @param profiles comma-separated Spring profiles, or null to leave them to the startup arguments
 */
public record ProjectChoice(String project, String mode, String profiles) {

    static final String BASE_PATH = "file.base-path";
    static final String BASE_PATH_ENV = "FILE_BASE_PATH";

    public ProjectChoice {
        if (project == null || project.isBlank()) throw new IllegalArgumentException("a project is required");
    }

    /**
     * The project named at startup, or null when none is -- then the app opens the home screen.
     * The command line wins over a system property, which wins over the environment: Spring's own order.
     */
    static ProjectChoice fromStartup(String[] args, Properties system, Map<String, String> env) {
        String project = argValue(args, BASE_PATH);
        if (blank(project)) project = system.getProperty(BASE_PATH);
        if (blank(project)) project = system.getProperty(BASE_PATH_ENV);
        if (blank(project)) project = env.get(BASE_PATH_ENV);
        return blank(project) ? null : new ProjectChoice(project, null, null);
    }

    /** The value of {@code --name=value} in the arguments, or null. */
    static String argValue(String[] args, String name) {
        String prefix = "--" + name + "=";
        String value = null;
        for (String a : args) {
            if (a.startsWith(prefix)) value = a.substring(prefix.length());
        }
        return value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
