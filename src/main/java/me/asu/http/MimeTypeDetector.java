package me.asu.http;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static me.asu.http.Strings.isEmpty;

public class MimeTypeDetector {
    /**
     * 路径后缀（例如文件扩展名）与其对应的MIME类型之间的映射关系。
     */
    protected static final Map<String, String> contentTypes = new ConcurrentHashMap<>();

    static {
        // initial common types
        addContentType("application/font-woff", "woff");
        addContentType("application/font-woff2", "woff2");
        addContentType("application/java-archive", "jar");
        addContentType("application/javascript", "js");
        addContentType("application/json", "json");
        addContentType("application/octet-stream", "exe");
        addContentType("application/pdf", "pdf");
        addContentType("application/x-7z-compressed", "7z");
        addContentType("application/x-compressed", "tgz");
        addContentType("application/x-gzip", "gz");
        addContentType("application/x-tar", "tar");
        addContentType("application/xhtml+xml", "xhtml");
        addContentType("application/zip", "zip");
        addContentType("audio/mpeg", "mp3");
        addContentType("image/gif", "gif");
        addContentType("image/jpeg", "jpg", "jpeg");
        addContentType("image/png", "png");
        addContentType("image/svg+xml", "svg");
        addContentType("image/x-icon", "ico");
        addContentType("text/css", "css");
        addContentType("text/csv", "csv");
        addContentType("text/html; charset=utf-8", "htm", "html");
        addContentType("text/plain", "txt", "text", "log");
        addContentType("text/xml", "xml");

        Path p = Paths.get("mime.prorperties");
        if (Files.isReadable(p)) {
            try (InputStream in = Files.newInputStream(p)) {
                addContentTypes(in);
            } catch (IOException e) {

            }
        } else {
            try (InputStream in = MimeTypeDetector.class.getClassLoader().getResourceAsStream(p.toString())) {
                if (in != null) {
                    addContentTypes(in);
                }
            } catch (IOException e) {

            }
        }
    }

    /**
     * 从标准的 "mime.types" 文件中添加内容类型映射。
     *
     * @param in 一个包含 "mime.types" 文件的流
     * @throws IOException           如果发生错误
     * @throws FileNotFoundException 如果文件未找到或无法读取
     */
    public static void addContentTypes(InputStream in) throws IOException {
        try (InputStream is = in) {
            Properties p = new Properties();
            p.load(in);
            final Set<String> strings = p.stringPropertyNames();
            for (String string : strings) {
                addContentType(p.getProperty(string), string);
            }
        } catch (IOException ignore) { // the end of file was reached - it's ok
        }
    }

    /**
     * 为给定的路径后缀添加内容类型映射。
     * 如果任何路径后缀之前已经与某个内容类型关联，
     * 则将其替换为给定的内容类型。路径后缀被视为不区分大小写，
     * 并且内容类型将被转换为小写。
     *
     * @param contentType 将与给定路径后缀关联的内容类型（MIME类型）
     * @param suffixes    将与内容类型相关联的路径后缀，例如被服务文件的文件扩展名
     *                    （不包括 '.' 字符）
     */
    public static void addContentType(String contentType, String... suffixes) {
        for (String suffix : suffixes)
            contentTypes.put(suffix.toLowerCase(Locale.US), contentType.toLowerCase(Locale.US));
    }

    /**
     * 根据给定路径的后缀返回其内容类型，
     * 如果无法确定，则返回给定的默认内容类型。
     *
     * @param fileName 请求其内容类型的路径
     * @return 给定路径的内容类型
     */
    public static String detect(String fileName) {
        String type = null;
        Path   path = Paths.get(fileName);
        try {
            type = Files.probeContentType(path);
        } catch (IOException e) {

        }

        if (isEmpty(type)) {
            //从最后一个点之后截取字符串
            int i = fileName.lastIndexOf(".");
            if (i == -1) {
                type = "application/octet-stream";
            } else {
                String suffix = fileName.substring(i + 1);
                type = contentTypes.get(suffix);
                if (type == null) {
                    type = "application/octet-stream";
                }
            }
        }

        return type;

    }
}