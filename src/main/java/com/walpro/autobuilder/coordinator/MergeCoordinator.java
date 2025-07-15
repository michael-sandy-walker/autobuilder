package com.walpro.autobuilder.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.FileLineage;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.persistence.CycleContext;
import com.walpro.autobuilder.persistence.MutationSnapshotService;
import com.walpro.autobuilder.service.FileService;
import com.walpro.autobuilder.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.service.merge.FileVariantCollector;
import com.walpro.autobuilder.service.merge.MergeProcessor;
import com.walpro.autobuilder.service.merge.MergeResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class MergeCoordinator {

	private final FileService fileService;
	private final ObjectMapper objectMapper;
	private final MutationSnapshotService mutationSnapshotService;
	private final FileVariantCollector fileVariantCollector;
	private final MergeProcessor mergeProcessor;
	private final CycleMergeTracker cycleMergeTracker;

	public CycleContextData mergeCompletedBranch(CycleContextData context) {
		int totalMerged = 0;
		int totalSkipped = 0;
		Map<String, List<String>> fileLineage = new HashMap<>();

		try {
			Path lineageFile = fileService.resolveSecurePath(context.outputPath() + "/lineage.json");
			if (fileService.existsResolved(lineageFile)) {
				try {
					FileLineage existing = objectMapper.readValue(lineageFile.toFile(), FileLineage.class);
					fileLineage.putAll(existing.lineage());
				} catch (IOException e) {
					log.warn("⚠️ Could not read existing lineage file, starting fresh.");
				}
			}

			Map<String, String> outputFiles = fileService.readFolderContents(context.outputPath());
			Set<String> alreadyMerged = mutationSnapshotService.listMergedMutations(context).stream()
					.map(CycleContext::getName)
					.collect(Collectors.toSet());

			Map<String, String> preMergeContent = new HashMap<>(outputFiles); // capture state before merge
			Map<String, String> postMergeContent = new HashMap<>();
			List<String> mergedCycles = new ArrayList<>();
			
			MergeTrace mergeTrace = new MergeTrace(mergedCycles, preMergeContent, postMergeContent);
			context = context.withMergeTrace(mergeTrace);

			Set<String> allPaths = collectAllPaths(outputFiles, alreadyMerged);

			for (String path : allPaths) {
				if (isSystemFile(path)) {
					log.debug("⏭️ Skipping system file: {}", path);
					continue;
				}

				String baseContent = outputFiles.getOrDefault(path, "");
				boolean existsInOutput = outputFiles.containsKey(path);

				List<FileVariant> variants = fileVariantCollector.collect(path, alreadyMerged);
				if (variants.isEmpty()) {
					totalSkipped++;
					continue;
				}

				MergeResult result = mergeProcessor.process(path, baseContent, variants, existsInOutput);

				if (result.wasSuccessful()) {
					fileService.writeFile("output/" + path, result.content());
					postMergeContent.put(path, result.content());
					totalMerged++;

					for (String contributor : result.contributingCycles()) {
						fileLineage.computeIfAbsent(path, k -> new ArrayList<>()).add(contributor);
					}

					mergedCycles.addAll(result.contributingCycles());

					cycleMergeTracker.track(context, path, result);
				} else if (result.skipped()) {
					totalSkipped++;
				} else {
					log.warn("⚠️ Merge failed for {}", path);
				}
			}
			
			log.info("🔀 Merge summary for {}: {} merged/promoted, {} skipped",
					context.cycleName(), totalMerged, totalSkipped);

		} catch (IOException e) {
			log.error("❌ Merge failed due to I/O error", e);
		}

		return context;
	}

	private Set<String> collectAllPaths(Map<String, String> outputFiles, Set<String> alreadyMerged) throws IOException {
		Set<String> paths = new HashSet<>(outputFiles.keySet());

		for (Path mutationDir : fileService.listSubfolders("mutations")) {
			String cycleId = mutationDir.getFileName().toString();
			if (alreadyMerged.contains(cycleId)) continue;

			paths.addAll(fileService.listAllRelativeFilePathsUnder("mutations/" + cycleId));
		}

		return paths;
	}

	private boolean isSystemFile(String path) {
		return path.endsWith("meta.json") || path.contains(".DS_Store") || path.endsWith(".log");
	}
}
