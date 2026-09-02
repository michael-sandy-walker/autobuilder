package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.event.FileChangeEvent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "a queue of live filesystem events")
public class FileWatcherService {

    private final Path rootDir;
    private final WatchService watchService;    
    
    private final ConcurrentLinkedQueue<FileChangeEvent> eventQueue = new ConcurrentLinkedQueue<>();
    
    private FileService fileService;

    @Autowired
    public FileWatcherService(
        @Value("${file.base-path}") String basePath
    ) throws IOException {
        this.rootDir = Paths.get(basePath).normalize().toAbsolutePath();
        this.watchService = FileSystems.getDefault().newWatchService();
    }
    
    @Autowired
    public void setFileService(@Lazy FileService fileService) {
        this.fileService = fileService;
    }
    
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            registerRecursive(rootDir);
            startWatcher();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    private void registerRecursive(Path start) throws IOException {
    	if (fileService == null) {
    	    throw new IllegalStateException("fileService not yet initialized");
    	}
    	fileService.walkResolved(start)
            .filter(t -> {
				try {
					return fileService.isDirectoryResolved(t);
				} catch (IOException e) {
					log.error("Error during processing isDirectory for dir {}", t);
					return false;
				}
			})
            .forEach(dir -> {
            	log.debug("Watching dir: " + dir);
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
                        log.debug("[" + kind.name() + "] " + fullPath);
                        eventQueue.add(new FileChangeEvent(kind.name(), fullPath.toString()));
                        
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            Path created = fullPath;
                            if (fileService.isDirectoryResolved(created)) {
                                try {
                                	registerRecursive(created);
                                } catch (IOException e) {
                                    log.warn("Error during processing isDirectory for dir {}", created, e);
                                }
                            }
                        }
                    }
                    key.reset();
                } catch (InterruptedException | IOException e) {
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
        log.debug("QUEUED EVENT: " + kind + " " + path);
        eventQueue.add(new FileChangeEvent(kind, path));
    }
    
    public FileChangeEvent pollEvent() {
        return eventQueue.poll();
    }
}
