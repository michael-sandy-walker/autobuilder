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
    private final ReverseEngineeringConfirmation confirmation;
    private final String fileBasePath;

    private volatile State state = new State("idle", null, null, null, null, null, null, null);

    public ReverseEngineeringJob(ReverseEngineeringService service, SeedComposer seedComposer,
                                 ReverseEngineeringConfirmation confirmation, @Value("${file.base-path}") String fileBasePath) {
        this.service = service;
        this.seedComposer = seedComposer;
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
     * @param services T4-EN: with {@code phase} {@code needs-services}, what the project needs started to run at all --
     *                 for the operator to say yes (edited or not) or no to
     */
    public record State(String phase, String stage, Instant since, ReverseEngineeringService.Account account, String error,
                        String kept, String seed, java.util.List<String> refused,
                        java.util.List<com.walpro.autobuilder.core.services.ServiceProposal> services) {
        public State(String phase, String stage, Instant since, ReverseEngineeringService.Account account, String error,
                     String kept, String seed, java.util.List<String> refused) {
            this(phase, stage, since, account, error, kept, seed, refused, null);
        }
    }

    /**
     * T4-EK slice (b): the seed the operator confirmed made into the project's purpose and goal tree. Refused unless an
     * account is waiting for confirmation.
     */
    public synchronized ReverseEngineeringConfirmation.Made confirm(String seed) throws java.io.IOException {
        State s = state;
        if (!"done".equals(s.phase()) || s.account() == null) {
            throw new IllegalStateException("there is no reverse-engineered account waiting to be confirmed");
        }
        ReverseEngineeringConfirmation.Made made = confirmation.confirm(s.account(), seed);
        state = new State("confirmed", null, s.since(), s.account(), null, s.kept(), seed, s.refused());
        return made;
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
                state = new State("done", null, since, account, null, kept.toString(), composed.text(), composed.refused());
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
