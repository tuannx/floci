package io.quarkus.runtime.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-only stub for the perf harness. The real annotation comes from
 * Quarkus at application runtime; the perf harness only needs repo model
 * classes to compile outside the Quarkus build. It has no runtime effect.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface RegisterForReflection {
    boolean registerFullHierarchy() default false;
    Class<?>[] targets() default {};
    String[] classNames() default {};
    boolean methods() default true;
    boolean fields() default true;
    boolean ignoreNested() default false;
    boolean serialization() default false;
}
