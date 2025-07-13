package com.walpro.autobuilder.tools.gena;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;

public class FileSnapshotStore {

    private final Path snapshotRoot;

    public FileSnapshotStore(Path snapshotRoot) {
        this.snapshotRoot = snapshotRoot;
    }

    /**
     * Saves the current file contents into the snapshot store (if it exists).
     */
    public void savePreviousVersion(String absolutePath) {
        Path sourcePath = Paths.get(absolutePath);
        if (!Files.exists(sourcePath)) return;

        Path targetPath = getSnapshotPath(absolutePath);
        try {
            Files.createDirectories(targetPath.getParent());
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("⚠️ Failed to store snapshot for " + absolutePath + ": " + e.getMessage());
        }
    }

    /**
     * Returns the snapshot content of a file, if it exists.
     */
    public String loadPreviousVersion(String absolutePath) {
        Path snapshotPath = getSnapshotPath(absolutePath);
        if (Files.exists(snapshotPath)) {
            try {
                return Files.readString(snapshotPath);
            } catch (IOException e) {
                System.err.println("⚠️ Failed to read snapshot for " + absolutePath + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Resolves the internal snapshot path for a given absolute path.
     */
    public Path getSnapshotPath(String absolutePath) {
        Path rel = Paths.get(absolutePath).isAbsolute()
                ? Paths.get(absolutePath).subpath(0, Paths.get(absolutePath).getNameCount())
                : Paths.get(absolutePath);
        return snapshotRoot.resolve(rel.toString().replace(':', '_')); // avoid Windows issues
    }
}
