package com.walpro.autobuilder.analysis.structure;

import java.io.IOException;

public interface ProjectStructureSummarizer {
    String summarizeProjectStructure(String rootDirectory) throws IOException;
}
