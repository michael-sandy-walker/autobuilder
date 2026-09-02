package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T4-DQ slice 4: the durable record of which paths this run has explored.
 *
 * <p>Append-only, like the goal-status history and for the same reason: a branch that was
 * abandoned is still a fact about what has been tried, and the whole point of the ledger is that
 * the next fork does not re-walk it. A branch is written once when opened and once when sealed;
 * the fold takes the last entry per id.
 *
 * <p>Run-scoped, under {@code branches/}. A branch names checkpoints, and checkpoints are cleared
 * on both resume and reset, so keeping branches across either would leave a record of paths whose
 * points no longer exist.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StateHolder(value = StateBucket.ALREADY_PERSISTED,
        note = "the ledger IS the durable home — branches/branches.ndjson; nothing is held in memory")
public class BranchLedger {

    public static final String BRANCHES_DIR = "branches";
    public static final String LEDGER_PATH = "branches/branches.ndjson";

    private final FileService fileService;
    private final ObjectMapper objectMapper;

    /** Record a branch, opened or sealed. The last entry for an id is the one that stands. */
    public synchronized void write(ExplorationBranch branch) {
        if (branch == null || branch.id() == null) return;
        try {
            fileService.ensureFolderExists(BRANCHES_DIR);
            fileService.writeString(LEDGER_PATH,
                    objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT)
                            .writeValueAsString(branch) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DQ: could not record branch {}: {}", branch.id(), e.getMessage());
        }
    }

    /** Every branch, latest state per id, in the order they were first opened. */
    public synchronized List<ExplorationBranch> branches() {
        Map<String, ExplorationBranch> latest = new LinkedHashMap<>();
        try {
            if (!fileService.exists(LEDGER_PATH)) return List.of();
            String content = fileService.readString(LEDGER_PATH);
            if (content.isBlank()) return List.of();
            try (var values = objectMapper.readerFor(ExplorationBranch.class).readValues(content)) {
                while (values.hasNextValue()) {
                    ExplorationBranch branch = (ExplorationBranch) values.nextValue();
                    latest.put(branch.id(), branch);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DQ: could not read the branch ledger: {}", e.getMessage());
        }
        return new ArrayList<>(latest.values());
    }

    /** The branch currently being worked, if any. */
    public Optional<ExplorationBranch> openBranch() {
        return branches().stream().filter(ExplorationBranch::open).reduce((a, b) -> b);
    }

    /** Branches the loop has finished with — the ones a comparison ranks. */
    public List<ExplorationBranch> sealedBranches() {
        return branches().stream().filter(b -> !b.open()).toList();
    }

    /** Has any branch already been forked from this checkpoint? */
    public boolean alreadyForkedFrom(String cycleId) {
        if (cycleId == null) return false;
        return branches().stream().anyMatch(b -> cycleId.equals(b.forkedFromCycleId()));
    }
}
