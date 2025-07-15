package com.walpro.autobuilder.agent.merge.model;

import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.SubgoalMetadata;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class MergeInputUtil {

    public static List<MergeInput> from(CycleContextData ctx) {
        Map<String, String> previous = ctx.previousOutput();

        List<FileMutation> mutations = ctx.evolutionOutcome().addedOrChangedFiles();

        return mutations.stream()
            .collect(Collectors.groupingBy(FileMutation::path))
            .entrySet()
            .stream()
            .map(entry -> {
                String path = entry.getKey();
                List<FileMutation> samePathMutations = entry.getValue();

                List<FileVariant> variants = samePathMutations.stream()
                    .map(m -> new FileVariant(
                        m.content(),
                        Set.of(SubgoalMetadata.of(ctx.currentSubGoalId(), ctx.currentSubGoalDescription())),
                        ctx.cycleName()
                    ))
                    .collect(Collectors.toList());

                return new MergeInput(
                    path,
                    previous.getOrDefault(path, ""),
                    variants
                );
            })
            .collect(Collectors.toList());
    }
}
