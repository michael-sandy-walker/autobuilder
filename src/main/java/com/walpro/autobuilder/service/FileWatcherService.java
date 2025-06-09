package com.walpro.autobuilder.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.event.FileChangeEvent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class FileWatcherService {

    private final Path rootDir;
    private final WatchService watchService;
    
    private final ConcurrentLinkedQueue<FileChangeEvent> eventQueue = new ConcurrentLinkedQueue<>();

    @Autowired
    public FileWatcherService(@Value("${file.base-path}") String basePath) throws IOException {
        this.rootDir = Paths.get(basePath).normalize().toAbsolutePath();
        this.watchService = FileSystems.getDefault().newWatchService();
        registerRecursive(rootDir);
        startWatcher();
    }

    private void registerRecursive(Path start) throws IOException {
        Files.walk(start)
            .filter(Files::isDirectory)
            .forEach(dir -> {
            	System.out.println("Watching dir: " + dir);
                try {
                    dir.register(watchService, 
                        StandardWatchEventKinds.ENTRY_CREATE, 
                        StandardWatchEventKinds.ENTRY_MODIFY, 
                        StandardWatchEventKinds.ENTRY_DELETE);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
    }

    @Async
    @PostConstruct
    public void startWatcher() {
        Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                try {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        Path dir = (Path) key.watchable();
                        Path fullPath = dir.resolve((Path) event.context());

                        // Process the file change event
                        System.out.println("[" + kind.name() + "] " + fullPath);
                        eventQueue.add(new FileChangeEvent(kind.name(), fullPath.toString()));
                        
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            Path created = dir.resolve((Path) event.context());
                            if (Files.isDirectory(created)) {
                                try {
                                	registerRecursive(created);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                    key.reset();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @PreDestroy
    public void stopWatcher() throws IOException {
        watchService.close();
    }
    
    public List<FileChangeEvent> getRecentEvents() {
        return eventQueue.stream().collect(Collectors.toList());
    }
    
    public void pushManualEvent(String kind, String path) {
        System.out.println("QUEUED EVENT: " + kind + " " + path);
        eventQueue.add(new FileChangeEvent(kind, path));
    }
    
    public FileChangeEvent pollEvent() {
        return eventQueue.poll();
    }
}
