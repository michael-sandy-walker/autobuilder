package com.walpro.autobuilder.core.services;

import java.util.List;
import java.util.Map;

/**
 * T4-EN: one service a project needs, as a container to start for it -- what the operator sees, edits and says yes to.
 *
 * @param name          what it is called (the compose file's service name, else the address's scheme word)
 * @param image         the container image, with its tag
 * @param hostPort      the port the project reaches it at -- the one its configuration names, never moved
 * @param containerPort the port the image listens on inside the container
 * @param env           the container's environment (user, password, database name ...)
 * @param initDir       the directory inside the container whose scripts run on its first start; null when it has none
 * @param initScripts   the scripts the project ships that could fill it (paths relative to the project), offered
 * @param initScript    the one the operator ticked, or null (decided: unticked by default)
 * @param source        {@code compose} (the project's own compose file, taken as written) or {@code model} (written from
 *                      the evidence lines only)
 * @param evidence      the lines it rests on, each with where it was read
 * @param failure       what the project said when it did not start, in its own words
 * @param revisedBecause when a container started from the operator's yes did not come up and its environment was
 *                      changed from what it said: what changed, and its words; null otherwise
 */
public record ServiceProposal(String name, String image, int hostPort, int containerPort, Map<String, String> env,
                              String initDir, List<String> initScripts, String initScript, String source,
                              List<String> evidence, String failure, String revisedBecause) {

    public ServiceProposal(String name, String image, int hostPort, int containerPort, Map<String, String> env,
                           String initDir, List<String> initScripts, String initScript, String source,
                           List<String> evidence, String failure) {
        this(name, image, hostPort, containerPort, env, initDir, initScripts, initScript, source, evidence, failure, null);
    }

    public ServiceProposal {
        env = env == null ? Map.of() : Map.copyOf(new java.util.TreeMap<>(env));
        initScripts = initScripts == null ? List.of() : List.copyOf(initScripts);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * Whether {@code other} is what the operator said yes to with only its environment changed -- the same image, ports
     * and setup script (the script directory is the image's, not the operator's choice). Such a change may be tried under
     * the same yes; any other change is asked again.
     */
    public boolean sameButEnvironment(ServiceProposal other) {
        return other != null && java.util.Objects.equals(image, other.image) && hostPort == other.hostPort
                && containerPort == other.containerPort && java.util.Objects.equals(initScript, other.initScript)
                && !env.equals(other.env);
    }

    /** What changed in the environment from {@code before} to this one, as a person reads it. */
    public String environmentChangeFrom(ServiceProposal before) {
        List<String> parts = new java.util.ArrayList<>();
        before.env.forEach((k, v) -> {
            if (!env.containsKey(k)) parts.add(k + " removed");
            else if (!env.get(k).equals(v)) parts.add(k + "=" + env.get(k) + " (was " + v + ")");
        });
        env.forEach((k, v) -> { if (!before.env.containsKey(k)) parts.add(k + "=" + v + " added"); });
        return String.join(", ", parts);
    }

    /** Whether the operator's edit leaves something that can be started: an image name and ports in range. */
    public String invalid() {
        if (image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9./_:@-]*")) return "the image name is not one Docker accepts";
        if (hostPort < 1 || hostPort > 65535) return "the port it is reached at is out of range";
        if (containerPort < 1 || containerPort > 65535) return "the port inside the container is out of range";
        for (String k : env.keySet()) if (!k.matches("[A-Za-z_][A-Za-z0-9_]*")) return "'" + k + "' is not an environment name";
        if (initScript != null && !initScripts.contains(initScript)) return "the setup script is not one the project ships";
        if (initScript != null && initDir == null) return "this image has no directory for setup scripts";
        return null;
    }
}
