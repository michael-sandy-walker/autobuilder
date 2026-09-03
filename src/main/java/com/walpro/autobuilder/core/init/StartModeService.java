package com.walpro.autobuilder.core.init;

import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Decides whether the {@link StartMode} asked for is one the tree can actually be started in.
 *
 * <p><b>The check that matters is the destructive one.</b> {@code NEW_PROJECT} clears
 * {@code output/}. On a tree the loop wrote that is ordinary. On a codebase someone else wrote it
 * is data loss, and until now the standard entry point defaulted straight to it. So the mode is
 * compared against what is on disk, and the combination that destroys someone's project is refused
 * rather than warned about.
 *
 * <p>The situation is read from the tree's own evidence, not from configuration: a project the loop
 * built leaves {@code meta/} behind, and one it did not leaves sources with no {@code meta/} at all.
 * That is the same distinction the cold read draws, and it is deliberately conservative — an
 * ambiguous tree is treated as foreign, because the cost of guessing wrong runs one way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StartModeService {

    /**
     * What is in a target directory, judged from the directory.
     *
     * <p><b>One home, because two nearly-identical rules already drifted.</b> The catalogue and the
     * startup check each judged authorship their own way and disagreed about the same tree within
     * minutes of both existing: one offered a mode the other then refused. Both now ask here.
     */
    public enum Situation {
        /** Nothing to lose: no sources present. */
        EMPTY,
        /** The loop's own work: a run has actually happened here. */
        LOOP_AUTHORED,
        /** Sources with none of the loop's own work beside them. */
        FOREIGN;

        /**
         * Judges {@code targetDir}, which holds {@code output/} and the loop's bookkeeping beside it.
         *
         * <p>Authorship is evidence of WORK, never the presence of a file the loop creates on the
         * way in. The goal planner writes an EMPTY goal stack while beans are still being created,
         * so a foreign codebase read as the loop's own on its second boot, {@code NEW_PROJECT} was
         * allowed, and the reset began. An empty stack proves nothing, which is what it is.
         */
        public static Situation of(Path targetDir) {
            if (!anyFileUnder(targetDir.resolve("output"))) return EMPTY;
            if (anyFileUnder(targetDir.resolve("mutations"))
                    || anyFileUnder(targetDir.resolve("snapshots"))) {
                return LOOP_AUTHORED;
            }
            Path stack = targetDir.resolve("meta/GoalStack.json");
            if (!Files.isRegularFile(stack)) return FOREIGN;
            try {
                return Files.readString(stack).contains("\"id\"") ? LOOP_AUTHORED : FOREIGN;
            } catch (IOException e) {
                return FOREIGN;
            }
        }

        /**
         * Unreadable counts as occupied. The opposite reading — unsure means empty — is the one
         * that ends in a deletion.
         */
        static boolean anyFileUnder(Path dir) {
            if (!Files.isDirectory(dir)) return false;
            try (Stream<Path> walk = Files.walk(dir)) {
                return walk.anyMatch(Files::isRegularFile);
            } catch (IOException | RuntimeException e) {
                return true;
            }
        }
    }

    /**
     * @param allowed     whether the run may proceed in this mode
     * @param mode        the mode asked for
     * @param situation   what the tree turned out to be
     * @param explanation why, in the words the operator needs — always populated
     */
    public record Decision(boolean allowed, StartMode mode, Situation situation, String explanation) {}

    private final FileService fileService;

    @Setter
    @Value("${autobuilder.start.mode:NEW_PROJECT}")
    private StartMode mode = StartMode.NEW_PROJECT;

    /**
     * The one escape hatch, and it is deliberately awkward to reach. An operator who genuinely
     * wants to discard a codebase the loop did not write has to say so in a setting that names
     * exactly that, rather than getting it from a default they never chose.
     */
    @Setter
    @Value("${autobuilder.start.confirm-discard-foreign-tree:false}")
    private boolean confirmDiscardForeignTree = false;

    public StartMode mode() {
        return mode;
    }

    /** Where a run's own start record lives. Per RUN, not per lineage, so it is not the identity file. */
    public static final String START_RECORD = "meta/run-start.json";

    /**
     * T4-DP slice 3: what a run started as, written once the start has actually happened.
     *
     * @param mode        the mode it began in
     * @param situation   what the tree was when it did
     * @param target      the target directory's name, which is what selects it on the command line
     * @param treeCleared whether anything was deleted to make room, which is the fact worth auditing
     * @param at          when
     */
    public record StartRecord(String mode, String situation, String target, boolean treeCleared, String at) {}

    @Setter
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    /**
     * Records how this run began, after the start actions have settled.
     *
     * <p>Written AFTER rather than before, and that is not incidental: {@code NEW_PROJECT} clears
     * {@code meta/}, so anything written first would be deleted by the very step it describes.
     *
     * <p>Never throws. A run that cannot write its own start record is still a run, and failing the
     * boot over a bookkeeping line would be a worse trade than losing the line.
     */
    public void recordStart(Decision decision, boolean treeCleared) {
        String target = targetName();
        StartRecord record = new StartRecord(decision.mode().name(), decision.situation().name(),
                target, treeCleared, java.time.Instant.now().toString());
        try {
            fileService.ensureFolderExists("meta");
            fileService.writeJson(START_RECORD, record);
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DP: could not write {} ({}) — the run is unaffected", START_RECORD, e.getMessage());
        }
        if (behaviourLogger != null) {
            behaviourLogger.runStarted(record.mode(), record.situation(), target, treeCleared);
        }
        log.info("T4-DP: run started as {} on {} ({}), tree cleared: {}",
                record.mode(), target, record.situation(), treeCleared);
    }

    private String targetName() {
        try {
            Path dir = targetDir();
            return dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
        } catch (RuntimeException e) {
            return "(unknown)";
        }
    }

    /** What this run's own tree is. Same rule the catalogue applies to every other target. */
    public Situation situation() {
        return Situation.of(targetDir());
    }

    private Path targetDir() {
        try {
            return fileService.resolveSecurePath("output").getParent();
        } catch (IOException e) {
            log.warn("T4-DP: could not resolve the target directory ({}) — treating it as occupied",
                    e.getMessage());
            return Path.of("/nonexistent-so-nothing-is-cleared");
        }
    }

    /** Whether this run may begin, and the sentence explaining it either way. */
    public Decision decide() {
        Situation situation = situation();
        return switch (mode) {
            case NEW_PROJECT -> newProject(situation);
            case CONTINUE_LINEAGE -> situation == Situation.EMPTY
                    ? refuse(situation, "there is nothing under output/ to continue; a lineage is "
                            + "continued from work that exists, so start in NEW_PROJECT to create some")
                    : allow(situation, "continuing the lineage already in this tree; nothing is cleared");
            case ADOPT_PROJECT -> switch (situation) {
                case EMPTY -> refuse(situation, "there is nothing under output/ to adopt");
                case FOREIGN -> allow(situation, "adopting a codebase this loop did not write: nothing "
                        + "is cleared and nothing is seeded over it. It has no goal yet — read it with "
                        + "GET /autobuilder/cold-read and set one deliberately");
                case LOOP_AUTHORED -> allow(situation, "adopting a tree this loop wrote earlier, which "
                        + "is allowed and simply means starting from it without a resume");
            };
            case ASK -> refuse(situation, "start mode is ASK, so nothing has been touched and nothing "
                    + "will be until a mode is chosen. This is a resting state, not a pause before a "
                    + "default");
        };
    }

    private Decision newProject(Situation situation) {
        // A new project is built FROM a specification, so without one there is nothing to build and
        // nothing to check the result against. Refusing here also closes a latent crash: the reset
        // reads bootstrap/seed.txt before it deletes anything, so a seedless NEW_PROJECT used to
        // die mid-reset rather than at the decision.
        if (!hasSeed()) {
            return refuse(situation, "NEW_PROJECT builds a project from bootstrap/seed.txt and this "
                    + "target has none, so there is nothing to build and nothing to judge the result "
                    + "against. Add a specification, or use ADOPT_PROJECT to work on what is already here");
        }
        if (situation != Situation.FOREIGN) {
            return allow(situation, situation == Situation.EMPTY
                    ? "starting a new project in an empty tree"
                    : "starting a new project, clearing a tree this loop wrote itself");
        }
        if (confirmDiscardForeignTree) {
            log.warn("T4-DP: discarding a codebase this loop did not write, because "
                    + "autobuilder.start.confirm-discard-foreign-tree is set.");
            return allow(situation, "discarding a codebase this loop did not write, confirmed by "
                    + "autobuilder.start.confirm-discard-foreign-tree");
        }
        return refuse(situation, "output/ holds a codebase this loop did not write, and NEW_PROJECT "
                + "clears output/ before it starts. Refusing, because that would delete someone else's "
                + "work. Use ADOPT_PROJECT to work on it, or set "
                + "autobuilder.start.confirm-discard-foreign-tree=true if discarding it is really what "
                + "you want");
    }

    private Decision allow(Situation s, String why) {
        return new Decision(true, mode, s, why);
    }

    private Decision refuse(Situation s, String why) {
        return new Decision(false, mode, s, why);
    }

    private boolean hasSeed() {
        try {
            return fileService.exists("bootstrap/seed.txt");
        } catch (IOException e) {
            return false;
        }
    }

}
