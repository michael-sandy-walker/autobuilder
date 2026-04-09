package com.walpro.autobuilder.goal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.walpro.memory.model.Artifact;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoalEntity {

    private String id;
    private String description;
    private String role;
    private GoalType type;
    @JsonIgnore
    private String parentId;
    private GoalStatus status;
    private Instant created;
    private Instant completed;
    private Instant givenUp;
    private Map<String, Object> metadata = new HashMap<>();
    private GoalKindClassifier.GoalKind kind;
    private List<Artifact> expectedArtifacts = new ArrayList<>(); // TODO: map this to be useful

    @JsonProperty("subGoals")
    private List<GoalEntity> subGoals = new ArrayList<>();

    public GoalEntity() {}

    public GoalEntity(String id, String description, String role, GoalType type, String parentId) {
        this.id = id;
        this.description = description;
        this.role = role;
        this.type = type;
        this.parentId = parentId;
        this.status = GoalStatus.ACTIVE;
        this.created = Instant.now();
    }
    
    public GoalEntity(String id, String description, String role, GoalType type,
    		GoalStatus status, Instant created, String parentId,
    		Map<String, Object> metadata) {
    	this.id = id;
    	this.description = description;
    	this.role = role;
    	this.type = type;
    	this.status = status != null ? status : GoalStatus.ACTIVE;
    	this.created = created != null ? created : Instant.now();
    	this.parentId = parentId;
    	this.metadata = metadata != null ? metadata : new HashMap<>();
    }
    
    @JsonIgnore
    public int getMergeCount() {
        Object val = metadata != null ? metadata.get("mergeCount") : null;
        return val instanceof Number n ? n.intValue() : 0;
    }

    public void markCompleted() {
        this.status = GoalStatus.COMPLETED;
        this.completed = Instant.now();
    }

    public void markGivenUp() {
        this.status = GoalStatus.GIVEN_UP;
        this.givenUp = Instant.now();
    }

}
