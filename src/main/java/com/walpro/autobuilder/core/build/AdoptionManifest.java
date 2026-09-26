package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * T4-DK / T4-CQ: what the loop has changed since it adopted a tree -- a fact, not a reading.
 *
 * <p>receiver-questions run 2026-09-26_22-44-36 reached its specification at minute 20 and ran to the
 * deadline: the planner had turned the seed's "do not modify src/test, keep every other class as it is"
 * into a subgoal whose criteria ("No files under src/test/java have been modified", "All other classes
 * under src/main/java remain unchanged"), and a model reading the source can confirm neither -- a
 * reading shows what a file says, not whether it changed. Whether a file changed has an authority: its
 * content at adoption. At adoption the digest of every file of the tree is recorded once, under
 * {@value #PATH}; {@link #changes()} compares the tree with it.
 *
 * <p>Only an ADOPTED tree has one: a project the loop wrote from a seed has no "as it was" to keep.
 */
@Slf4j
@Component
public class AdoptionManifest {

    public static final String PATH = "meta/adoption-manifest.json";

    private final FileService fileService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdoptionManifest(FileService fileService) {
        this.fileService = fileService;
    }

    /** What changed since adoption, path relative to the project root. */
    public record Changes(List<String> modified, List<String> added, List<String> removed) {
        public List<String> changedOrAdded() {
            List<String> out = new ArrayList<>(modified);
            out.addAll(added);
            return out;
        }
    }

    /** Records the digest of every file of the adopted tree, once: a manifest already there is the adoption's. */
    public void recordIfAbsent() {
        try {
            if (fileService.exists(PATH)) return;
            Path out = fileService.resolveSecurePath("output");
            if (!Files.isDirectory(out)) return;
            Map<String, String> digests = digests(out);
            fileService.writeString(PATH, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(digests));
            log.warn("T4-DK: recorded the digest of {} adopted file(s) under {} -- 'what changed since adoption' is now a fact",
                    digests.size(), PATH);
        } catch (Exception e) {
            log.warn("T4-DK: could not record the adoption manifest: {}", e.getMessage());
        }
    }

    /** The changes since adoption; empty when this tree was not adopted or the manifest is unreadable. */
    public Optional<Changes> changes() {
        try {
            if (!fileService.exists(PATH)) return Optional.empty();
            Map<String, String> adopted = mapper.readValue(fileService.readString(PATH), new TypeReference<TreeMap<String, String>>() { });
            Path out = fileService.resolveSecurePath("output");
            if (!Files.isDirectory(out)) return Optional.empty();
            return Optional.of(compare(adopted, digests(out)));
        } catch (Exception e) {
            log.debug("T4-DK: adoption manifest unreadable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static Changes compare(Map<String, String> adopted, Map<String, String> now) {
        List<String> modified = new ArrayList<>(), added = new ArrayList<>(), removed = new ArrayList<>();
        for (Map.Entry<String, String> e : now.entrySet()) {
            String before = adopted.get(e.getKey());
            if (before == null) added.add(e.getKey());
            else if (!before.equals(e.getValue())) modified.add(e.getKey());
        }
        for (String p : adopted.keySet()) if (!now.containsKey(p)) removed.add(p);
        return new Changes(modified, added, removed);
    }

    /** Every regular file of the tree except build output, path -> sha-256. */
    static Map<String, String> digests(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> w = Files.walk(root)) {
            for (Path f : w.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith("target/") || rel.contains("/target/") || rel.startsWith(".git/")) continue;
                out.put(rel, HexFormat.of().formatHex(md.digest(Files.readAllBytes(f))));
            }
        }
        return out;
    }
}
