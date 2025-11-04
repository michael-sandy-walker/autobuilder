package com.walpro.autobuilder.tools.tracker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;

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
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Hashing failed", e);
        }
    }
}
