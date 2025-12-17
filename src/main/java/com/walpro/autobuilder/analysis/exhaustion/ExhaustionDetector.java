package com.walpro.autobuilder.analysis.exhaustion;

public interface ExhaustionDetector {
    ExhaustionReport detect(ExhaustionInput input);
}