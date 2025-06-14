package com.walpro.autobuilder.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.walpro.autobuilder.model.FileInfo;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FileService {
	
	@Autowired
	private FileWatcherService fileWatcherService;

    private final Path rootDir;

    public FileService(@Value("${file.base-path}") String basePath) {
        this.rootDir = Paths.get(basePath).normalize().toAbsolutePath();
    }
    
    @PostConstruct
    public void init() {
        System.out.println("FileService started with fileWatcherService: " + fileWatcherService);
    }
    
    @PostConstruct
    public void ensureDefaultDirs() throws IOException {
        for (String dir : List.of("input", "output", "logs")) {
            Path subdir = rootDir.resolve(dir).normalize();
            if (!Files.exists(subdir)) {
                Files.createDirectories(subdir);
                System.out.println("Created default folder: " + subdir);
            }
        }
    }
    
    @PostConstruct
    public void ensureDefaultFolders() {
        try {
            List<String> requiredFolders = List.of("bootstrap", "output", "logs", "test");

            for (String folder : requiredFolders) {
                Path dir = resolveSecurePath(folder);
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                    log.info("Created missing folder: {}", dir);
                } else {
                    log.debug("Folder exists: {}", dir);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to ensure default folders exist", e);
        }
    }

    public Path resolveSecurePath(String relativePath) throws IOException {
        Path resolved = rootDir.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(rootDir)) {
            throw new SecurityException("Access outside root directory is not allowed.");
        }
        return resolved;
    }

    public String readFile(String relativePath) throws IOException {
        Path filePath = resolveSecurePath(relativePath);
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    public void writeFile(String relativePath, String content) throws IOException {
        Path filePath = resolveSecurePath(relativePath);
        boolean existed = Files.exists(filePath);

        Files.createDirectories(filePath.getParent()); // ensure subdirs exist
        Files.writeString(filePath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        String kind = existed ? "ENTRY_MODIFY" : "ENTRY_CREATE";
        pushManualEvent(kind, filePath.toString());
    }

    public List<FileInfo> listFiles(String relativeDir) throws IOException {
        Path dirPath = resolveSecurePath(relativeDir);
        if (!Files.isDirectory(dirPath)) {
            throw new IOException("Not a directory: " + relativeDir);
        }

        try (Stream<Path> stream = Files.walk(dirPath)) { // Use walk instead of list
            return stream
                .filter(Files::isRegularFile)
                .map(path -> {
                    try {
                        return new FileInfo(
                            dirPath.relativize(path).toString(), // make path relative
                            Files.size(path),
                            Files.getLastModifiedTime(path).toMillis()
                        );
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .collect(Collectors.toList());
        }
    }
    
    private void pushManualEvent(String kind, String path) {
        System.out.println("PUSHING EVENT: " + kind + " " + path);
        if (fileWatcherService != null) {
            fileWatcherService.pushManualEvent(kind, path);
        }
    }
    
    public void deleteFile(String relativePath) throws IOException {
        Path filePath = resolveSecurePath(relativePath);
        if (Files.exists(filePath)) {
            Files.delete(filePath);
            pushManualEvent("ENTRY_DELETE", filePath.toString());
        }
    }
    
    public byte[] readBytes(String relativePath) throws IOException {
        Path filePath = resolveSecurePath(relativePath);
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new IOException("File does not exist: " + relativePath);
        }
        return Files.readAllBytes(filePath);
    }
    
    public Optional<String> detectMimeType(Path path) {
        try {
            return Optional.ofNullable(Files.probeContentType(path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
    
    public String saveUploadedFile(MultipartFile multipartFile, String optionalPath) throws IOException {
        if (multipartFile.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }

        Path targetPath = resolveSecurePath(optionalPath != null ? optionalPath : multipartFile.getOriginalFilename());

        Files.createDirectories(targetPath.getParent());
        try (InputStream in = multipartFile.getInputStream()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        fileWatcherService.pushManualEvent("ENTRY_MODIFY", targetPath.toString());
        return rootDir.relativize(targetPath).toString();
    }
    
    public void copyFolder(String from, String to) throws IOException {
        Path sourceDir = resolveSecurePath(from);
        Path targetDir = resolveSecurePath(to);

        if (!Files.exists(sourceDir)) {
            throw new IOException("Source directory does not exist: " + sourceDir);
        }

        Files.walk(sourceDir).forEach(source -> {
        	Path relative = sourceDir.relativize(source);
            Path target = targetDir.resolve(relative);
            try {
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy " + source + " to " + target, e);
            }
        });
    }
    
    public Map<String, String> readFolderContents(String relativeDir) throws IOException {
        return listFiles(relativeDir).stream().collect(Collectors.toMap(
            FileInfo::getName,
            file -> {
                try {
                    return readFile(relativeDir + "/" + file.getName());
                } catch (IOException e) {
                    log.error("Failed to read: {}/{}", relativeDir, file.getName(), e);
                    return "";
                }
            }
        ));
    }

}
