package com.walpro.autobuilder.agent.meta.graph.util;

public class AgentLineageUtil {

    public static String extendPath(String parentLineagePath, String newAgentName) {
        return parentLineagePath == null || parentLineagePath.isEmpty()
                ? newAgentName
                : parentLineagePath + "." + newAgentName;
    }
}
