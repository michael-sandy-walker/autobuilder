package com.walpro.autobuilder.goal;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import lombok.Getter;

@Getter
public class GoalEntity {

    private String id;
    private String description;
    private String role;
    private GoalType type;
    private String parentId;
    private GoalStatus status;
    private Instant created;
    private Map<String, Object> metadata = new HashMap<>();

    public GoalEntity() {}

    public GoalEntity(String description, String role, GoalType type, String parentId) {
        this.id = "goal-" + Instant.now().toEpochMilli(); // or UUID.randomUUID().toString()
        this.description = description;
        this.role = role;
        this.type = type;
        this.parentId = parentId;
        this.status = GoalStatus.ACTIVE;
        this.created = Instant.now();
    }
    
    public void markCompleted() {
        this.status = GoalStatus.COMPLETED;
    }

    public void markGivenUp() {
        this.status = GoalStatus.GIVEN_UP;
    }

    public void markActive() {
        this.status = GoalStatus.ACTIVE;
    }

}
