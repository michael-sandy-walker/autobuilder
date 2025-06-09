package com.walpro.autobuilder.event;

import java.time.Instant;

public class FileChangeEvent {
 private final Instant timestamp;
 private final String kind;
 private final String path;

 public FileChangeEvent(String kind, String path) {
     this.timestamp = Instant.now();
     this.kind = kind;
     this.path = path;
 }

 public Instant getTimestamp() { return timestamp; }
 public String getKind() { return kind; }
 public String getPath() { return path; }
}
