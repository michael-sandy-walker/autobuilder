package com.walpro.autobuilder.graph.goal;

import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import lombok.*;
import org.springframework.data.neo4j.core.schema.*;

import java.time.Instant;
import java.util.*;

@Node("Goal")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GoalNode {

    @Id
    private String id;

    private String description;
    private String role;
    private GoalType type;
    private GoalStatus status;
    private Instant created;

    @Relationship(type = "SUBGOAL_OF", direction = Relationship.Direction.OUTGOING)
    private GoalNode parent;

    @Relationship(type = "SUBGOAL_OF", direction = Relationship.Direction.INCOMING)
    private Set<GoalNode> subgoals = new HashSet<>();

    private Map<String, Object> metadata = new HashMap<>();

    public GoalNode(GoalEntity g) {
        this.id = g.getId();
        this.description = g.getDescription();
        this.role = g.getRole();
        this.type = g.getType();
        this.status = g.getStatus();
        this.created = g.getCreated();
        this.metadata = g.getMetadata();
        // Parent will be linked later in the service
    }

    public GoalEntity toEntity() {
        return new GoalEntity(id, description, role, type,
                parent != null ? parent.getId() : null);
    }
}
