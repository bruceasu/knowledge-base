package me.asu.http;

import lombok.Data;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static me.asu.http.HeaderKey.*;
import static me.asu.http.Bytes.CRLF;
import static me.asu.http.Bytes.getBytes;
import static me.asu.http.Streams.transfer;
import static me.asu.http.Strings.*;

/**
 * {@code Response} 类封装了单一的 HTTP 响应。
 */
@Data
public class Response implements Closeable {
    /** HTTP状态描述字符串。*/
    protected static final String[] statuses = new String[600];

    static {
        // initialize status descriptions lookup table
        Arrays.fill(statuses, "Unknown Status");
        statuses[100] = "Continue";
        statuses[101] = "Switching Protocols";
        statuses[102] = "Processing";                 // WebDAV
        statuses[103] = "Early Hints";                // RFC 8297

        statuses[200] = "OK";
        statuses[201] = "Created";
        statuses[202] = "Accepted";
        statuses[203] = "Non-Authoritative Information";
        statuses[204] = "No Content";
        statuses[205] = "Reset Content";
        statuses[206] = "Partial Content";
        statuses[207] = "Multi-Status";               // WebDAV
        statuses[208] = "Already Reported";           // WebDAV
        statuses[226] = "IM Used";                    // RFC 3229

        statuses[300] = "Multiple Choices";
        statuses[301] = "Moved Permanently";
        statuses[302] = "Found";
        statuses[303] = "See Other";
        statuses[304] = "Not Modified";
        statuses[305] = "Use Proxy";                  // Deprecated
        statuses[306] = "Switch Proxy";               // No longer used
        statuses[307] = "Temporary Redirect";
        statuses[308] = "Permanent Redirect";

        statuses[400] = "Bad Request";
        statuses[401] = "Unauthorized";
        statuses[402] = "Payment Required";           // Reserved
        statuses[403] = "Forbidden";
        statuses[404] = "Not Found";
        statuses[405] = "Method Not Allowed";
        statuses[406] = "Not Acceptable";
        statuses[407] = "Proxy Authentication Required";
        statuses[408] = "Request Timeout";
        statuses[409] = "Conflict";
        statuses[410] = "Gone";
        statuses[411] = "Length Required";
        statuses[412] = "Precondition Failed";
        statuses[413] = "Content Too Large";          // RFC 9110 (used to be Payload Too Large)
        statuses[414] = "URI Too Long";
        statuses[415] = "Unsupported Media Type";
        statuses[416] = "Range Not Satisfiable";
        statuses[417] = "Expectation Failed";
        statuses[418] = "I'm a teapot";               // RFC 2324 (April Fools)
        statuses[421] = "Misdirected Request";
        statuses[422] = "Unprocessable Content";      // WebDAV / RFC 9110
        statuses[423] = "Locked";                     // WebDAV
        statuses[424] = "Failed Dependency";          // WebDAV
        statuses[425] = "Too Early";                  // RFC 8470
        statuses[426] = "Upgrade Required";
        statuses[428] = "Precondition Required";      // RFC 6585
        statuses[429] = "Too Many Requests";          // RFC 6585
        statuses[431] = "Request Header Fields Too Large"; // RFC 6585
        statuses[451] = "Unavailable For Legal Reasons";   // RFC 7725

        statuses[500] = "Internal Server Error";
        statuses[501] = "Not Implemented";
        statuses[502] = "Bad Gateway";
        statuses[503] = "Service Unavailable";
        statuses[504] = "Gateway Timeout";
        statuses[505] = "HTTP Version Not Supported";
        statuses[506] = "Variant Also Negotiates";    // RFC 2295
        statuses[507] = "Insufficient Storage";       // WebDAV
        statuses[508] = "Loop Detected";              // WebDAV
        statuses[510] = "Not Extended";               // RFC 2774
        statuses[511] = "Network Authentication Required"; // RFC 6585

    }

   static HTTPServer.GzipConfig gzipConfig;

