package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.analysis.structure.Endpoint;
import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T, constraint-samples run 2026-10-20_18-04-44: an adopted library cut serving nothing, whose ticket quoted a past
 * field result naming an endpoint. The quote was taken for a required endpoint; root closure was refused at 27/27.
 * Whether the project serves HTTP is read from the tree as adopted.
 */
class AdoptedSurfaceTest {

    private static final String QUOTE = "Field DoD met 3/3 with the rule fixed in advance -- each run independently\n"
            + "produced \"POST /widgets -> 201, but the running application answered 500\".";

    private static EndpointSurfaceQuery adopt(Path root, String className, String source) throws Exception {
        Path main = root.resolve("output/src/main/java/org/quill");
        Files.createDirectories(main);
        Files.createDirectories(root.resolve("meta"));
        Files.writeString(main.resolve(className + ".java"), source);
        FileService files = new FileService(root.toString(), new ObjectMapper());
        AdoptionManifest manifest = new AdoptionManifest(files);
        manifest.recordIfAbsent();
        EndpointSurfaceQuery query = new EndpointSurfaceQuery();
        query.setAdoptionManifest(manifest);
        return query;
    }

    @Test
    void anAdoptedTreeServingNothing_namesNoEndpointFromItsSpecification(@TempDir Path root) throws Exception {
        EndpointSurfaceQuery query = adopt(root, "Inkwell", "package org.quill;\npublic class Inkwell { int drops() { return 3; } }\n");

        assertThat(Files.readString(root.resolve(AdoptionManifest.SERVED_PATH)).strip()).isEqualTo("[ ]");
        assertThat(query.declaredInSpec(QUOTE)).isEmpty();
        assertThat(query.specRequiresRunnableArtifact(QUOTE)).isFalse();
    }

    @Test
    void anAdoptedTreeThatServes_keepsTheEndpointsItsSpecificationNames(@TempDir Path root) throws Exception {
        EndpointSurfaceQuery query = adopt(root, "NibController", "package org.quill;\n"
                + "@RestController\npublic class NibController {\n  @GetMapping(\"/nibs\")\n  public String nibs() { return \"\"; }\n}\n");

        assertThat(query.declaredInSpec(QUOTE)).containsExactly(new Endpoint("POST", "/widgets"));
    }

    @Test
    void aProjectTheLoopWroteItself_keepsTheRule(@TempDir Path root) {
        EndpointSurfaceQuery query = new EndpointSurfaceQuery();
        query.setAdoptionManifest(new AdoptionManifest(new FileService(root.toString(), new ObjectMapper())));

        assertThat(query.declaredInSpec(QUOTE)).as("no adoption recorded: greenfield keeps its endpoints")
                .containsExactly(new Endpoint("POST", "/widgets"));
    }
}
