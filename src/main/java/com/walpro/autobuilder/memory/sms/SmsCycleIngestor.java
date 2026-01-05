package com.walpro.autobuilder.memory.sms;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.memory.ingest.GraphWriter;
import com.walpro.memory.model.Artifact;
import com.walpro.memory.model.ElementRef;
import com.walpro.memory.model.Episode;
import com.walpro.memory.model.Interpretation;
import com.walpro.memory.model.RelationTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SmsCycleIngestor {

    private final GraphWriter graphWriter;
    private final Neo4jClient neo4jClient;

    /**
     * Step 1: emit pure SMS Episode + Artifacts + Interpretations.
     * Observations only. No acceptance/rejection. No merge semantics.
     */
    public void ingestCycle(CycleContextData context) {
        if (context == null) return;

        String cycleId = context.cycleName();
        String trace = "autobuilder-" + cycleId;

        String episodeId;
        try (GraphWriter.Batch b = graphWriter.batch(trace)) {
            Episode ep = b.episodeNow();
            episodeId = ep.id();

            // ---- Artifacts (refs only; no blobs) ----
            List<Artifact> artifacts = new ArrayList<>();

            // Example refs (adapt to your actual fields/paths in CycleContextData)
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
            b.commit();
        }

        neo4jClient.query("""
            MATCH (e:Episode { id: $episodeId })
            MERGE (c:Cycle { id: $cycleId })
            MERGE (e)-[:ABOUT_CYCLE]->(c)
        """)
                .bindAll(Map.of("episodeId", episodeId, "cycleId", cycleId))
                .run();

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
