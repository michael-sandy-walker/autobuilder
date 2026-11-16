package com.walpro.autobuilder.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-EV: deleting a project from the home screen, in two steps the user set apart (2026-11-16).
 *
 * <p><b>Delete</b> moves the project's directory into the deleted folder, as {@code <name>-<when>}: nothing is
 * erased, and moving it back undoes it. Its history in the shared graph stays. Refused for the project that is open or
 * running, and for a versioned toy case -- a project whose {@code bootstrap/} is tracked in git belongs to the
 * repository, not to the list.
 *
 * <p><b>Emptying the trash</b> is the permanent step, only when asked for: every deleted directory is erased, and with
 * it the project's history in the graph -- the lineage its {@code meta/run-identity.json} names. A lineage that a
 * project still in the list also names is kept (a copied project carries its original's lineage, and erasing it would
 * erase the original's history); emptying says which it kept. Nothing outside the deleted folder and the graph is
 * touched: the run archives of scripted runs belong to the toy cases, which cannot be deleted here.
 */
@Slf4j
public final class Trash {

    /**
     * One deleted project: its id (its directory's name in the deleted folder, so two deletions of one name stay apart),
     * its name, that directory, when it was deleted, and its lineage.
     */
    public record Entry(String id, String name, String folder, String deletedAt, String lineageId) {}

    /** What emptying did: the entries erased, the graph nodes erased with their lineages, the lineages kept and why. */
    public record Emptied(List<Entry> erased, long graphNodes, List<String> lineagesKept, List<String> problems) {}

    /** Erases one lineage's nodes from the graph; returns how many there were. */
    public interface GraphEraser {
        long erase(String lineageId);
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Pattern STAMPED = Pattern.compile("^(.+)-(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path projects;
    private final Path bin;

    public Trash(Path projects, Path bin) {
        this.projects = projects;
        this.bin = bin;
    }

    public Path bin() {
        return bin;
    }

    /**
     * Moves {@code name}'s directory into the deleted folder.
     *
     * @param open whether a project directory is the one open or running now
     * @throws IllegalArgumentException no such project (or not a plain name)
     * @throws IllegalStateException    the project may not be deleted, and why
     */
    public Entry delete(String name, Predicate<Path> open) throws IOException {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new IllegalArgumentException("a project is deleted by the name of its directory in " + projects);
        }
        Path dir = projects.resolve(name).normalize();
        if (!dir.getParent().equals(projects.normalize()) || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("no project named " + name + " in " + projects);
        }
        if (open.test(dir)) throw new IllegalStateException(name + " is open; go back to the home screen first");
        if (tracked(dir)) {
            throw new IllegalStateException(name + " is a versioned toy case (its bootstrap/ is tracked in git): "
                    + "it belongs to the repository and is not deleted from here");
        }
        Files.createDirectories(bin);
        String when = LocalDateTime.now().format(STAMP);
        Path to = bin.resolve(name + "-" + when);
        Files.move(dir, to);
        log.warn("T4-EV: deleted {} -- moved to {}", dir, to);
        return entryOf(to);
    }

    /** The deleted projects, newest first. */
    public List<Entry> list() throws IOException {
        if (!Files.isDirectory(bin)) return List.of();
        try (Stream<Path> s = Files.list(bin)) {
            return s.filter(Files::isDirectory).map(this::entryOf)
                    .sorted(Comparator.comparing(Entry::deletedAt).reversed()).toList();
        }
    }

    /** Erases every deleted project for good: its directory, and its lineage in the graph unless a listed project shares it. */
    public Emptied empty(GraphEraser graph) throws IOException {
        return erase(list(), graph);
    }

    /** Erases one deleted project for good, by its id -- the same as emptying, for that one. */
    public Emptied erase(String id, GraphEraser graph) throws IOException {
        return erase(List.of(entry(id)), graph);
    }

    /**
     * Moves a deleted project back into the projects directory under its own name.
     *
     * @throws IllegalArgumentException no deleted project with that id
     * @throws IllegalStateException    a project of that name is in the list again -- nothing is overwritten
     */
    public Entry restore(String id) throws IOException {
        Entry e = entry(id);
        Path to = projects.resolve(e.name());
        if (Files.exists(to)) {
            throw new IllegalStateException("a project named " + e.name() + " is in the list again; rename or delete it first, "
                    + "or move " + e.folder() + " back by hand under another name");
        }
        Files.createDirectories(projects);
        Files.move(Path.of(e.folder()), to);
        log.warn("T4-EV: {} moved back to {}", e.folder(), to);
        return e;
    }

    private Entry entry(String id) throws IOException {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.startsWith(".")) {
            throw new IllegalArgumentException("no deleted project " + id);
        }
        return list().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no deleted project " + id));
    }

    private Emptied erase(List<Entry> entries, GraphEraser graph) throws IOException {
        Set<String> stillListed = listedLineages();
        List<Entry> erased = new ArrayList<>();
        List<String> kept = new ArrayList<>(), problems = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        long nodes = 0;
        for (Entry e : entries) {
            String lineage = e.lineageId();
            if (lineage != null && !done.contains(lineage)) {
                if (stillListed.contains(lineage)) {
                    kept.add(lineage + " (" + e.name() + "): a project in the list carries the same lineage");
                } else {
                    try {
                        nodes += graph.erase(lineage);
                        done.add(lineage);
                    } catch (RuntimeException ex) {
                        // The directory is kept too: erasing again removes both once the graph answers.
                        problems.add(e.name() + ": its graph history was not erased (" + ex.getMessage() + "); kept in the trash");
                        continue;
                    }
                }
            }
            erase(Path.of(e.folder()));
            erased.add(e);
        }
        log.warn("T4-EV: erased for good -- {} project(s), {} graph node(s), {} lineage(s) kept, {} problem(s)",
                erased.size(), nodes, kept.size(), problems.size());
        return new Emptied(erased, nodes, kept, problems);
    }

    private Entry entryOf(Path folder) {
        String dirName = folder.getFileName().toString();
        Matcher m = STAMPED.matcher(dirName);
        String name = m.matches() ? m.group(1) : dirName;
        String when = m.matches() ? m.group(2) : "";
        return new Entry(dirName, name, folder.toString(), when, lineageOf(folder));
    }

    /** The lineage a project directory names in {@code meta/run-identity.json}; null when it names none. */
    static String lineageOf(Path dir) {
        Path id = dir.resolve("meta/run-identity.json");
        if (!Files.isRegularFile(id)) return null;
        try {
            JsonNode n = JSON.readTree(id.toFile()).get("lineageId");
            return n == null || n.asText().isBlank() ? null : n.asText();
        } catch (IOException e) {
            return null;
        }
    }

    private Set<String> listedLineages() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isDirectory(projects)) return out;
        try (Stream<Path> s = Files.list(projects)) {
            s.filter(Files::isDirectory).map(Trash::lineageOf).filter(java.util.Objects::nonNull).forEach(out::add);
        }
        return out;
    }

    /** Whether the directory's {@code bootstrap/} has files tracked in git -- a versioned toy case. False outside a repository. */
    static boolean tracked(Path dir) {
        if (!Files.isDirectory(dir.resolve("bootstrap"))) return false;
        try {
            Process p = new ProcessBuilder("git", "-C", dir.toString(), "ls-files", "--", "bootstrap")
                    .redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.getErrorStream().readAllBytes();
            return p.waitFor() == 0 && !out.isBlank();
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void erase(Path folder) throws IOException {
        Path inBin = folder.toAbsolutePath().normalize();
        if (!inBin.startsWith(bin.toAbsolutePath().normalize()) || inBin.equals(bin.toAbsolutePath().normalize())) {
            throw new IOException("refusing to erase " + folder + ": not inside the deleted folder " + bin);
        }
        try (Stream<Path> walk = Files.walk(inBin)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
