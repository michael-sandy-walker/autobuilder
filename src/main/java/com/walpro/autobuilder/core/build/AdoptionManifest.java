package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.analysis.structure.Endpoint;
import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
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
 *
 * <p><b>What the adopted tree served</b> is recorded beside it, once, under {@value #SERVED_PATH}: the request
 * mappings of its sources. constraint-samples run 2026-10-20_18-04-44 adopted a library cut that serves nothing;
 * its ticket quoted a past field result, "POST /loans -> 201, but the running application answered 500", and the
 * endpoint-coverage rule took the quote for a required endpoint -- root closure was refused at 27/27 and the
 * injected work item had the model add an invented /loans handler. Whether the project serves HTTP at all is a
 * fact of the tree as adopted, not of the ticket's prose ({@link #servedAtAdoption()}).
 */
@Slf4j
@Component
public class AdoptionManifest {

    public static final String PATH = "meta/adoption-manifest.json";
    public static final String SERVED_PATH = "meta/adoption-served.json";

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
            recordServed(out);
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
            Changes all = compare(adopted, digests(out));
            // T6-U: the tests written from the requirements are the framework's, written and restored by it -- none of
            // the loop's changes. Counted, field109's edit-hunks run 1 reached 11/11 and was refused closure to the end
            // on "No file under src/test/java is changed": 98 files were added there, every one a derived test.
            java.util.Set<String> derived = new java.util.HashSet<>();
            for (DerivedTestsFile.Entry e : DerivedTestsFile.read(fileService)) {
                if (e.accepted() && e.path() != null) derived.add(e.path().replace('\\', '/'));
            }
            if (derived.isEmpty()) return Optional.of(all);
            java.util.function.Predicate<String> loops = path -> !derived.contains(path.replace('\\', '/'));
            return Optional.of(new Changes(all.modified().stream().filter(loops).toList(),
                    all.added().stream().filter(loops).toList(), all.removed().stream().filter(loops).toList()));
        } catch (Exception e) {
            log.debug("T4-DK: adoption manifest unreadable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Whether the tree as adopted served any request mapping: empty when this tree was not adopted, or what it served was
     * not recorded or could not be grounded (a mapping path that is not a literal) -- callers keep their rule then.
     */
    public Optional<Boolean> servedAtAdoption() {
        try {
            if (!fileService.exists(SERVED_PATH)) return Optional.empty();
            List<String> served = mapper.readValue(fileService.readString(SERVED_PATH), new TypeReference<List<String>>() { });
            return served == null ? Optional.empty() : Optional.of(!served.isEmpty());
        } catch (Exception e) {
            log.debug("T2-EI: adopted surface unreadable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void recordServed(Path out) throws Exception {
        Map<String, String> sources = new TreeMap<>();
        Path main = out.resolve("src/main/java");
        if (Files.isDirectory(main)) {
            try (Stream<Path> w = Files.walk(main)) {
                for (Path f : w.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")).toList()) {
                    sources.put(out.relativize(f).toString().replace('\\', '/'), Files.readString(f));
                }
            }
        }
        Optional<java.util.Set<Endpoint>> served = new EndpointSurfaceQuery().servedByCode(sources);
        if (served.isEmpty()) return; // not groundable: record nothing, the rule stays as it was
        List<String> rows = served.get().stream().map(Endpoint::toString).sorted().toList();
        fileService.writeString(SERVED_PATH, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
        log.warn("T2-EI: the adopted tree serves {} request mapping(s), recorded under {}{}", rows.size(), SERVED_PATH,
                rows.isEmpty() ? " -- it is not an HTTP application, so endpoints its specification names are not required of it" : "");
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
