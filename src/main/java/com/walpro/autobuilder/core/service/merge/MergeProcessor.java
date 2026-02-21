package com.walpro.autobuilder.core.service.merge;

import com.walpro.autobuilder.agent.merge.MergeLLMAdapter;
import com.walpro.autobuilder.graph.merge.MergeGraphFacade;
import com.walpro.autobuilder.graph.merge.MergeGraphService;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.merge.model.MergeNode;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
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

    private final MergeLLMAdapter mergeAdapter;
    private final MergeGraphFacade mergeGraphFacade;
    private final MergeGraphService mergeGraphService;

    public MergeResult process(String path, String baseContent, List<FileVariant> variants, boolean fileExistsInOutput, CycleContextData context) {
        if (variants.isEmpty()) {
            log.debug("No variants to merge for {}", path);
            return MergeResult.asSkipped();
        }

        if (!fileExistsInOutput && variants.size() == 1) {
            FileVariant only = variants.get(0);
            log.info("Adopting single new file: {}", path);

            Set<String> contributingCycles = Set.of(only.originCycleId());
            String adoptedContent = only.content();
            String hash = HashingUtils.hashSha256(adoptedContent);

            MergeNode node = new MergeNode(path, context.cycleName(), contributingCycles, hash);
            mergeGraphFacade.recordMerge(node);
            mergeGraphService.save(node);

            return MergeResult.asAdopted(baseContent, adoptedContent, contributingCycles);
        }

        log.debug("Merging {} — {} variant(s)", path, variants.size());
        MergeOutcome outcome = mergeAdapter.merge(path, baseContent, variants, context);

        if (outcome.successful()) {
            Set<String> contributingCycles = new HashSet<>();
            for (FileVariant v : variants) {
                contributingCycles.add(v.originCycleId());
            }

            log.info("Merged file: {}", path);

            // Lineage tracking
            String hash = HashingUtils.hashSha256(outcome.mergedContent());
            MergeNode node = new MergeNode(path, context.cycleName(), contributingCycles, hash);
            mergeGraphFacade.recordMerge(node);
            mergeGraphService.save(node);

            return MergeResult.asMerged(baseContent, outcome.mergedContent(), contributingCycles);
        }

        log.warn("Merge failed for {} — {}", path, outcome.reasoning());
        return MergeResult.asFailed();
    }
}
