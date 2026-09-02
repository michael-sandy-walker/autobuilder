package com.walpro.autobuilder.memory.sms;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.memory.ingest.GraphWriter;
import com.walpro.memory.model.Artifact;
import com.walpro.memory.model.ElementRef;
import com.walpro.memory.model.Episode;
import com.walpro.memory.model.Interpretation;
import com.walpro.memory.model.RelationTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "a dropped-ingestion counter")
public class SmsCycleIngestor {

    private final GraphWriter graphWriter;
    private final Neo4jClient neo4jClient;
    // T4-CJ slice 2: a Cycle created by this link must carry its lineage too.
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentity;

    /**
     * T6-J: optional cost tracking. This method runs synchronously inside every cycle and, measured
     * on 2026-07-30, accounted for ~120s of a 155s cycle — roughly 78% of the loop's wall clock,
     * and the whole of the "70 of 89 minutes unaccounted" finding. Adding indexes on the labels it
     * writes took it to ~95s, so the remainder is inside this method and had to be split to be
     * found. Four phases are timed separately rather than one total, because a single figure would
     * only reproduce what was already known.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.build.BuildCostTracker buildCostTracker;

    /**
     * T6-J: one thread, so ingestions stay in cycle order and the substrate sees the same
     * concurrency it always did — this change is about WHEN the write happens, not how many run at
     * once. The queue is small on purpose: it is slack for a burst, not a buffer for a backlog,
     * and overflowing it is information worth having rather than memory worth spending.
     */
    private static final int INGEST_QUEUE_CAPACITY = 16;

    @org.springframework.beans.factory.annotation.Value("${autobuilder.memory.sms.async:true}")
    @lombok.Setter
    private boolean async = true;

    private final java.util.concurrent.atomic.AtomicLong droppedIngestions =
            new java.util.concurrent.atomic.AtomicLong();

    private java.util.concurrent.ThreadPoolExecutor ingestExecutor;

