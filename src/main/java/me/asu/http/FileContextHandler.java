package me.asu.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Date;
import java.util.Formatter;
import java.util.Locale;

import static me.asu.http.HeaderKey.*;
import static me.asu.http.MimeTypeDetector.detect;
import static me.asu.http.Strings.splitElements;
import static me.asu.http.Strings.trimRight;

/**
 * {@code FileContextHandler} 通过将上下文映射到磁盘上的文件或文件夹来提供服务。
 * 为了能够递归地服务于一个目录，上下文路径必须以名为“*”的通配符路径参数结束，
 * 例如 “/path/{*}”（带斜杠）或 “/path{*}”（无论是否带斜杠）。
 */
public class FileContextHandler implements ContextHandler {

    protected final File base;

    public FileContextHandler(File dir) throws IOException {
        this.base = dir.getCanonicalFile();
    }
    public FileContextHandler(Path dir) throws IOException {
        this.base = dir.toFile().getCanonicalFile();
    }
    public FileContextHandler(String dir) throws IOException {
        this.base = new File(dir).getCanonicalFile();
    }

    @Override
    public int serve(Request req, Response resp) throws IOException {
        String path     = req.getPath();
        String filename = req.getParamMap().getParameter("*");
        String context  = filename == null ? path : path.substring(0, path.length() - filename.length());
        return serveFile(base, context, req, resp);
    }


    /**
     * 提供文件内容及其对应的内容类型、最后修改时间等信息。
     * 按照RFC的规定处理条件和部分检索。
     *
     * @param file 现有且可读的文件，其内容将被提供
     * @param req  请求
     * @param resp 内容将被写入的响应
     * @throws IOException 如果发生错误
     */
    public static void serveFileContent(File file, Request req, Response resp) throws IOException {
        long   len          = file.length();
        long   lastModified = file.lastModified(); // [RFC9110#8.8.2.1] must not be in the future
        String etag         = "W/\"" + lastModified + "\""; // weak tag based on modified date milliseconds
        // we round down timestamps to second resolution, because that's what date headers support
        // (if a resource changes more than once per second, one should use ETags instead)
        long lastModifiedSecs = lastModified - lastModified % 1000; // rounded to seconds
        // handle conditional request and range
        long[] range  = req.getRange(len);
        int    status = getConditionalStatus(req, lastModifiedSecs, etag, range != null);
        if (status == 206)
            status = range[0] >= len ? 416 : 200; // unsatisfiable range or 200 flow but with range
        else
            range = null; // ignore range
        // send the response
        Headers respHeaders = resp.getHeaders();
        switch (status) {
            case 304: // [RFC9110#15.4.5] no other headers or body allowed
                respHeaders.add(ETAG, etag);
                respHeaders.add(VARY, ACCEPT_ENCODING);
                respHeaders.add(LAST_MODIFIED, DateUtils.formatDate(lastModified));
                resp.sendHeaders(304);
                break;
            case 412:
                resp.sendError(412);
                break;
            case 416:
                respHeaders.add("Content-Range", "bytes */" + len);
                resp.sendError(416);
                break;
            case 200:
                // send OK response
                resp.sendHeaders(200, len, lastModified, etag,
                        detect(file.getName()), range);
                // send body
                InputStream in = new FileInputStream(file);
                try {
                    resp.sendBody(in, len, range);
                } finally {
                    in.close();
                }
                break;
            default:
                resp.sendError(500); // should never happen
                break;
        }
    }

