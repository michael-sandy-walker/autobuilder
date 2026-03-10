package com.walpro.autobuilder.config;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Declares what the Autobuilder system CAN and CANNOT do at runtime.
 * <p>
 * Injected into agent protocol prompts so LLM agents stop proposing goals
 * that the system cannot satisfy (e.g. "run the tests", "compile the project").
 * <p>
 * Extensible hook for T3-F: when a capability registry and external handshake
 * arrive, dynamic entries are added here without changing the prompt-rendering
 * or agent-wiring code.
 */
@Component
public class SystemCapabilityManifest {

    private final List<String> can = List.of(
            "Read any file from the target project filesystem",
            "Write and modify files in the target project filesystem",
            "Propose code changes as structured file mutations (diffs / patches)",
            "Call LLM APIs to reason about code and generate new implementations",
            "Query the orchestration history graph (Neo4j) for past decisions and outcomes",
            "Analyse project structure, diffs, and open problems using static analysis"
    );

    private final List<String> cannot = List.of(
            "Compile source code or verify that it compiles without errors",
            "Execute programs, scripts, shell commands, or build tools (Maven, Gradle, npm, etc.)",
            "Run automated tests or observe test results",
            "Observe runtime output, logs, or stdout/stderr of any process",
            "Install or upgrade dependencies",
            "Start, stop, or interact with running servers or external services"
    );

    /**
     * Returns a formatted block ready for injection into an LLM system prompt.
     * Callers may append dynamic entries before calling {@code render()} in the future.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- SYSTEM CAPABILITY MANIFEST ---\n");
        sb.append("This system operates by reading and writing files and reasoning via LLM.\n");
        sb.append("It has NO ability to compile, build, or execute code.\n\n");

        sb.append("CAN:\n");
        for (String c : can) {
            sb.append("  + ").append(c).append("\n");
        }

        sb.append("\nCANNOT:\n");
        for (String c : cannot) {
            sb.append("  - ").append(c).append("\n");
        }

        sb.append("\nIMPORTANT: Do NOT propose goals that require compiling, running, or testing code.\n");
        sb.append("Goals like \"run the tests\", \"verify compilation\", \"execute the script\",\n");
        sb.append("or \"check that the application starts\" cannot be satisfied and will always fail.\n");
        sb.append("Propose only goals achievable through file reads and writes.\n");
        sb.append("--- END SYSTEM CAPABILITY MANIFEST ---");

        return sb.toString();
    }
}
