package me.asu.http;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The {@code MethodContextHandler} services a context
 * by invoking a handler method on a specified object.
 * <p>
 * The method must have the same signature and contract as
 * {@link ContextHandler#serve}, but can have an arbitrary name.
 *
 */
public class MethodContextHandler implements ContextHandler {

    protected final Method m;
    protected final Object obj;

    public MethodContextHandler(Method m, Object obj) throws IllegalArgumentException {
        this.m = m;
        this.obj = obj;
        Class<?>[] params = m.getParameterTypes();
        if (params.length != 2
                || !Request.class.isAssignableFrom(params[0])
                || !Response.class.isAssignableFrom(params[1])
                || !int.class.isAssignableFrom(m.getReturnType()))
            throw new IllegalArgumentException("invalid method signature: " + m);
    }

    @Override
    public int serve(Request req, Response resp) throws IOException {
        try {
            return (Integer) m.invoke(obj, req, resp);
        } catch (InvocationTargetException ite) {
            throw new IOException(ite.getCause());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}