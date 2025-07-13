package com.walpro.autobuilder.tools.tracker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Set;

public class MethodHashTracker extends AbstractHashTracker {

    public MethodHashTracker(Path filePath) {
        super(filePath.toString());
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