    @jakarta.annotation.PostConstruct
    void startIngestThread() {
        if (!async) return;
        ingestExecutor = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(INGEST_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "sms-ingest");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Drains briefly rather than either blocking a shutdown indefinitely or discarding silently.
     * Whatever is still queued is reported, because "observations were lost" is a fact a run's
     * record should carry.
     */
    @jakarta.annotation.PreDestroy
    void stopIngestThread() {
        if (ingestExecutor == null) return;
        int queued = ingestExecutor.getQueue().size();
        ingestExecutor.shutdown();
        try {
            if (!ingestExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                List<Runnable> abandoned = ingestExecutor.shutdownNow();
                log.warn("T6-J: shutdown with {} memory ingestion(s) unwritten (queued at stop: {})",
                        abandoned.size(), queued);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long dropped = droppedIngestions.get();
        if (dropped > 0) {
            log.warn("T6-J: {} memory ingestion(s) dropped this session on a full queue", dropped);
        }
    }

    /** T6-J: how many ingestions were refused on a full queue this session. */
    long droppedCount() {
        return droppedIngestions.get();
    }

    private void phase(String name, long startNanos) {
        if (buildCostTracker != null) {
            buildCostTracker.record(null, "sms-" + name, (System.nanoTime() - startNanos) / 1_000_000L);
        }
    }

    /**
     * Step 1: emit pure SMS Episode + Artifacts + Interpretations.
     * Observations only. No acceptance/rejection. No merge semantics.
     */
    /**
     * T6-J: hands the write to a background thread instead of doing it inside the cycle.
     *
     * <p><b>Why.</b> Measured 2026-07-30/31, the body of this method cost <b>92.9 / 94.8 / 93.5 /
     * 93.5 / 121.3 / 97.3 / 94.2 / 92.1 seconds</b> per cycle — ~78% of the loop's entire wall
     * clock, and essentially all of it inside {@code GraphWriter.Batch.commit()} in the
     * memory-substrate dependency. The loop was getting 35 cycles per 90 minutes where an earlier
     * run against a smaller graph got 62. Nothing in a cycle READS this memory back, so it has no
     * business deciding how many cycles the loop gets.
     *
     * <p><b>What this trades away, stated plainly:</b> observations are no longer durable at the
     * moment the cycle ends. A crash loses whatever is still queued, and at ~94s per write against
     * faster cycles the queue is expected to hold work. That is the point of the change and the
     * cost of it.
     *
     * <p><b>Losses are counted, never silent.</b> The queue is bounded; when it is full the
     * ingestion is dropped, logged at WARN and counted, and the count is reported at shutdown. An
     * unbounded queue would trade a throughput problem for a memory one and hide the fact that the
     * substrate cannot keep up.
     */
    public void ingestCycle(CycleContextData context) {
        if (context == null) return;
        if (!async || ingestExecutor == null) {
            ingestCycleBlocking(context);
            return;
        }
        try {
            ingestExecutor.execute(() -> {
                try {
                    ingestCycleBlocking(context);
                } catch (RuntimeException e) {
                    // A background failure must not vanish: the cycle that produced it has long
                    // since returned and nothing else will report this.
                    log.warn("T6-J: background memory ingest failed for {} — {}",
                            context.cycleName(), e.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            long n = droppedIngestions.incrementAndGet();
            log.warn("T6-J: memory ingest queue full — dropped observations for {} (dropped so far: {}). "
                    + "The substrate is not keeping up with the loop.", context.cycleName(), n);
        }
    }

    void ingestCycleBlocking(CycleContextData context) {
        if (context == null) return;

        String cycleId = context.cycleName();
        String trace = "autobuilder-" + cycleId;

        String episodeId;
        long tBatch = System.nanoTime();
        try (GraphWriter.Batch b = graphWriter.batch(trace)) {
            Episode ep = b.episodeNow();
            episodeId = ep.id();

            // ---- Artifacts (refs only; no blobs) ----
            List<Artifact> artifacts = new ArrayList<>();

            // Example refs (adapt to actual fields/paths in CycleContextData)
            addArtifactIfPresent(b, artifacts, "OUTPUT_PATH", safeString(context.outputPath()));
            addArtifactIfPresent(b, artifacts, "MUTATION_PATH", safeString(context.mutationPath()));
            addArtifactIfPresent(b, artifacts, "SNAPSHOT_PATH", safeString(context.snapshotPath()));
            addArtifactIfPresent(b, artifacts, "MERGED_PATH", safeString(context.mergedPath()));
            addArtifactIfPresent(b, artifacts, "EVOLUTION_FINGERPRINT", safeString(context.lastEvolutionFingerprint()));
            addArtifactIfPresent(b, artifacts, "RECOMBINATION_FINGERPRINT", safeString(context.lastRecombinationFingerprint()));


            // If we have a stable compact string for the diff result, store the summary:
            ProjectDiffResult diff = context.projectDiff();
            if (diff != null) {
                addArtifactIfPresent(b, artifacts, "PROJECT_DIFF_SUMMARY", diff.summary());
            }

            // Link Episode -> Artifact via HAS_ARTIFACT (matches Phase2/Phase3 style)
            for (Artifact a : artifacts) {
                b.relation(
                        RelationTypes.HAS_ARTIFACT,
                        List.of(ElementRef.episode(ep.id()), ElementRef.artifact(a.id()))
                );
            }

            // ---- Interpretations (observations only) ----
            // Keep type strings simple, like the demos.
            // Use confidence, but do not turn it into acceptance logic.
            List<Interpretation> observations = new ArrayList<>();

            observations.add(b.interpretation(
                    "OBSERVATION",
                    "Cycle produced " + artifacts.size() + " recorded artifact reference(s).",
                    0.70
            ));

            // Examples — keep these purely descriptive
            if (isNoStructuralChange(diff)) {
                observations.add(b.interpretation(
                        "OBSERVATION",
                        "Mutation produced no structural change.",
                        0.85
                ));
            }
            if (context.plannerOutcome() == null) {
                observations.add(b.interpretation(
                        "OBSERVATION",
                        "Cycle ran without planner input.",
                        0.75
                ));
            }

            if (context.merged()) {
                observations.add(b.interpretation(
                        "OBSERVATION",
                        "A merge occurred during this cycle.",
                        0.70
                ));
            }

            // ABOUT relations should connect the interpretation to what it is about:
            // interpretation + episode (+ relevant artifacts if applicable), matching Phase1/Phase3 patterns. :contentReference[oaicite:3]{index=3}
            for (Interpretation i : observations) {
                List<ElementRef> links = new ArrayList<>();
                links.add(ElementRef.interpretation(i.id()));
                links.add(ElementRef.episode(ep.id()));

//                // Optionally include artifacts as extra anchors for explainability
//                for (Artifact a : artifacts) {
//                    links.add(ElementRef.artifact(a.id()));
//                }

                // Optional: anchor to diff summary artifact if present
                artifacts.stream()
                        .filter(a -> "PROJECT_DIFF_SUMMARY".equals(a.kind()))
                        .findFirst()
                        .ifPresent(a -> links.add(ElementRef.artifact(a.id())));

                b.relation(
                        RelationTypes.ABOUT,
                        links,
                        Map.of("role", "cycle-observation")
                );
            }

            // commit() triggers validateAllOrThrow via Batch close as well,
            // but commit() makes intent explicit.
            phase("batch-build", tBatch);
            long tCommit = System.nanoTime();
            b.commit();
            phase("commit", tCommit);
        }

        // T4-CJ slice 2: the Episode/Artifact/Interpretation/Relation nodes above are written by
        // the semantic-memory-substrate library, not by this repo, so they carry no lineage stamp
        // of their own — 787 of 2170 nodes in the 2026-07-26 verification run (36%). Now that a
        // reset KEEPS history (T1-D), an unattributable node cannot be told apart from a previous
        // lineage's, so stamp what this ingestion just wrote. Measured shape: everything reachable
        // within two hops of the episode (Episode -[LINKS_TO]-> Relation -> Artifact/Interpretation)
        // accounts for 100% of them. `WHERE x.lineageId IS NULL` makes this fill gaps only — it can
        // never re-stamp a node another lineage already owns.
        long tStamp = System.nanoTime();
        neo4jClient.query("""
            MATCH (e:Episode { id: $episodeId })
            SET e.lineageId = $lineageId, e.runId = $runId
            WITH e
            MATCH (e)-[*1..2]-(x)
            WHERE x.lineageId IS NULL
            SET x.lineageId = $lineageId, x.runId = $runId
        """)
                .bind(episodeId).to("episodeId")
                .bind(runIdentity.lineageId()).to("lineageId")
                .bind(runIdentity.runId()).to("runId")
                .run();

        phase("lineage-stamp", tStamp);
        long tCycleMerge = System.nanoTime();
        neo4jClient.query("""
            MATCH (e:Episode { id: $episodeId })
            MERGE (c:Cycle { id: $cycleId })
                ON CREATE SET c.lineageId = $lineageId, c.runId = $runId
            MERGE (e)-[:ABOUT_CYCLE]->(c)
        """)
                .bindAll(Map.of("episodeId", episodeId, "cycleId", cycleId))
                .bind(runIdentity.lineageId()).to("lineageId")
                .bind(runIdentity.runId()).to("runId")
                .run();
        phase("cycle-merge", tCycleMerge);

        long tLink = System.nanoTime();
        if (context.parentCycleName() != null) {
            neo4jClient.query("""
        MATCH (prev:Cycle {id: $prev})
        MATCH (cur:Cycle {id: $cur})
        MERGE (prev)-[:NEXT]->(cur)
        """)
                        .bindAll(Map.of(
                                "prev", context.parentCycleName(),
                                "cur", context.cycleName()
                        ))
                        .run();
            }

        if (context.previousAcceptedCycleName() != null) {
            neo4jClient.query("""
        MATCH (prev:Cycle {id: $prev})
        MATCH (cur:Cycle {id: $cur})
        MERGE (prev)-[:NEXT_ACCEPTED]->(cur)
        """)
                    .bindAll(Map.of(
                            "prev", context.previousAcceptedCycleName(),
                            "cur", context.cycleName()
                    ))
                    .run();
        }
        phase("cycle-link", tLink);
    }

    private static void addArtifactIfPresent(GraphWriter.Batch b, List<Artifact> out, String kind, String ref) {
        if (ref == null || ref.isBlank()) return;
        out.add(b.artifact(kind, ref));
    }

    private static String safeString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean isNoStructuralChange(ProjectDiffResult diff) {
        if (diff == null) return true;
        boolean noChanged = diff.changedFiles() == null || diff.changedFiles().isEmpty();
        boolean noDeleted = diff.deletedPaths() == null || diff.deletedPaths().isEmpty();
        return noChanged && noDeleted;
    }
}
