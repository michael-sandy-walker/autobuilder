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
    private final String fileBasePath;

    private volatile State state = new State("idle", null, null, null, null, null);

    public ReverseEngineeringJob(ReverseEngineeringService service, @Value("${file.base-path}") String fileBasePath) {
        this.service = service;
        this.fileBasePath = fileBasePath;
    }

    /**
     * @param phase   {@code idle}, {@code running}, {@code done} or {@code failed}
     * @param stage   what it is doing now, in words
     * @param kept    where the account was kept, once done
     */
    public record State(String phase, String stage, Instant since, ReverseEngineeringService.Account account, String error,
                        String kept) {
    }

    public State state() {
        return state;
    }

    /** Starts it on the project's code; false when one is already running. */
    public synchronized boolean start() {
        if (state.phase().equals("running")) return false;
        Path code = Path.of(fileBasePath, "output").toAbsolutePath().normalize();
        Instant since = Instant.now();
        state = new State("running", "Starting", since, null, null, null);
        Thread t = new Thread(() -> {
            try {
                Path work = Path.of(System.getProperty("java.io.tmpdir"), "autobuilder-reverse-engineering",
                        Path.of(fileBasePath).toAbsolutePath().normalize().getFileName().toString());
                var account = service.reverseEngineer(code, work,
                        stage -> state = new State("running", stage, since, null, null, null));
                Path keep = Path.of(fileBasePath, "meta", "reverse-engineering");
                Files.createDirectories(keep);
                Path kept = keep.resolve(Path.of(account.copy()).getFileName() + ".json");
                Files.copy(Path.of(account.copy(), "account.json"), kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                state = new State("done", null, since, account, null, kept.toString());
            } catch (Exception | Error e) {
                log.warn("T4-EK: reverse engineering failed", e);
                state = new State("failed", null, since, null, e.getMessage() == null ? e.toString() : e.getMessage(), null);
            }
        }, "reverse-engineering");
        t.setDaemon(true);
        t.start();
        return true;
    }
}