    /**
     * 通过基于文件的资源提供上下文的内容。
     * <p>
     * 该文件通过从请求路径中剥离给定的上下文前缀，然后将结果附加到给定的基础目录来定位。
     * <p>
     * 缺失、禁止访问或其他无效的文件将返回相应的错误响应。
     * 如果允许目录将作为HTML索引页面提供；否则，将返回禁止访问的错误。
     * 文件将以其相应的内容类型发送，并根据RFC处理条件和部分检索。
     *
     * @param base    映射到上下文的基础目录
     * @param context 映射到基础目录的上下文
     * @param req     请求
     * @param resp    内容写入的响应
     * @return 返回的HTTP状态码；如果已经发送响应，则返回0
     * @throws IOException 如果发生错误
     */
    public static int serveFile(File base, String context,
                                Request req, Response resp) throws IOException {
        String path = req.getPath();
        File   file = new File(base, path.substring(context.length())).getCanonicalFile();
        if (!file.exists() || file.isHidden() || file.getName().startsWith(".")) {
            return 404;
        } else if (!file.canRead() || !file.getPath().startsWith(base.getPath())) { // validate
            return 403;
        } else if (file.isDirectory()) {
            if (path.endsWith("/")) {
                if (!req.server.allowGeneratedIndex) {
                    return 403;
                } else {
                    resp.send(200, createIndex(file, path));
                }
            } else { // redirect to the normalized directory URL ending with '/'
                resp.redirect(req.getBaseURL() + path + "/", true);
            }
        } else if (path.endsWith("/")) {
            return 404; // non-directory ending with slash (File constructor removed it)
        } else {
            serveFileContent(file, req, resp);
        }
        return 0;
    }

    /**
     * 提供作为 HTML 文件索引的目录内容。
     *
     * @param dir  要提供内容的现有可读目录
     * @param path 显示与目录相对应的基本路径
     * @return 包含该目录文件索引的 HTML 字符串
     */
    public static String createIndex(File dir, String path) {
        if (!path.endsWith("/"))
            path += "/";
        // calculate name column width
        int w = 21; // minimum width
        for (String name : dir.list())
            if (name.length() > w)
                w = name.length();
        w += 2; // with room for added slash and space
        // note: we use apache's format, for consistent user experience
        Formatter f = new Formatter(Locale.US);
        f.format("<!DOCTYPE html>%n" +
                        "<html><head><title>Index of %s</title></head>%n" +
                        "<body><h1>Index of %s</h1>%n" +
                        "<pre> Name%" + (w - 5) + "s Last modified      Size<hr>",
                path, path, "");
        if (path.length() > 1) // add parent link if not root path
            f.format(" <a href=\"%s/\">Parent Directory</a>%"
                    + (w + 5) + "s-%n", getParentPath(path), "");
        for (File file : dir.listFiles()) {
            try {
                String name = file.getName() + (file.isDirectory() ? "/" : "");
                String size = file.isDirectory() ? "- " : toSizeApproxString(file.length());
                // properly url-encode the link
                String link = new URI(null, path + name, null).toASCIIString();
                if (!file.isHidden() && !name.startsWith("."))
                    f.format(" <a href=\"%s\">%s</a>%-" + (w - name.length()) +
                                    "s&#8206;%td-%<tb-%<tY %<tR%6s%n",
                            link, name, "", file.lastModified(), size);
            } catch (URISyntaxException ignore) {}
        }
        f.format("</pre></body></html>");
        return f.toString();
    }

    /**
     * 返回一个友好的字符串，用于大致表示给定的数据大小，
     * 例如：“316”、“1.8K”、“324M”等等。
     *
     * @param size 要显示的大小
     * @return 一个友好的字符串，用于大致表示给定的数据大小
     */
    public static String toSizeApproxString(long size) {
        final char[] units = {' ', 'K', 'M', 'G', 'T', 'P', 'E'};
        int          u;
        double       s;
        for (u = 0, s = size; s >= 1000; u++, s /= 1024) ;
        return String.format(s < 10 ? "%.1f%c" : "%.0f%c", s, units[u]);
    }

