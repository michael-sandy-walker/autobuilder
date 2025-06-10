package com.walpro.autobuilder.analyzer;

import java.nio.file.Path;

public interface ProjectStructureSummarizer {
    String summarizeProjectStructure(Path rootDirectory);
}
