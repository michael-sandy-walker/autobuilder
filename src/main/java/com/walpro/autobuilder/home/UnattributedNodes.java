package com.walpro.autobuilder.home;

import com.walpro.autobuilder.graph.HistoryRead;
import com.walpro.autobuilder.graph.HistorySpan;
import com.walpro.autobuilder.graph.HistoryUse;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.util.Map;
import java.util.TreeMap;

/**
 * T5-BX: the graph's nodes that carry no lineage -- written before every writer stamped one (2026-07..09), so no
 * project and no run can be named for them, and erasing a project by its lineage (T4-EV) never reaches them.
 *
 * <p>Removed only when the operator asks (never automatically), in batches so a long history is not one transaction,
 * and never a node that has a lineage. The {@code SystemState} node is left alone like T4-EV leaves it: it is one
 * node for every project, not history.
 *
 * <p>Like the eraser beside it in the home context, it opens a connection per request and closes it: the home context
 * keeps none.
 */
public class UnattributedNodes {

    private static final String WHERE = "n.lineageId IS NULL AND NOT n:SystemState";

    private final String uri;
    private final String user;
    private final String password;

    public UnattributedNodes(String uri, String user, String password) {
        this.uri = uri;
        this.user = user;
        this.password = password;
    }

    /** How many nodes without a lineage there are, by label. */
    @HistoryRead(span = HistorySpan.ALL_LINEAGES, use = HistoryUse.ADVISORY,
            note = "the home screen's count for the operator, who decides whether to remove them; it reads only the "
                    + "nodes that belong to no lineage")
    public Map<String, Long> count() {
        Map<String, Long> byLabel = new TreeMap<>();
        try (Driver driver = driver(); Session session = driver.session()) {
            session.executeRead(tx -> tx.run(
                    "MATCH (n) WHERE " + WHERE + " RETURN labels(n) AS l, count(*) AS c").list())
                    .forEach(r -> byLabel.merge(label(r.get("l").asList(org.neo4j.driver.Value::asString)), r.get("c").asLong(), Long::sum));
        }
        return byLabel;
    }

    /** Removes every node without a lineage (and its relationships); says how many of each label it removed. */
    @HistoryRead(span = HistorySpan.ALL_LINEAGES, use = HistoryUse.BINDING,
            note = "the operator's explicit cleanup from the home screen (T5-BX); never called by the loop",
            crossLineageBecause = "it matches only nodes WITHOUT a lineage, so no lineage's work is read, blocked or "
                    + "changed; the nodes it removes are linked to no stamped node (measured 2026-11-16)")
    public Map<String, Long> erase() {
        Map<String, Long> removed = new TreeMap<>();
        try (Driver driver = driver(); Session session = driver.session()) {
            while (true) {
                var batch = session.executeWrite(tx -> tx.run(
                        "MATCH (n) WHERE " + WHERE + " WITH n LIMIT 5000 WITH n, labels(n) AS l "
                                + "DETACH DELETE n RETURN l, count(*) AS c").list());
                long n = 0;
                for (var r : batch) {
                    long c = r.get("c").asLong();
                    removed.merge(label(r.get("l").asList(org.neo4j.driver.Value::asString)), c, Long::sum);
                    n += c;
                }
                if (n == 0) return removed;
            }
        }
    }

    private static String label(java.util.List<String> labels) {
        return labels.isEmpty() ? "(no label)" : String.join(":", labels);
    }

    private Driver driver() {
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }
}
