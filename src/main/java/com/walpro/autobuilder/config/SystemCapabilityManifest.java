package com.walpro.autobuilder.config;

import com.walpro.autobuilder.core.build.BootGate;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.build.PackageGate;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.core.investigate.LogConsultation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares what happens to an agent's work, injected into the planner and analysis system prompts
 * so their goals are ones the system can carry out and check.
 * <p>
 * T1-GH: the T3-K version was a hand-written list that said the system could not compile, test or
 * start anything -- true in March, the opposite of the loop by the time the compile probe and the
 * completion-edge gates existed, and nothing noticed. Each line about a check is now read from that
 * check's own switch, so the text changes when the configuration does.
 */
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "rebuilt from configuration at startup")
public class SystemCapabilityManifest {

    private final BuildExecutor buildExecutor;
    private final PackageGate packageGate;
    private final TestRunGate testRunGate;
    private final BootGate bootGate;
    private final LogConsultation logConsultation;

    public SystemCapabilityManifest(BuildExecutor buildExecutor, PackageGate packageGate,
                                    TestRunGate testRunGate, BootGate bootGate, LogConsultation logConsultation) {
        this.buildExecutor = buildExecutor;
        this.packageGate = packageGate;
        this.testRunGate = testRunGate;
        this.bootGate = bootGate;
        this.logConsultation = logConsultation;
    }

    /** The checks the system runs on written files, in the order they happen; empty when nothing is built. */
    List<String> checks() {
        List<String> checks = new ArrayList<>();
        if (!buildExecutor.isEnabled()) return checks;
        checks.add("compiles every proposed change before accepting it; a change that does not compile is rejected"
                + " and the compiler's errors are shown to the next attempt");
        checks.add("when a change uses a library the build does not declare, looks the library up and declares it"
                + " in the build file");
        if (packageGate.isLive()) checks.add("before the goal is declared complete, packages the project");
        if (testRunGate.isLive()) checks.add("before the goal is declared complete, runs the project's own tests");
        if (bootGate.isLive()) checks.add("before the goal is declared complete, starts the packaged program once"
                + " and observes it; a program that serves requests is sent the ones the specification names");
        return checks;
    }

    /**
     * Returns a formatted block ready for injection into an LLM system prompt.
     */
    public String render() {
        List<String> checks = checks();
        StringBuilder sb = new StringBuilder();
        sb.append("--- SYSTEM CAPABILITY MANIFEST ---\n");
        sb.append("Agents act only by writing files. No agent runs a command, a build or a test itself.\n\n");

        sb.append("WHAT THE SYSTEM DOES WITH THE FILES WRITTEN:\n");
        if (checks.isEmpty()) {
            sb.append("  - nothing: in this run no change is compiled, tested or started; the code is judged by reading it\n");
        } else {
            for (String c : checks) {
                sb.append("  + ").append(c).append("\n");
            }
            if (packageGate.isLive() || testRunGate.isLive() || bootGate.isLive()) {
                sb.append("  + a completion check that fails becomes a new work item\n");
            }
        }
        sb.append("  + records past decisions and their outcomes; agents are shown excerpts of them\n");
        if (logConsultation != null && logConsultation.isLive()) {
            sb.append("  + an agent retrying rejected work may first ask one question of this run's log;"
                    + " a model searches the log and answers\n");
        }

        sb.append("\nNOTHING IN THE SYSTEM:\n");
        sb.append("  - runs a command an agent asks for\n");
        sb.append("  - installs software outside the project's own build\n");

        sb.append("\nPlan goals as changes to files. ");
        if (checks.isEmpty()) {
            sb.append("State criteria that can be judged by reading the code.\n");
        } else {
            sb.append("A criterion such as ").append(checkedCriterionExample()).append(" is checked by the system;\n");
            sb.append("a goal whose only step is to run, start or inspect something has no file to change.\n");
        }
        sb.append("--- END SYSTEM CAPABILITY MANIFEST ---");

        return sb.toString();
    }

    /** Only the checks that run may serve as the example, or the example would promise one that does not. */
    private String checkedCriterionExample() {
        List<String> examples = new ArrayList<>();
        if (testRunGate.isLive()) examples.add("\"the tests pass\"");
        if (bootGate.isLive()) examples.add("\"the program starts\"");
        if (examples.isEmpty()) examples.add("\"the project compiles\"");
        return String.join(" or ", examples);
    }
}
