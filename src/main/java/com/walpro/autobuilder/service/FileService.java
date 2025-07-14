package com.walpro.autobuilder.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.model.FileInfo;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FileService {

    private final Path rootDir;
    private final ObjectMapper objectMapper;
    private FileWatcherService fileWatcherService;

    public FileService(@Value("${file.base-path}") String basePath, ObjectMapper objectMapper) {
        this.rootDir = Paths.get(basePath).normalize().toAbsolutePath();
        this.objectMapper = objectMapper;
    }
    
    @Autowired(required = false)
    public void setFileWatcherService(FileWatcherService fileWatcherService) {
        this.fileWatcherService = fileWatcherService;
    }
    
    @PostConstruct
    public void ensureDefaultFolders() {
        try {
            List<String> requiredFolders = List.of("bootstrap", "output", "logs", "test", "mutations");

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
        Objects.requireNonNull(relativePath, "path must not be null");
        Path resolved = rootDir.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(rootDir)) {
            throw new SecurityException("Access outside root directory is not allowed.");
        }
        return resolved;
    }

    public String readFile(String relativePath) throws IOException {
        Path path = resolveSecurePath(relativePath);
        return readFileResolved(path);
    }
    
    public String readFileResolved(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public void writeFile(String relativePath, String content) throws IOException {
        Path filePath = resolveSecurePath(relativePath);
        writeFileResolved(filePath, content);
    }
    
    public void writeFileResolved(Path path, String content) throws IOException {
        boolean existed = Files.exists(path);

        Files.createDirectories(path.getParent()); // ensure subdirs exist
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        String kind = existed ? "ENTRY_MODIFY" : "ENTRY_CREATE";
        pushManualEvent(kind, path.toString());
    }
    
    public List<FileInfo> listFiles(String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
        if (!Files.isDirectory(path)) {
            throw new IOException("Not a directory: " + path);
        }

        try (Stream<Path> stream = Files.walk(path)) { // Use walk instead of list
            return stream
                .filter(Files::isRegularFile)
                .map(file -> {
                    try {
                        return new FileInfo(
                        	path.relativize(file).toString(), // make path relative
                            Files.size(file),
                            Files.getLastModifiedTime(file).toMillis()
                        );
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .collect(Collectors.toList());
        }
    }

//    public List<FileInfo> listFilesResolved(Path path) throws IOException {
//        if (!Files.isDirectory(path)) {
//            throw new IOException("Not a directory: " + path);
//        }
//
//        try (Stream<Path> stream = Files.walk(path)) { // Use walk instead of list
//            return stream
//                .filter(Files::isRegularFile)
//                .map(file -> {
//                    try {
//                        return new FileInfo(
//                        	path.relativize(file).toString(), // make path relative
//                            Files.size(file),
//                            Files.getLastModifiedTime(file).toMillis()
//                        );
//                    } catch (IOException e) {
//                        throw new UncheckedIOException(e);
//                    }
//                })
//                .collect(Collectors.toList());
//        }
//    }
    
    private void pushManualEvent(String kind, String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
        log.debug("PUSHING EVENT: " + kind + " " + relativePath);
        if (fileWatcherService != null) {
            fileWatcherService.pushManualEvent(kind, path.toString());
        }
    }
    
    public void deleteFile(String relativePath) throws IOException {
        Path path = resolveSecurePath(relativePath);
        if (Files.exists(path)) {
            Files.delete(path);
            pushManualEvent("ENTRY_DELETE", path.toString());
        }
    }
    
    public byte[] readBytes(String relativePath) throws IOException {
        Path path = resolveSecurePath(relativePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IOException("File does not exist: " + relativePath);
        }
        return Files.readAllBytes(path);
    }
    
    public Optional<String> detectMimeType(String relativePath) throws IOException {
        Path path = resolveSecurePath(relativePath);
        return detectMimeTypeResolved(path);
    }
    
    public Optional<String> detectMimeTypeResolved(Path path) throws IOException {
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
    
    public Map<String, String> readFolderContents(String relativePath) throws IOException {
        Path path = resolveSecurePath(relativePath);
        return readFolderContentsResolved(path);
    }
    
    public Map<String, String> readFolderContentsResolved(Path path) throws IOException {
        return listFiles(path.toString()).stream().collect(Collectors.toMap(
            FileInfo::getName,
            file -> {
                try {
                    return readFile(path + "/" + file.getName());
                } catch (IOException e) {
                    log.error("Failed to read: {}/{}", path, file.getName(), e);
                    return "";
                }
            }
        ));
    }
    
    public boolean isDirectory(String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
    	return isDirectoryResolved(path);
    }
    
    public boolean isDirectoryResolved(Path path) throws IOException {
    	return Files.isDirectory(path);
    }
    
    public Stream<Path> walk(String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
    	return walkResolved(path);
    }
    
    public Stream<Path> walkResolved(Path path) throws IOException {
    	return Files.walk(path);
    }
    
    public boolean isRegularFile(String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
    	return isRegularFileResolved(path);
    }
    
    public boolean isRegularFileResolved(Path path) throws IOException {
    	return Files.isRegularFile(path);
    }
    
    public Stream<Path> list(String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
    	return listResolved(path);
    }
    
    public Stream<Path> listResolved(Path path) throws IOException {
    	return Files.list(path);
    }
    
    public boolean exists(String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
    	return existsResolved(path);
    }
    
    public boolean existsResolved(Path path) throws IOException {
    	return Files.exists(path);
    }
    
    public void writeString(String relativePath, CharSequence csq, OpenOption... options) throws IOException {
    	Path path = resolveSecurePath(relativePath);
    	Files.writeString(path, csq, options);
    }
    
    public void createDirectories(String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
    	Files.createDirectories(path);
    }
    
    public List<String> readAllLines(String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
    	return Files.readAllLines(path);
    }
    
    public byte[] readAllBytes(String relativePath) throws IOException {
    	Path path = resolveSecurePath(relativePath);
    	return readAllBytesResolved(path);
    }
    
    public byte[] readAllBytesResolved(Path path) throws IOException {
    	return Files.readAllBytes(path);
    }
    
    public String readString(String relativePath) throws IOException {
    	Path filePath = resolveSecurePath(relativePath);
    	return readStringResolved(filePath);
    }
    
    public String readStringResolved(Path path) throws IOException {
    	return Files.readString(path);
    }

    public java.nio.file.attribute.FileTime getLastModifiedTime(String relativePath) throws IOException {
        Path path = resolveSecurePath(relativePath);
        return java.nio.file.Files.getLastModifiedTime(path);
    }

    public List<Path> listSubfolders(String relativePath) throws IOException {
        Path path = resolveSecurePath(relativePath);
        return listSubfoldersResolved(path);
    }
    
    public List<Path> listSubfoldersResolved(Path path) throws IOException {
        try (Stream<Path> stream = Files.list(path)) {
            return stream.filter(Files::isDirectory).collect(Collectors.toList());
        }
    }

    public List<String> listAllRelativeFilePathsUnder(String relativePath) throws IOException {
        Path path = resolveSecurePath(relativePath);
        try (Stream<Path> stream = Files.walk(path)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> path.relativize(p).toString().replace('\\', '/')) // safe relativize
                    .collect(Collectors.toList());
        }
    }

    public void deleteRecursively(String relativePath) throws IOException {
        Path path = resolveSecurePath(relativePath);
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()) // delete files before dirs
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                throw new UncheckedIOException("Failed to delete: " + p, e);
                            }
                        });
            }
        }
    }
    
    public void writeJson(String relativePath, Object object) throws IOException {
        Path path = resolveSecurePath(relativePath);
        writeJsonResolved(path, object);
    }

    public void writeJsonResolved(Path path, Object object) throws IOException {
        Files.createDirectories(path.getParent());
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
        Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

   
}
