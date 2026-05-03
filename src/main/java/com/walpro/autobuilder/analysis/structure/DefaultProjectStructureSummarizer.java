package com.walpro.autobuilder.analysis.structure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.walpro.autobuilder.core.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultProjectStructureSummarizer implements ProjectStructureSummarizer {

    // T2-BX: annotation-aware structural summary for in-memory file content
    private static final Pattern RELEVANT_ANNOTATION = Pattern.compile(
            "@(ExceptionHandler|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)" +
            "(\\([^)\\n]*\\))?");

    // public [static] [<T>] ReturnType<Generic> methodName( — method name must start lowercase (excludes constructors)
    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "\\bpublic\\s+(?:static\\s+)?(?:final\\s+)?" +
            "(?:<[^>]+>\\s+)?" +
            "([\\w$]+(?:<[^>()]+>)?(?:\\[\\])?)" +
            "\\s+([a-z][\\w$]*)\\s*\\(");

	private final FileService fileService;
	
	public String summarizeProjectStructure(String relativeDir) throws IOException {
		Path rootPath = fileService.resolveSecurePath(relativeDir);
		return summarizeProjectStructureResolved(rootPath);
	}

	public String summarizeProjectStructureResolved(Path rootPath) throws IOException {
		if (!fileService.isDirectoryResolved(rootPath)) {
			return "[Error] Not a directory: " + rootPath;
		}

		List<String> summaries = new ArrayList<>();

		try {
			fileService.walkResolved(rootPath)
			.filter(p -> {
				try {
					return fileService.isRegularFileResolved(p) && p.toString().endsWith(".java");
				} catch (IOException e) {
					log.error("Error during processing summarizeProjectStructureResolved -> isRegularFile for file {}", p);
					return false;
				}
			})
			.forEach(file -> {
				try {
					String relativePath = rootPath.relativize(file).toString();
					List<String> methodSignatures = extractMethodSignaturesResolved(file);
					summaries.add(relativePath + methodSignaturesToString(methodSignatures));
				} catch (IOException e) {
					summaries.add(file + " [Error reading file: " + e.getMessage() + "]");
				}
			});

			// T4-AN-3 adjacent: always emit the section so absence of root files (e.g. pom.xml)
			// is observable rather than silently hidden — the LLM can no longer assume "the
			// project must have a pom.xml because it's a Spring Boot codebase".
			Map<String, List<String>> nonJavaFiles = collectNonJavaFilesByType(rootPath);
			summaries.add("Non-Java project files:");
			if (nonJavaFiles.isEmpty()) {
				summaries.add("  (none in project tree)");
			} else {
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

    /**
     * T2-BX: annotation-aware structural summary of in-memory Java file content.
     * Returns one formatted line per public method:
     * - Methods preceded by @ExceptionHandler / @*Mapping: "@Annotation(params) → methodName(...) : ReturnType"
     * - All other public methods: "methodName(...) : ReturnType"
     * Constructors are excluded (method name must start lowercase).
     * Duplicate method names are deduplicated (first occurrence wins).
     */
    public static List<String> summarizeContent(String content) {
        if (content == null || content.isBlank()) return List.of();

        List<String> declarations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] lines = content.split("\\n");
        String pendingAnnotation = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher annMatcher = RELEVANT_ANNOTATION.matcher(trimmed);
            if (annMatcher.find()) {
                String params = annMatcher.group(2);
                pendingAnnotation = "@" + annMatcher.group(1) + (params != null ? params : "");
                continue;
            }

            Matcher methodMatcher = PUBLIC_METHOD.matcher(trimmed);
            if (methodMatcher.find()) {
                String returnType = methodMatcher.group(1).trim();
                String methodName = methodMatcher.group(2);
                if (seen.add(methodName)) {
                    if (pendingAnnotation != null) {
                        declarations.add("  " + pendingAnnotation + " → " + methodName + "(...) : " + returnType);
                    } else {
                        declarations.add("  " + methodName + "(...) : " + returnType);
                    }
                }
                pendingAnnotation = null;
                continue;
            }

            // Non-annotation, non-blank line resets the pending annotation
            if (!trimmed.startsWith("@") && !trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                pendingAnnotation = null;
            }
        }

        return declarations;
    }

	private List<String> extractMethodSignaturesResolved(Path file) throws IOException {
		List<String> lines = fileService.readAllLines(file.toString());
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
		fileService.walk(rootDir.toString())
		.filter(p -> {
			try {
				return fileService.isRegularFile(p.toString());
			} catch (IOException e) {
				log.error("Error during processing collectNonJavaFilesByType -> isRegularFile for file {}", p);
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

	/**
	 * Scans all .java files under rootPath and returns the common base package prefix.
	 * Returns null if no Java files exist (first-evolution case — no constraint yet).
	 */
	public String detectBasePackage(Path rootPath) {
		if (!rootPath.toFile().isDirectory()) return null;
		List<String> packages = new ArrayList<>();
		try {
			fileService.walkResolved(rootPath)
				.filter(p -> {
					try { return fileService.isRegularFileResolved(p) && p.toString().endsWith(".java"); }
					catch (IOException e) { return false; }
				})
				.forEach(file -> {
					try {
						fileService.readAllLines(file.toString()).stream()
							.map(String::trim)
							.filter(l -> l.startsWith("package ") && l.endsWith(";"))
							.findFirst()
							.map(l -> l.substring("package ".length(), l.length() - 1).trim())
							.filter(p -> !p.isBlank())
							.ifPresent(packages::add);
					} catch (IOException e) {
						log.debug("Could not read package declaration from {}", file);
					}
				});
		} catch (IOException e) {
			log.warn("Error walking output dir for base package detection", e);
			return null;
		}
		if (packages.isEmpty()) return null;
		return commonPackagePrefix(packages);
	}

	private String commonPackagePrefix(List<String> packages) {
		String[] parts = packages.get(0).split("\\.");
		int commonLen = parts.length;
		for (String pkg : packages) {
			String[] p = pkg.split("\\.");
			int minLen = Math.min(commonLen, p.length);
			int match = 0;
			for (int i = 0; i < minLen; i++) {
				if (parts[i].equals(p[i])) match++;
				else break;
			}
			commonLen = match;
		}
		if (commonLen == 0) return null;
		return String.join(".", Arrays.copyOf(parts, commonLen));
	}
}
