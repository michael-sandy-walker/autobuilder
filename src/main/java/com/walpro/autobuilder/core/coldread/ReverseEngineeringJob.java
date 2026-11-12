package com.walpro.autobuilder.core.coldread;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * T4-EK: reverse engineering of the run's own project as a job the page can follow -- it takes minutes (one build and
 * start per capability and per feature), longer than a request should wait. One at a time.
 *
 * <p>The variants (a copy of the tree each, with its build output) go to the temporary directory; only the account is
 * kept, under the project's {@code meta/reverse-engineering/}, so a person can read it again.
 */
@Slf4j
@Component
public class ReverseEngineeringJob {

    private final ReverseEngineeringService service;
    private final SeedComposer seedComposer;
    private final SeedRefiner seedRefiner;
    private final ReverseEngineeringConfirmation confirmation;
    private final String fileBasePath;

    private volatile State state = new State("idle", null, null, null, null, null, null, null);

    public ReverseEngineeringJob(ReverseEngineeringService service, SeedComposer seedComposer, SeedRefiner seedRefiner,
                                 ReverseEngineeringConfirmation confirmation, @Value("${file.base-path}") String fileBasePath) {
        this.service = service;
        this.seedComposer = seedComposer;
        this.seedRefiner = seedRefiner;
        this.confirmation = confirmation;
        this.fileBasePath = fileBasePath;
    }

    /**
     * @param phase   {@code idle}, {@code running}, {@code done} or {@code failed}
     * @param stage   what it is doing now, in words
     * @param kept    where the account was kept, once done
     * @param seed    the seed composed from the account, for the operator to confirm ({@code phase} {@code done}), or the
     *                one confirmed ({@code confirmed})
     * @param refused the lines of the composition that were refused, with why
     * @param offered  T1-GK: what the README says the project does that no claim shows, for the operator to add or not
     * @param checked  T4-EK slice (c): with {@code confirmed}, what checking the operator's own lines found -- which the
     *                 project does already (with their criteria) and which are open work
     * @param services T4-EN: with {@code phase} {@code needs-services}, what the project needs started to run at all --
     *                 for the operator to say yes (edited or not) or no to
     */
    public record State(String phase, String stage, Instant since, ReverseEngineeringService.Account account, String error,
                        String kept, String seed, java.util.List<String> refused,
                        java.util.List<com.walpro.autobuilder.core.services.ServiceProposal> services,
                        java.util.List<SeedComposer.Offered> offered,
                        java.util.List<ReverseEngineeringService.StatedLine> checked) {
        public State(String phase, String stage, Instant since, ReverseEngineeringService.Account account, String error,
                     String kept, String seed, java.util.List<String> refused,
                     java.util.List<com.walpro.autobuilder.core.services.ServiceProposal> services,
                     java.util.List<SeedComposer.Offered> offered) {
            this(phase, stage, since, account, error, kept, seed, refused, services, offered, null);
        }

        public State(String phase, String stage, Instant since, ReverseEngineeringService.Account account, String error,
                     String kept, String seed, java.util.List<String> refused) {
            this(phase, stage, since, account, error, kept, seed, refused, null, null);
        }

        public State(String phase, String stage, Instant since, ReverseEngineeringService.Account account, String error,
                     String kept, String seed, java.util.List<String> refused,
                     java.util.List<com.walpro.autobuilder.core.services.ServiceProposal> services) {
            this(phase, stage, since, account, error, kept, seed, refused, services, null);
        }
    }

    /**
     * T4-EK slice (b): the seed the operator confirmed made into the project's purpose and goal tree. Refused unless an
     * account is waiting for confirmation.
     */
    public synchronized ReverseEngineeringConfirmation.Made confirm(String seed) throws java.io.IOException {
        return confirm(seed, () -> { });
    }

