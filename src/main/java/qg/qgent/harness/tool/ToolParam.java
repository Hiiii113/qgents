package qg.qgent.harness.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具参数在 JSON Schema 中的描述与是否必填。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    boolean required() default true;

    String description() default "";
}
