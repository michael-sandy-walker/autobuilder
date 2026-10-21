package com.walpro.autobuilder.core.merge.session;

import java.io.IOException;
import java.util.List;

/**
 * Where merge sessions are kept: an append-only log per session. Files first ({@link FileMergeSessionStore}); a graph
 * store (Neo4j, or files projected into it) is the later implementation the user keeps in view -- the log's nodes, edges
 * and typed edits map one to one onto it, so callers do not change.
 */
public interface MergeSessionStore {

    /** Starts an empty session; refuses an id already used. */
    void create(String sessionId) throws IOException;

    boolean exists(String sessionId);

    /** Appends {@code edits} in order. */
    void append(String sessionId, List<MergeSession.Edit> edits) throws IOException;

    /** The session's log, oldest first. */
    List<MergeSession.Edit> log(String sessionId) throws IOException;

    /** The session's current graph, for readers that do not replay (written after every append). */
    void publish(String sessionId, String graphJson) throws IOException;
}
