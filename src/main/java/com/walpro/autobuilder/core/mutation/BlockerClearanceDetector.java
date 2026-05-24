package com.walpro.autobuilder.core.mutation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * T4-BE: post-merge hook. Walks the newly-merged Java sources, derives blocker fingerprints
 * for every top-level type, constructor and method, and asks the {@link DeferredMutationLedger}
 * to drop any matching {@code BLOCKED_BY} edges. Deferred mutations whose open-blocker count
 * drops to zero are flipped to {@code READY_TO_RESTAGE} by the ledger and returned for the
 * caller's benefit (snapshot rendering, behaviour-log enrichment).
 *
 * <p>Lives in the merge code path's COMPILE_OK branch — only fires after the post-merge build
 * gate has confirmed the merge is sound. Non-Java files are skipped (no symbol semantics).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockerClearanceDetector {

    private final DeferredMutationLedger ledger;
    private final FileService fileService;

    /**
     * @param mergedInCycleId the cycle id of the merge that just completed
     * @param mergedPaths     relative paths (under {@code outputPath}) the merge wrote
     * @param outputPath      project output root (relative to {@code file.base-path})
     * @return list of deferred mutations whose state flipped to READY_TO_RESTAGE this call
     */
    public List<DeferredMutation> detectAndClear(String mergedInCycleId,
                                                  List<String> mergedPaths,
                                                  String outputPath) {
        if (mergedPaths == null || mergedPaths.isEmpty()) return List.of();
        Set<String> fingerprints = new HashSet<>();
        for (String path : mergedPaths) {
            if (path == null || !path.endsWith(".java")) continue;
            String content = readJavaSafely(outputPath + "/" + path);
            if (content == null) continue;
            collectFingerprintsFromJava(path, content, fingerprints);
        }
        if (fingerprints.isEmpty()) return List.of();

        return ledger.recordBlockerClearance(mergedInCycleId, fingerprints);
    }

    private String readJavaSafely(String relativePath) {
        try {
            if (!fileService.exists(relativePath)) return null;
            return fileService.readString(relativePath);
        } catch (Exception e) {
            log.warn("T4-BE: clearance read failed for {}: {}", relativePath, e.getMessage());
            return null;
        }
    }

    private void collectFingerprintsFromJava(String path, String content, Set<String> sink) {
        try {
            CompilationUnit cu = new JavaParser().parse(content).getResult().orElse(null);
            if (cu == null) return;
            for (TypeDeclaration<?> type : cu.getTypes()) {
                String typeName = type.getNameAsString();
                // The type itself now exists — clears any MISSING_TYPE blocker by simple name.
                sink.add(MutationBlocker.missingType(typeName).fingerprint());
                for (BodyDeclaration<?> member : type.getMembers()) {
                    if (member instanceof MethodDeclaration md) {
                        String sig = paramTypesAsString(md.getParameters());
                        sink.add(MutationBlocker
                                .missingMember(typeName, md.getNameAsString(), sig)
                                .fingerprint());
                    } else if (member instanceof ConstructorDeclaration cd) {
                        String sig = paramTypesAsString(cd.getParameters());
                        sink.add(MutationBlocker
                                .missingMember(typeName, typeName, sig)
                                .fingerprint());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("T4-BE: clearance parse failed for {}: {}", path, e.getMessage());
        }
    }

    private static String paramTypesAsString(List<Parameter> params) {
        if (params == null || params.isEmpty()) return "";
        String raw = params.stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.joining(","));
        return MutationBlocker.normaliseSignature(raw);
    }
}
