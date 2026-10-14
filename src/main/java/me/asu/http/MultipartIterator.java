package me.asu.http;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import static me.asu.http.Bytes.getBytes;
import static me.asu.http.HeaderKey.*;
import static me.asu.http.Headers.readHeaders;
import static me.asu.http.Strings.isEmpty;

/**
 * {@code MultipartIterator} 用于迭代 multipart/form-data 请求的各个部分。
 * <p>
 * 例如，为了支持从网络浏览器上传文件：
 * <ol>
 * <li>创建一个 HTML 表单，该表单包含一个类型为 “file” 的输入字段， 属性为 method="post" 和 enctype="multipart/form-data"，
 * 并指定一个您选择的操作 URL，
 * 例如 action="/upload"。该表单可以像其他任何资源一样正常提供，例如通过硬盘上的 HTML 文件。
 * <li>为操作路径（在本示例中为 "/upload"）添加上下文处理程序，
 * 可以使用显式的 {@link HTTPServer#addContext} 方法或 {@link Context} 注释进行添加。
 * </ol>
 */
public class MultipartIterator implements Iterator<Part> {


    protected final MultipartInputStream in;
    protected boolean next;

    /**
     * Creates a new MultipartIterator from the given request.
     *
     * @param req the multipart/form-data request
     * @throws IOException              if an IO error occurs
     * @throws IllegalArgumentException if the given request's content type
     *                                  is not multipart/form-data, or is missing the boundary
     */
    public MultipartIterator(Request req) throws IOException {
        Map<String, String> ct = req.getHeaders().getParams(CONTENT_TYPE);
        if (!ct.containsKey(MULTIPART_FORM_DATA))
            throw new IllegalArgumentException("Content-Type is not multipart/form-data");
        String boundary = ct.get(BOUNDARY); // should be US-ASCII
        if (boundary == null)
            throw new IllegalArgumentException("Content-Type is missing boundary");
        in = new MultipartInputStream(req.getBody(), getBytes(boundary));
    }

    @Override
    public boolean hasNext() {
        try {
            return next || (next = in.nextPart());
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    @Override
    public Part next() {
        if (!hasNext())
            throw new NoSuchElementException();
        next = false;
        Part p = new Part();
        try {
            p.headers = readHeaders(in);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        Map<String, String> cd = p.headers.getParams(CONTENT_DISPOSITION);
        p.name = cd.get("name");
        p.filename = cd.get("filename");
        p.body = in;
        if (!isEmpty(p.filename)) {
            p.type = Part.FILE;
        }
        return p;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}