    /**
     * 返回给定路径的父路径。
     *
     * @param path 要返回其父路径的路径（必须以'/'开头）
     * @return 给定路径的父路径（不包含尾部斜杠），
     * 如果给定路径是根路径，则返回null
     */
    public static String getParentPath(String path) {
        path = trimRight(path, '/'); // remove trailing slash
        int slash = path.lastIndexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    /**
     * 将给定的 ETag 值与指定的 ETags 进行匹配。
     * <p>
     * 如果给定的 ETag 不为 null， 并且 ETags 中有一个是单独的 "*"，
     * 或者其中一个与给定的 ETag 完全相同，则表示匹配成功。
     * 如果使用强比较，则以弱 ETag 前缀 "W/" 开头的标签永远不会匹配。
     * 如果使用弱比较， 则标签比较会忽略弱 ETag 前缀 "W/"。
     * 详细信息请参见 RFC9110#8.8.3.2，RFC9110#13。
     *
     * @param strong 若为 true，则使用强比较，否则使用弱比较
     * @param etags  要进行匹配的 ETags
     * @param etag   要进行匹配的 ETag
     * @return 如果 ETag 匹配，则返回 true，否则返回 false
     */
    public static boolean match(boolean strong, String[] etags, String etag) {
        if (etag == null)
            return false;
        if (etags.length == 1 && etags[0].equals("*"))
            return true;
        for (String e : etags) {
            if (strong ? e.equals(etag) && !etag.startsWith("W/") && !e.startsWith("W/") : e.equals(etag)
                    || etag.length() - e.length() == 2 && etag.startsWith("W/") && etag.startsWith(e, 2)
                    || etag.length() - e.length() == -2 && e.startsWith("W/") && e.startsWith(etag, 2))
                return true;
        }
        return false;
    }

    /**
     * 根据请求中存在的条件头，计算给定请求及其资源的最后修改时间和 ETag 的适当响应状态。
     * 请参阅 RFC9110#13。
     *
     * @param req          请求对象
     * @param lastModified 资源的最后修改时间
     *                     （必须向下取整到秒，并且不得在未来）
     * @param etag         资源的 ETag
     * @param range        请求是否包含 Range 头
     * @return 请求的适当响应状态
     */
    public static int getConditionalStatus(Request req, long lastModified, String etag, boolean range) {
        Headers headers = req.getHeaders();
        // If-Match [RFC9110#13.1.1]
        String header = headers.get(IF_MATCH);
        if (header != null) {
            if (!match(true, splitElements(header, false), etag))
                return 412;
        } else {
            // If-Unmodified-Since [RFC9110#13.1.4]
            Date date = headers.getDate(IF_MODIFIED_SINCE);
            if (date != null && lastModified > date.getTime())
                return 412;
        }
        boolean isGetOrHead = req.getMethod().equals("GET") || req.getMethod().equals("HEAD");
        // If-None-Match [RFC9110#13.1.2]
        header = headers.get(IF_NONE_MATCH);
        if (header != null) {
            if (match(false, splitElements(header, false), etag)) // [RFC9110#13.1.2] weak matching
                return isGetOrHead ? 304 : 412;
        } else if (isGetOrHead) {
            // If-Modified-Since [RFC9110#13.1.3]
            Date date = headers.getDate(IF_MODIFIED_SINCE);
            if (date != null && lastModified <= date.getTime())
                return 304;
        }
        // [RFC9110#14.2] Range is ignored on any method other than GET,
        // and evaluated only if the response would otherwise be 200 (which is true at this point)
        if (!range || !isGetOrHead)
            return 200;
        // If-Range [RFC9110#13.1.5]
        header = req.getHeaders().get(IF_RANGE); // either a date or an etag
        if (header != null) {
            if (!header.startsWith("\"") && !header.startsWith("W/")) {
                Date date = req.getHeaders().getDate(IF_RANGE);
                if (date == null || lastModified != date.getTime()) // [RFC9110#13.1.5] exact match
                    return 200; // date validator doesn't match - ignore range
            } else if (!match(true, new String[]{header}, etag)) { // [RFC9110#13.1.5] strong etag
                return 200; // etag validator doesn't match - ignore range
            }
        }
        return 206; // send partial content according to range
    }
}