    protected OutputStream outputStream; // the underlying output stream
    protected OutputStream encodedOut;   // chained encoder streams
    protected Headers      headers;
    protected boolean      discardBody;
    protected int          state;       // nothing sent, headers sent, or closed
    protected Request      request;     // request used in determining client capabilities

    public Headers getHeaders() {
        return headers;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public void setDiscardBody(boolean discardBody) {
        this.discardBody = discardBody;
    }

    /**
     * Constructs a Response whose output is written to the given stream.
     *
     * @param outputStream the stream to which the response is written
     */
    public Response(OutputStream outputStream) {
        this.outputStream = outputStream;
        this.headers = new Headers();
    }

    /**
     * 设置用于确定客户端支持的能力的请求
     * （例如，压缩、编码等）
     *
     * @param req 请求
     */
    public void setClientCapabilities(Request req) {this.request = req;}

    /**
     * Returns whether the response headers were already sent.
     *
     * @return whether the response headers were already sent
     */
    public boolean headersSent() {return state == 1;}

    /**
     * 返回一个输出流，响应体可以写入该流中。
     * 该流根据发送的头部应用编码（例如，压缩）。
     * 此方法必须在指示存在响应体的响应头已发送后调用。
     * 通常情况下，内容应该在发送头部之前准备好（而不是发送），
     * 以便在处理过程中捕获任何错误，并返回适当的错误响应——
     * 在发送头部之后，改变状态为错误已为时已晚。
     *
     * @return 一个可以写入响应体的输出流，
     * 如果不应写入响应体（例如，它被丢弃），则返回 null。
     * @throws IOException 如果发生错误
     */
    public OutputStream getBody() throws IOException {
        if (encodedOut != null || discardBody)
            return encodedOut; // return the existing stream (or null)
        // set up chain of encoding streams according to headers
        List<String> te = Arrays.asList(splitElements(headers.get("Transfer-Encoding"), true));
        List<String> ce = Arrays.asList(splitElements(headers.get("Content-Encoding"), true));
        encodedOut = new ResponseOutputStream(outputStream); // leaves underlying stream open when closed
        if (te.contains("chunked"))
            encodedOut = new ChunkedOutputStream(encodedOut);
        if (ce.contains("gzip") || te.contains("gzip"))
            encodedOut = new GZIPOutputStream(encodedOut, 4096);
        else if (ce.contains("deflate") || te.contains("deflate"))
            encodedOut = new DeflaterOutputStream(encodedOut);
        return encodedOut; // return the outermost stream
    }

    /**
     * 关闭此响应并刷新所有输出。
     *
     * @throws IOException 如果发生错误
     */
    @Override
    public void close() throws IOException {
        state = -1; // closed
        if (encodedOut != null)
            encodedOut.close(); // close all chained streams (except the underlying one)
        outputStream.flush();   // always flush underlying stream (even if getBody was never called)
    }


    /**
     * 发送具有指定响应状态的响应头。
     * 如果尚不存在，则会添加一个日期头。
     * 如果响应包含正文，则必须在发送头之前设置内容长度/传输编码
     * 和内容类型头。
     *
     * @param status 响应状态
     * @throws IOException 如果发生错误或头信息已被发送
     * @see #sendHeaders(int, long, long, String, String, long[])
     */
    public void sendHeaders(int status) throws IOException {
        if (headersSent())
            throw new IOException("headers were already sent");
        if (!headers.contains("Date"))
            headers.add("Date", DateUtils.formatDate(System.currentTimeMillis()));
        outputStream.write(getBytes("HTTP/1.1 ", Integer.toString(status), " ", statuses[status]));
        outputStream.write(CRLF);
        headers.writeTo(outputStream);
        state = 1; // headers sent
    }

    /**
     * 发送响应头，包括所给定的响应状态及其描述，以及所有响应头。
     * 如果尚未存在，必要时将添加以下头信息：Content-Range、Content-Type、
     * Transfer-Encoding、Content-Encoding、Content-Length、Last-Modified、ETag、
     * Connection 和 Date。
     * 范围也将被准确计算，200 状态将更改为 206 状态。
     *
     * @param status       响应状态
     * @param length       响应体的长度，如果没有体则为零，或者如果有体但长度尚未确定则为负值
     * @param lastModified 响应资源的最后修改日期，如果未知则为非正值。将来时间将替换为当前系统时间。
     * @param etag         响应资源的 ETag，未知时为 null（参见 RFC9110#8.8.3）
     * @param contentType  响应资源的内容类型，如果未知则为 null（在这种情况下将发送 "application/octet-stream"）
     * @param range        将发送的内容范围，如果将发送整个资源则为 null
     * @throws IOException 如果发生错误
     */
    public void sendHeaders(int status, long length, long lastModified,
                            String etag, String contentType, long[] range) throws IOException {
        if (range != null) {
            headers.add(CONTENT_RANGES, "bytes " + range[0] + "-" +
                    range[1] + "/" + (length >= 0 ? length : "*"));
            length = range[1] - range[0] + 1;
            if (status == 200)
                status = 206;
        }
        String ct = headers.get(CONTENT_TYPE);
        if (ct == null) {
            ct = contentType != null ? contentType : CommonContentType.OCTET_STREAM.type();
            headers.add(CONTENT_TYPE, ct);
        }
        if (!headers.contains(CONTENT_LENGTH) && !headers.contains(TRANSFER_ENCODING)) {
            // [RFC9112#7] transfer encodings are case-insensitive
            // [RFC9112#6.1] transfer encodings must not be sent to an HTTP/1.0 client
            boolean modern      = request != null && request.getVersion() == 11;
            String  accepted    = request == null ? null : request.getHeaders().get(ACCEPT_ENCODING);
            String  compression = getHighestQValue(accepted, "identity", "identity", "gzip", "deflate");
            if (compression != null && !compression.equals("identity") &&
                    (length < 0 || length > 300) && isCompressible(ct) && modern) {
                headers.add(TRANSFER_ENCODING, CHUNKED); // compressed data is always unknown length
                headers.add(CONTENT_ENCODING, compression);
            } else if (length < 0 && modern) {
                headers.add(TRANSFER_ENCODING, CHUNKED); // unknown length
            } else if (length >= 0) {
                headers.add(CONTENTLENGTH, Long.toString(length)); // known length
            }
        }
        if (!headers.contains(VARY)) // [RFC9110#12.5.5] Vary field should include headers
            headers.add(VARY, ACCEPT_ENCODING); // that are used in selecting representation
        if (lastModified > 0 && !headers.contains(LAST_MODIFIED)) // [RFC9110#8.8.2.1]
            headers.add(LAST_MODIFIED, DateUtils.formatDate(Math.min(lastModified, System.currentTimeMillis())));
        if (etag != null && !headers.contains(ETAG))
            headers.add(ETAG, etag);
        if (request != null && CLOSE.equalsIgnoreCase(request.getHeaders().get(CONNECTION))
                && !headers.contains(CONNECTION))
            headers.add(CONNECTION, CLOSE); // #[RFC9112#9.6] should reply to close with close
        sendHeaders(status);
    }
    /**
     * 返回支持的列表中具有最高质量值的元素。
     *
     * @param list 元素列表字符串
     * @param def 如果没有匹配项（且未被明确排除）时返回的默认值
     * @param supported 支持值的列表（包括默认值）
     * @return 支持的列表中具有最高质量值的元素，
     *         或默认值（如果未被明确排除）
     */
    public static String getHighestQValue(String list, String def, String... supported) {
        String value = null;
        double qvalue = -1;
        double wildcard = -1;
        List<String> items = new ArrayList<>(Arrays.asList(supported)); // supported but unmentioned values
        for (String s : splitElements(list, true)) {
            String[] pair = split(s, ";", -1);
            double q = pair.length > 1 && pair[1].startsWith("q=") ? Double.parseDouble(pair[1].substring(2)) : 1;
            if (pair[0].equals("*")) {
                wildcard = q; // save wildcard qvalue
            } else if (items.remove(pair[0]) && q >= qvalue && q > 0) {
                qvalue = q; // best qvalue so far
                value = pair[0]; // best candidate so far
            }
        }
        // wildcard allows or disallows unmentioned values (including default)
        // no wildcard disallows unmentioned values (except default)
        return wildcard < 0 && value == null && items.contains(def) ? def
                : wildcard > qvalue && wildcard > 0 && !items.isEmpty() ? items.get(0) : value;
    }

    /**
     * 检查给定内容类型（MIME类型）的数据是否可压缩。
     *
     * @param contentType 内容类型
     * @return 若数据可压缩则返回真，若不可压缩则返回假
     */
    public static boolean isCompressible(String contentType) {
        int pos = contentType.indexOf(';'); // exclude params
        String ct = pos < 0 ? contentType : contentType.substring(0, pos);
        for (String s : gzipConfig.compressibleContentTypes)
            if (s.equals(ct) || s.charAt(0) == '*' && ct.endsWith(s.substring(1))
                    || s.charAt(s.length() - 1) == '*' && ct.startsWith(s.substring(0, s.length() - 1)))
                return true;
        return false;
    }

    /**
     * 发送带有指定状态的完整响应，并将指定字符串作为主体。文本将以 UTF-8 字符集发送。如果没有明确设置 Content-Type 头，将默认设置为
     * text/html，因此文本必须包含有效且正确转义的 HTML。
     *
     * @param status 响应状态
     * @param text   文本主体（以 text/html 形式发送）
     * @throws IOException 如果发生错误
     */
    public void send(int status, String text) throws IOException {
        byte[] content = text.getBytes("UTF-8");
        sendHeaders(status, content.length, -1,
                "W/\"" + Integer.toHexString(text.hashCode()) + "\"",
                "text/html; charset=utf-8", null);
        OutputStream out = getBody();
        if (out != null)
            out.write(content);
    }

    /**
     * 发送一个包含指定状态和详细信息的错误响应。
     * 创建一个HTML主体，其中包含状态及其描述，
     * 以及消息，该消息将使用以下方式进行转义。
     *
     * @param status 响应状态
     * @param text   文本主体（作为text/html发送）
     * @throws IOException 如果发生错误
     */
    public void sendError(int status, String text) throws IOException {
        send(status, String.format(
                "<!DOCTYPE html>%n<html>%n<head><title>%d %s</title></head>%n" +
                        "<body><h1>%d %s</h1>%n<p>%s</p>%n</body></html>",
                status, statuses[status], status, statuses[status], escapeHTML(text)));
    }

    /**
     * 发送带有指定状态和默认主体的错误响应。
     *
     * @param status 响应状态
     * @throws IOException 如果发生错误
     */
    public void sendError(int status) throws IOException {
        String text = status < 400 ? ":)" : "sorry it didn't work out :(";
        sendError(status, text);
    }

    /**
     * 发送响应体。此方法必须在响应头已发送（并且指示存在主体）之后才可调用。
     *
     * @param body   包含响应体的流
     * @param length 响应体的完整长度，或者-1表示整个流
     * @param range  应该发送的响应体中的子范围，如果要发送整个主体则为null
     * @throws IOException 如果发生错误
     */
    public void sendBody(InputStream body, long length, long[] range) throws IOException {
        OutputStream out = getBody();
        if (out != null) {
            if (range != null) {
                long offset = range[0];
                length = range[1] - range[0] + 1;
                while (offset > 0) {
                    long skip = body.skip(offset);
                    if (skip == 0)
                        throw new IOException("can't skip to " + range[0]);
                    offset -= skip;
                }
            }
            transfer(body, out, length);
        }
    }

    /**
     * 发送301或302响应，将客户端重定向至指定的URL。
     *
     * @param url       客户端重定向的绝对URL
     * @param permanent 指定是发送永久性(301)还是
     *                  临时性(302)重定向状态
     * @throws IOException 如果发生IO错误或URL格式不正确
     */
    public void redirect(String url, boolean permanent) throws IOException {
        try {
            url = new URI(url).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IOException("malformed URL: " + url);
        }
        headers.add("Location", url);
        // some user-agents expect a body, so we send it
        if (permanent)
            sendError(301, "Permanently moved to " + url);
        else
            sendError(302, "Temporarily moved to " + url);
    }

    /**
     * {@code ResponseOutputStream} 包含一个通过连接生成的单一响应，
     * 并不关闭底层流，以便于后续响应的使用。
     */
    static class ResponseOutputStream extends FilterOutputStream {
        /**
         * 构造一个响应输出流。
         *
         * @param out 基础输出流
         */
        public ResponseOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() {} // keep underlying connection stream open

        @Override // override the very inefficient default implementation
        public void write(byte[] b, int off, int len) throws IOException {out.write(b, off, len);}
    }

    /**
     * {@code ChunkedOutputStream} 是一种使用“分块”传输编码的输出流。
     * 它仅应在事先不知道内容长度的情况下使用，
     * 并且响应的 Transfer-Encoding 头部应设置为“chunked”。
     * <p>
     * 通过调用 {@link #write(byte[], int, int)} 方法将数据写入流中，
     * 每次调用该方法都会写入一个新的数据块。要结束流，必须调用 {@link #writeTrailingChunk} 方法，或关闭该流。
     */
    static class ChunkedOutputStream extends FilterOutputStream {

        protected int state; // the current stream state

        /**
         * Constructs a ChunkedOutputStream with the given underlying stream.
         *
         * @param out the underlying output stream to which the chunked stream
         *            is written
         * @throws NullPointerException if the given stream is null
         */
        public ChunkedOutputStream(OutputStream out) {
            super(out);
            if (out == null)
                throw new NullPointerException("output stream is null");
        }

        /**
         * Initializes a new chunk with the given size.
         *
         * @param size the chunk size (must be positive)
         * @throws IllegalArgumentException if size is negative
         * @throws IOException              if an IO error occurs, or the stream has
         *                                  already been ended
         */
        protected void initChunk(long size) throws IOException {
            if (size < 0)
                throw new IllegalArgumentException("invalid size: " + size);
            if (state > 0)
                out.write(CRLF); // end previous chunk
            else if (state == 0)
                state = 1; // start first chunk
            else
                throw new IOException("chunked stream has already ended");
            out.write(getBytes(Long.toHexString(size)));
            out.write(CRLF);
        }

        /**
         * Writes the trailing chunk which marks the end of the stream.
         *
         * @param headers the (optional) trailing headers to write, or null
         * @throws IOException if an error occurs
         */
        public void writeTrailingChunk(Headers headers) throws IOException {
            initChunk(0); // zero-sized chunk marks the end of the stream
            if (headers == null)
                out.write(CRLF); // empty header block
            else
                headers.writeTo(out);
            state = -1;
        }

        /**
         * Writes a chunk containing the given byte. This method initializes
         * a new chunk of size 1, and then writes the byte as the chunk data.
         *
         * @param b the byte to write as a chunk
         * @throws IOException if an error occurs
         */
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        /**
         * Writes a chunk containing the given bytes. This method initializes
         * a new chunk of the given size, and then writes the chunk data.
         *
         * @param b   an array containing the bytes to write
         * @param off the offset within the array where the data starts
         * @param len the length of the data in bytes
         * @throws IOException               if an error occurs
         * @throws IndexOutOfBoundsException if the given offset or length
         *                                   are outside the bounds of the given array
         */
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > 0) // zero-sized chunk is the trailing chunk
                initChunk(len);
            out.write(b, off, len);
        }

        /**
         * Writes the trailing chunk if necessary, and closes the underlying stream.
         *
         * @throws IOException if an error occurs
         */
        @Override
        public void close() throws IOException {
            if (state > -1)
                writeTrailingChunk(null);
            super.close();
        }
    }
}
