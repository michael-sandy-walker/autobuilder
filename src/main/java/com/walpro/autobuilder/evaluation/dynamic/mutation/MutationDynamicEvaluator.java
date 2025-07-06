//package com.walpro.autobuilder.evaluation.dynamic.mutation;
//
//import com.walpro.autobuilder.evaluation.MutationEvaluator;
//import com.walpro.autobuilder.evaluation.dynamic.DynamicEvaluator;
//import com.walpro.autobuilder.model.EvaluationInput;
//
//public class MutationDynamicEvaluator implements DynamicEvaluator {
//
//    private final MutationEvaluator delegate;
//    private boolean active = true;
//
//    public MutationDynamicEvaluator(MutationEvaluator delegate) {
//        this.delegate = delegate;
//    }
//
//    @Override
//    public String getId() {
//        return "mutation-evaluator";
//    }
//
//    @Override
//    public boolean isActive() {
//        return active;
//    }
//
//    @Override
//    public void activate() {
//        active = true;
//    }
//
//    @Override
//    public void deactivate() {
//        active = false;
//    }
//
//    @Override
//    public EvaluationOutcome evaluate(EvaluationInput input) {
//        return delegate.evaluate(
//            input.score(),
//            input.lastScore(),
//            input.updatedFiles(),
//            input.deletedFiles(),
//            input.currentGoal(),
//            input.projectState(),
//            input.findings()
//        );
//    }
//}
