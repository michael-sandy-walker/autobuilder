package com.walpro.autobuilder.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DefaultProjectStructureSummarizer implements ProjectStructureSummarizer {

	//	@Override
	//    public String summarizeProjectStructure(Path rootDir) {
	//        if (!Files.isDirectory(rootDir)) {
	//            return "[Error] Not a directory: " + rootDir;
	//        }
	//
	//        List<String> summaries = new ArrayList<>();
	//
	//        try {
	//            Files.walk(rootDir)
	//                 .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
	//                 .forEach(file -> {
	//                     try {
	//                         String relativePath = rootDir.relativize(file).toString();
	//                         List<String> methodSignatures = extractMethodSignatures(file);
	//                         summaries.add(relativePath + methodSignaturesToString(methodSignatures));
	//                     } catch (IOException e) {
	//                         summaries.add(file.toString() + " [Error reading file: " + e.getMessage() + "]");
	//                     }
	//                 });
	//        } catch (IOException e) {
	//            return "[Error walking directory: " + e.getMessage() + "]";
	//        }
	//
	//        return String.join("\n\n", summaries);
	//    }
	//
	//	private List<String> extractMethodSignatures(Path file) throws IOException {
	//        List<String> lines = Files.readAllLines(file);
	//        return lines.stream()
	//                .map(String::trim)
	//                .filter(line -> line.matches("(public|protected|private)?\\s+(static\\s+)?[\\w\\<\\>\\[\\]]+\\s+\\w+\\s*\\(.*\\)\\s*(\\{|;)?"))
	//                .filter(line -> !line.startsWith("//"))
	//                .collect(Collectors.toList());
	//    }
	//
	//    private String methodSignaturesToString(List<String> methods) {
	//        if (methods.isEmpty()) return "";
	//        return methods.stream()
	//                .map(m -> "\n    - " + m)
	//                .collect(Collectors.joining());
	//    }

	public String summarizeProjectStructure(Path rootDir) {
		if (!Files.isDirectory(rootDir)) {
			return "[Error] Not a directory: " + rootDir;
		}

		List<String> summaries = new ArrayList<>();

		try {
			Files.walk(rootDir)
			.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
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
		List<String> lines = Files.readAllLines(file);
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
		Files.walk(rootDir)
		.filter(p -> Files.isRegularFile(p))
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
