package com.walpro.autobuilder.analyzer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.walpro.autobuilder.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultProjectStructureSummarizer implements ProjectStructureSummarizer {
	
	private final FileService fileService;

	public String summarizeProjectStructure(Path rootDir) throws IOException {
		if (!fileService.isDirectory(rootDir)) {
			return "[Error] Not a directory: " + rootDir;
		}

		List<String> summaries = new ArrayList<>();

		try {
			fileService.walk(rootDir)
			.filter(p -> {
				try {
					return fileService.isRegularFile(p) && p.toString().endsWith(".java");
				} catch (IOException e) {
					log.error("Error during processing isRegularFile for file {}", p);
					return false;
				}
			})
			.forEach(file -> {
				try {
					String relativePath = rootDir.relativize(file).toString();
					List<String> methodSignatures = extractMethodSignatures(file);
					summaries.add(relativePath + methodSignaturesToString(methodSignatures));
				} catch (IOException e) {
					summaries.add(file.toString() + " [Error reading file: " + e.getMessage() + "]");
				}
			});

			Map<String, List<String>> nonJavaFiles = collectNonJavaFilesByType(rootDir);
			if (!nonJavaFiles.isEmpty()) {
				summaries.add("Non-Java project files:");
				for (Map.Entry<String, List<String>> entry : nonJavaFiles.entrySet()) {
					summaries.add("  ." + entry.getKey() + " files:");
					for (String path : entry.getValue()) {
						summaries.add("    - " + path);
					}
				}
			}

		} catch (IOException e) {
			return "[Error walking directory: " + e.getMessage() + "]";
		}

		return String.join("\n\n", summaries);
	}

	private List<String> extractMethodSignatures(Path file) throws IOException {
		List<String> lines = fileService.readAllLines(file);
		return lines.stream()
				.map(String::trim)
				.filter(line -> line.matches("(public|protected|private)?\\s+(static\\s+)?[\\p{L}\\p{N}_<>\\[\\],\\s]+\\s+\\w+\\s*\\(.*\\)\\s*(\\{|;)?"))
				.filter(line -> !line.startsWith("//"))
				.collect(Collectors.toList());
	}

	private String methodSignaturesToString(List<String> methods) {
		if (methods.isEmpty()) return "";
		return methods.stream()
				.map(m -> "\n    - " + m)
				.collect(Collectors.joining());
	}

	private Map<String, List<String>> collectNonJavaFilesByType(Path rootDir) throws IOException {
		Map<String, List<String>> grouped = new TreeMap<>();
		fileService.walk(rootDir)
		.filter(p -> {
			try {
				return fileService.isRegularFile(p);
			} catch (IOException e) {
				log.error("Error during processing isRegularFile for file {}", p);
				return false;
			}
		})
		.filter(p -> !p.toString().endsWith(".java"))
		.forEach(p -> {
			String ext = getExtension(p.getFileName().toString());
			String relPath = rootDir.relativize(p).toString();
			grouped.computeIfAbsent(ext, k -> new ArrayList<>()).add(relPath);
		});
		return grouped;
	}

	private String getExtension(String filename) {
		int i = filename.lastIndexOf('.');
		return (i > 0 && i < filename.length() - 1) ? filename.substring(i + 1).toLowerCase() : "no_ext";
	}
}
