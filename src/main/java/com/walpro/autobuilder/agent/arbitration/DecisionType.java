package com.walpro.autobuilder.agent.arbitration;

public enum DecisionType {

    /** Plan is approved and may be executed */
    ACCEPT,

    /** Plan is rejected and must not be executed */
    REJECT,

    /** Plan is not wrong, but not now */
    DEFER,

    /** Plan requires modification before it can proceed */
    MODIFY
}
