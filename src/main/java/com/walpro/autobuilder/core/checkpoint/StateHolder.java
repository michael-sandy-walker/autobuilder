package com.walpro.autobuilder.core.checkpoint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * T4-DQ: declares how a bean's mutable state behaves across a checkpoint/rewind.
 *
 * <p><b>The registration contract.</b> Any singleton bean that carries mutable instance state
 * must carry this annotation. {@code StateHolderRegistrationContractTest} enumerates them by
 * reflection and fails the build for one that does not — so the state inventory cannot silently
 * fall behind the code, which is exactly how the trackers came to be unpersisted in the first
 * place.
 *
 * <p>{@link #note()} is required and must be non-blank: the bucket alone records the decision but
 * not the reason, and the reason is what a later reader needs in order to change it correctly.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface StateHolder {

    /** Which of the three dispositions this holder's state has. */
    StateBucket value();

    /** Why — where the durable home is, or what makes the state derived. Must be non-blank. */
    String note();
}
