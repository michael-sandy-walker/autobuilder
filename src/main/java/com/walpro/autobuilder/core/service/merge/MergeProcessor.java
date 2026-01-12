package com.walpro.autobuilder.core.service.merge;

import com.walpro.autobuilder.agent.merge.MergeAgent;
import com.walpro.autobuilder.graph.merge.MergeGraphFacade;
import com.walpro.autobuilder.graph.merge.MergeGraphService;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.merge.model.MergeNode;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import com.walpro.autobuilder.agent.merge.registry.MergeLineageRegistry;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.util.HashingUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MergeProcessor {

    private final MergeAgent mergeAgent;
    private final MergeLineageRegistry lineageRegistry;
    private final MergeGraphFacade mergeGraphFacade;
    private final MergeGraphService mergeGraphService;

    public MergeResult process(String path, String baseContent, List<FileVariant> variants, boolean fileExistsInOutput, CycleContextData context) {
        if (variants.isEmpty()) {
            log.debug("No variants to merge for {}", path);
            return MergeResult.asSkipped();
        }

        if (!fileExistsInOutput && variants.size() == 1) {
            FileVariant only = variants.get(0);
            log.info("Promoting single new file: {}", path);
            return MergeResult.asPromoted(baseContent, only.content(), Set.of(only.originCycleId()));
        }

        log.debug("Merging {} — {} variant(s)", path, variants.size());
        MergeOutcome outcome = mergeAgent.merge(path, baseContent, variants, context);

        if (outcome.successful()) {
            Set<String> contributingCycles = new HashSet<>();
            for (FileVariant v : variants) {
                contributingCycles.add(v.originCycleId());
            }

            log.info("Merged file: {}", path);

            // Lineage tracking
            String hash = HashingUtils.hashSha256(outcome.mergedContent());
            MergeNode node = new MergeNode(path, context.cycleName(), contributingCycles, hash);
            lineageRegistry.register(node);
            mergeGraphFacade.recordMerge(node);
            mergeGraphService.save(node);

            return MergeResult.asMerged(baseContent, outcome.mergedContent(), contributingCycles);
        }

        log.warn("Merge failed for {} — {}", path, outcome.reasoning());
        return MergeResult.asFailed();
    }
}
