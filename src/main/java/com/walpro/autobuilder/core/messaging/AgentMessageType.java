package com.walpro.autobuilder.core.messaging;

public enum AgentMessageType {
    INSIGHT,        // Evaluation → Planner/Evolution ("here is what I learned")
    REQUEST,        // X → Y ("please give me information / do something")
    RESPONSE,       // Y → X ("here is what you asked for")
    WARNING,        // Evaluation/Meta → any ("something is off, pay attention")
    RECOMMENDATION  // Evaluation/Meta → Planner/Evolution ("you should consider ...")
}
