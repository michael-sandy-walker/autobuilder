package com.walpro.autobuilder.tools.tracker;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;

@Profile("tools")
@Component
public class FileHashTracker extends AbstractHashTracker {

    private static final String CACHE_PATH = "gena/cache/filehashes.json";
    
    public FileHashTracker(ObjectMapper mapper, FileService fileService) {
    	super(mapper, fileService);
    	loadCache(CACHE_PATH);
    }
    
}
