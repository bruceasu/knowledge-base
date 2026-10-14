package me.asu.http;

import java.io.IOException;

/**
 * A {@code ContextHandler} serves the content of resources within a context.
 *
 * @see HTTPServer#addContext
 */
public interface ContextHandler {

    /**
     * 使用给定的请求和响应来处理请求。
     *
     * @param req  要处理的请求
     * @param resp 要填充的响应
     * @return 一个HTTP状态码，将用于返回一个适合该状态的默认响应。
     * 如果该方法调用已经在响应中发送了任何内容（无论是头部还是内容），
     * 则必须返回0，并且不会进行进一步处理。
     * @throws IOException 如果发生输入输出错误
     */
    int serve(Request req, Response resp) throws IOException;
}