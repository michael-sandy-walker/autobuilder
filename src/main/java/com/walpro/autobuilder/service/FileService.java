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
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.walpro.autobuilder.model.FileInfo;

import jakarta.annotation.PostConstruct;

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

        try (Stream<Path> stream = Files.list(dirPath)) {
            return stream
                .filter(Files::isRegularFile)
                .map(path -> {
                    try {
                        return new FileInfo(
                            path.getFileName().toString(),
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
}
