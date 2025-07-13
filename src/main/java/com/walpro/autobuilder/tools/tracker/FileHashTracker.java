package com.walpro.autobuilder.tools.tracker;

public class FileHashTracker extends AbstractHashTracker {

    private static final String CACHE_PATH = "gena/cache/filehashes.json";

    public FileHashTracker() {
        super(CACHE_PATH);
    }
}
