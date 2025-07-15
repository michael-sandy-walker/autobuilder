package com.walpro.autobuilder.service.merge;

import com.walpro.autobuilder.agent.merge.MergeAgent;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.merge.model.MergeInput;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MergeProcessor {

    private final MergeAgent mergeAgent;

    public MergeResult process(String path, String baseContent, List<FileVariant> variants, boolean fileExistsInOutput) {
        if (variants.isEmpty()) {
            log.debug("⏭️ No variants to merge for {}", path);
            return MergeResult.asSkipped();
        }

        if (!fileExistsInOutput && variants.size() == 1) {
            FileVariant only = variants.get(0);
            log.info("✅ Promoting single new file: {}", path);
            return MergeResult.asPromoted(only.content(), Set.of(only.originCycleId()));
        }

        log.debug("🧪 Merging {} — {} variant(s)", path, variants.size());
        MergeInput input = new MergeInput(path, baseContent, variants);
        MergeOutcome outcome = mergeAgent.merge(input);

        if (outcome.successful()) {
            Set<String> contributingCycles = new HashSet<>();
            for (FileVariant v : variants) {
                contributingCycles.add(v.originCycleId());
            }
            log.info("✅ Merged file: {}", path);
            return MergeResult.asMerged(outcome.mergedContent(), contributingCycles);
        }

        log.warn("⚠️ Merge failed for {} — {}", path, outcome.reasoning());
        return MergeResult.asFailed();
    }
}