    /**
     * As above, in the job: null is returned at once, the job reads {@code running} with its stage, and ends
     * {@code confirmed} (with {@link State#checked}) or back at {@code done} with the error; {@code whenConfirmed} runs
     * once the goal tree exists. T4-EK slice (c): lines of the operator's own are first checked against the code
     * ({@link ReverseEngineeringService#checkStated}), which takes as long as exercising the project's variants does.
     */
    public synchronized ReverseEngineeringConfirmation.Made confirm(String seed, Runnable whenConfirmed) throws java.io.IOException {
        State s = state;
        if (!"done".equals(s.phase()) || s.account() == null) {
            throw new IllegalStateException("there is no reverse-engineered account waiting to be confirmed");
        }
        java.util.List<String> stated = ReverseEngineeringConfirmation.stated(s.account(), seed);
        // Always in the job, never in the request: setting the goals runs every kept check once more, which starts the
        // project (and its services) and takes minutes -- the user, 2026-11-11: "the confirm button greyed out, but the
        // page stayed the same for quite a while". The page shows a running job's stage; a waiting request shows nothing.
        Instant began = Instant.now();   // the page counts the minutes of THIS step, not of the job before it
        String setting = "Confirming: the seed and its goals are being set, and every kept check is run once more";
        state = new State("running", stated.isEmpty() ? setting : "Checking whether the project already does what you added",
                began, null, null, null, null, null);
        Thread t = new Thread(() -> {
            try {
                var checked = stated.isEmpty() ? java.util.List.<ReverseEngineeringService.StatedLine>of()
                        : service.checkStated(s.account(), stated,
                                stage -> state = new State("running", stage, began, null, null, null, null, null));
                state = new State("running", setting, began, null, null, null, null, null);
                confirmation.confirm(s.account(), seed, checked);
                // Beside the account it belongs to: reverse-<time>.json -> reverse-<time>.stated.json.
                if (s.kept() != null) {
                    ReverseEngineeringService.keepStated(Path.of(s.kept().replaceFirst("\\.json$", "") + ".stated.json"), checked);
                }
                state = new State("confirmed", null, s.since(), s.account(), null, s.kept(), seed, s.refused(), null,
                        s.offered(), checked);
                whenConfirmed.run();
            } catch (Exception | Error e) {
                log.warn("T4-EK: the confirmation failed; the account waits for confirmation again", e);
                state = new State("done", null, s.since(), s.account(), "Not confirmed: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()), s.kept(), seed, s.refused(), null, s.offered());
            }
        }, "reverse-engineering");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** T4-EN: the operator answered the services question; the job no longer waits on it. */
    public synchronized void servicesAnswered() {
        if ("needs-services".equals(state.phase())) state = new State("idle", null, null, null, null, null, null, null);
    }

    public State state() {
        return state;
    }

    /** Starts it on the project's code; false when one is already running. */
    public synchronized boolean start() {
        if (state.phase().equals("running") || state.phase().equals("confirmed")) return false;
        Path code = Path.of(fileBasePath, "output").toAbsolutePath().normalize();
        Instant since = Instant.now();
        state = new State("running", "Starting", since, null, null, null, null, null);
        Thread t = new Thread(() -> {
            try {
                Path work = Path.of(System.getProperty("java.io.tmpdir"), "autobuilder-reverse-engineering",
                        Path.of(fileBasePath).toAbsolutePath().normalize().getFileName().toString());
                var account = service.reverseEngineer(code, work,
                        stage -> state = new State("running", stage, since, null, null, null, null, null));
                Path keep = Path.of(fileBasePath, "meta", "reverse-engineering");
                Files.createDirectories(keep);
                Path kept = keep.resolve(Path.of(account.copy()).getFileName() + ".json");
                Files.copy(Path.of(account.copy(), "account.json"), kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                state = new State("running", "Writing the seed from what was found (the model writes, every line cites claims)",
                        since, null, null, null, null, null);
                SeedComposer.Composed composed = seedComposer.compose(account);
                // T4-EK slice (c): what was observed of how the project is built, proposed as rules below the seed.
                // T4-EQ: arranged by how the project is used and worded from what was observed; no line added or lost.
                state = new State("running", "Arranging the seed by how the project is used", since, null, null, null, null, null);
                String refined = seedRefiner.refine(account, composed.text());
                state = new State("done", null, since, account, null, kept.toString(),
                        refined.stripTrailing() + ObservedRules.block(account), composed.refused(),
                        null, composed.offered());
            } catch (com.walpro.autobuilder.core.services.ServicesNeeded n) {
                log.warn("T4-EN: reverse engineering waits for the operator -- {}", n.getMessage());
                state = new State("needs-services", null, since, null, null, null, null, null, n.proposals());
            } catch (Exception | Error e) {
                log.warn("T4-EK: reverse engineering failed", e);
                state = new State("failed", null, since, null, e.getMessage() == null ? e.toString() : e.getMessage(), null,
                        null, null);
            }
        }, "reverse-engineering");
        t.setDaemon(true);
        t.start();
        return true;
    }
}
