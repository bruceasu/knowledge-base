package me.asu.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code Context} 注解用于装饰映射到服务器上下文（路径）的方法，并提供其内容。
 * <p>
 * 被注解的方法必须具有与 {@link ContextHandler#serve} 相同的签名和契约，但可以有任意名称。
 *
 * @see Application#addContexts(Object)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Context {

    /**
     * 此字段映射到的上下文（路径）（必须以'/'开头）。
     *
     * @return 此字段映射到的上下文（路径）
     */
    String value();

    /**
     * 此上下文处理器所支持的HTTP方法（默认为“GET”）。
     *
     * @return 此上下文处理器所支持的HTTP方法
     */
    String[] methods() default "GET";
}
