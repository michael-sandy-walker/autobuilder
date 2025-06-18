package com.walpro.autobuilder.goal.graph;

import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class GoalNodeProjection {
    private String id;
    private String description;
    private String role;
    private String type;
    private String status;
    private String created;
    private Map<String, Object> metadata;
    private String parentId;

    public GoalEntity toEntity() {
        return new GoalEntity(
            id,
            description,
            role,
            GoalType.valueOf(type),
            GoalStatus.valueOf(status),
            created != null ? Instant.parse(created) : Instant.now(),
            parentId,
            metadata
        );
    }
}
