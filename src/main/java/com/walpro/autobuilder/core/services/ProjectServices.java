package com.walpro.autobuilder.core.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * T4-EN: the operator's answer about the services this project needs, kept in the project ({@code meta/services.json})
 * so later starts of the same project -- the reverse engineering's and the loop's own -- start them without asking again.
 * Nothing is ever started without a recorded yes; a recorded no is kept too, so the question is not asked again.
 */
@Component
public class ProjectServices {

    /** @param yes whether the operator said yes; {@code services} what they said it to, as they left it */
    public record Decision(boolean yes, List<ServiceProposal> services, Instant at) {
        public Decision {
            services = services == null ? List.of() : List.copyOf(services);
        }
    }

    private final Path projectRoot;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ProjectServices(@Value("${file.base-path}") String fileBasePath) {
        this.projectRoot = Path.of(fileBasePath).toAbsolutePath().normalize();
    }

    /** The project's name as a container name may carry it. */
    public String project() {
        String n = projectRoot.getFileName() == null ? "project" : projectRoot.getFileName().toString();
        n = n.replaceAll("[^A-Za-z0-9_.-]", "-");
        return n.isEmpty() || !Character.isLetterOrDigit(n.charAt(0)) ? "p" + n : n;
    }

    private Path file() {
        return projectRoot.resolve("meta").resolve("services.json");
    }

    public Optional<Decision> recorded() {
        Path f = file();
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(json.readValue(f.toFile(), Decision.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The services to start before each program start: the recorded yes, else none. */
    public List<ServiceProposal> toStart() {
        return recorded().filter(Decision::yes).map(Decision::services).orElse(List.of());
    }

    public void record(Decision decision) {
        try {
            Files.createDirectories(file().getParent());
            json.writeValue(file().toFile(), decision);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Withdrawn: nothing is started any more, and the question may be asked again. */
    public void withdraw() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
