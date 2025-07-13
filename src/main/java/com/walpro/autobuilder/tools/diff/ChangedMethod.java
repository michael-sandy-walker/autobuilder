package com.walpro.autobuilder.tools.diff;

public record ChangedMethod(
	    String name,
	    ChangeType type,
	    String oldMethodCode,
	    String newMethodCode
	) {
	    public enum ChangeType { ADDED, REMOVED, MODIFIED }

	    public ChangedMethod(String name, ChangeType type) {
	        this(name, type, null, null);
	    }
}

