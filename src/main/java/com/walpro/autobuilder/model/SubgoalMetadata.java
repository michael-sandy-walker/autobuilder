package com.walpro.autobuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubgoalMetadata(String id, String description) {

    public boolean isValid() {
        return id != null && !id.isBlank()
                && description != null && !description.isBlank();
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public String toString() {
        return id != null ? id + " – " + description : "[bootstrap] " + description;
    }

    public static SubgoalMetadata of(String id, String description) {
        return new SubgoalMetadata(id, description);
    }

    public static SubgoalMetadata empty() {
        return new SubgoalMetadata(null, null);
    }
}
