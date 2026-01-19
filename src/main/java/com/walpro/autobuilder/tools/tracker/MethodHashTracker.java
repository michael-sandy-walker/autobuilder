package com.walpro.autobuilder.tools.tracker;

import java.util.Set;

import com.walpro.autobuilder.util.HashingUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;

@Profile("tools")
@Component
public class MethodHashTracker extends AbstractHashTracker {
	
    private static final String CACHE_PATH = "gena/cache/methodhashes.json";
	
    public MethodHashTracker(ObjectMapper mapper, FileService fileService) {
    	super(mapper, fileService);
    	loadCache(CACHE_PATH);
    }

	public boolean hasChanged(String key, String hash) {
	    return !hash.equals(getPreviousHashes().get(key));
	}
    
	public Set<String> getPreviousKeys() {
	    return getPreviousHashes().keySet();
	}

    public void track(String filePath, String key, String hash) {
    	track(filePath + "::" + key, hash);
    }
    
    public static String hash(String content) {
        return HashingUtils.hashSha256(content);
    }
}
