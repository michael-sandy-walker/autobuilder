package com.walpro.autobuilder.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T4-EV: delete moves a project into the deleted folder; emptying the trash erases it and its graph history. */
class TrashTest {

    @TempDir
    Path root;

    private Path projects() {
        return root.resolve("data");
    }

    private Path project(String name, String lineage) throws Exception {
        Path dir = projects().resolve(name);
        Files.createDirectories(dir.resolve("output/src"));
        Files.writeString(dir.resolve("output/src/A.java"), "class A {}");
        if (lineage != null) {
            Files.createDirectories(dir.resolve("meta"));
            Files.writeString(dir.resolve("meta/run-identity.json"), "{\"lineageId\" : \"" + lineage + "\"}");
        }
        return dir;
    }

    private Trash trash() {
        return new Trash(projects(), root.resolve("deleted"));
    }

    @Test
    void aDeletedProjectIsMovedIntoTheDeletedFolder_andListedThereWithItsLineage() throws Exception {
        project("dial", "lineage-1");
        Trash.Entry e = trash().delete("dial", d -> false);

        assertThat(projects().resolve("dial")).doesNotExist();
        assertThat(Path.of(e.folder())).isDirectory().startsWith(root.resolve("deleted"));
        assertThat(Path.of(e.folder(), "output/src/A.java")).hasContent("class A {}");
        assertThat(e.name()).isEqualTo("dial");
        assertThat(e.lineageId()).isEqualTo("lineage-1");
        assertThat(e.deletedAt()).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}");
        assertThat(trash().list()).containsExactly(e);
    }

    @Test
    void theOpenProject_aMissingOne_andAPathAreNotDeleted() throws Exception {
        project("dial", null);
        assertThatThrownBy(() -> trash().delete("dial", d -> d.getFileName().toString().equals("dial")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("open");
        assertThatThrownBy(() -> trash().delete("absent", d -> false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trash().delete("../data", d -> false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(projects().resolve("dial")).isDirectory();
    }

    @Test
    void aVersionedToyCase_itsBootstrapTrackedInGit_isNotDeleted() throws Exception {
        Path dir = project("toy", null);
        Files.createDirectories(dir.resolve("bootstrap"));
        Files.writeString(dir.resolve("bootstrap/seed.txt"), "a seed");
        Process init = new ProcessBuilder("git", "init", "-q", root.toString()).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(init.waitFor() == 0, "git is not available");
        new ProcessBuilder("git", "-C", root.toString(), "add", "data/toy/bootstrap/seed.txt").start().waitFor();

        assertThatThrownBy(() -> trash().delete("toy", d -> false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("toy case");
        assertThat(dir).isDirectory();
        // a project whose bootstrap is NOT tracked is deleted as any other
        Path other = project("mine", null);
        Files.createDirectories(other.resolve("bootstrap"));
        Files.writeString(other.resolve("bootstrap/seed.txt"), "a seed");
        trash().delete("mine", d -> false);
        assertThat(other).doesNotExist();
    }

    @Test
    void emptyingErasesTheFolders_andTheirLineages_butKeepsALineageAListedProjectCarries() throws Exception {
        project("dial", "lineage-1");
        project("copy", "lineage-2");
        project("original", "lineage-2");      // a copy carries its original's lineage
        trash().delete("dial", d -> false);
        trash().delete("copy", d -> false);
        List<String> erased = new ArrayList<>();

        Trash.Emptied done = trash().empty(id -> { erased.add(id); return 7; });

        assertThat(erased).containsExactly("lineage-1");
        assertThat(done.graphNodes()).isEqualTo(7);
        assertThat(done.erased()).extracting(Trash.Entry::name).containsExactlyInAnyOrder("dial", "copy");
        assertThat(done.lineagesKept()).singleElement().asString().contains("lineage-2", "copy");
        assertThat(trash().list()).isEmpty();
        assertThat(projects().resolve("original")).isDirectory();
    }

    @Test
    void aProjectWhoseGraphHistoryCouldNotBeErasedStaysInTheTrash() throws Exception {
        project("dial", "lineage-1");
        trash().delete("dial", d -> false);

        Trash.Emptied done = trash().empty(id -> { throw new IllegalStateException("graph down"); });

        assertThat(done.erased()).isEmpty();
        assertThat(done.problems()).singleElement().asString().contains("dial", "graph down");
        assertThat(trash().list()).extracting(Trash.Entry::name).containsExactly("dial");
    }

    @Test
    void aDeletedProjectIsMovedBackUnderItsName_butNeverOverAProjectOfThatNameAgain() throws Exception {
        project("dial", "lineage-1");
        Trash.Entry e = trash().delete("dial", d -> false);
        assertThat(e.id()).startsWith("dial-");

        trash().restore(e.id());
        assertThat(projects().resolve("dial/output/src/A.java")).hasContent("class A {}");
        assertThat(trash().list()).isEmpty();

        Trash.Entry again = trash().delete("dial", d -> false);
        project("dial", null);                 // a new project took the name meanwhile
        assertThatThrownBy(() -> trash().restore(again.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("dial");
        assertThat(Path.of(again.folder())).isDirectory();
        assertThatThrownBy(() -> trash().restore("nothing-2026-01-01_00-00-00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneDeletedProjectIsErasedForGood_andTheOthersStay() throws Exception {
        project("dial", "lineage-1");
        project("gauge", "lineage-3");
        Trash.Entry dial = trash().delete("dial", d -> false);
        trash().delete("gauge", d -> false);
        List<String> erased = new ArrayList<>();

        Trash.Emptied done = trash().erase(dial.id(), id -> { erased.add(id); return 2; });

        assertThat(erased).containsExactly("lineage-1");
        assertThat(done.erased()).extracting(Trash.Entry::name).containsExactly("dial");
        assertThat(Path.of(dial.folder())).doesNotExist();
        assertThat(trash().list()).extracting(Trash.Entry::name).containsExactly("gauge");
    }
}
