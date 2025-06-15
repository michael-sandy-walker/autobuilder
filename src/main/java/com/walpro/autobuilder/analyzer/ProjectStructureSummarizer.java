package com.walpro.autobuilder.analyzer;

import java.io.IOException;
import java.nio.file.Path;

public interface ProjectStructureSummarizer {
    String summarizeProjectStructure(Path rootDirectory) throws IOException;
}
