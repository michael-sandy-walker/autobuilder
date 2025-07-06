package com.walpro.autobuilder.model;

import java.util.List;
import java.util.Map;

public record FileLineage(Map<String, List<String>> lineage) {
}