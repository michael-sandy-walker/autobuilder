package com.walpro.autobuilder.model;

public class FileInfo {
 private String name;
 private long size;
 private long modifiedTime;

 public FileInfo(String name, long size, long modifiedTime) {
     this.name = name;
     this.size = size;
     this.modifiedTime = modifiedTime;
 }

 public String getName() { return name; }
 public long getSize() { return size; }
 public long getModifiedTime() { return modifiedTime; }
}
