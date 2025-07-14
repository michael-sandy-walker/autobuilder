package com.walpro.autobuilder.analyzer;

import java.io.IOException;

public interface ProjectStructureSummarizer {
    String summarizeProjectStructure(String rootDirectory) throws IOException;
}
