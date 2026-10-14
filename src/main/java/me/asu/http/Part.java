package me.asu.http;

import lombok.Getter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static me.asu.http.HeaderKey.CONTENT_TYPE;
import static me.asu.http.Streams.readToken;
import static me.asu.http.Streams.transfer;

/**
 * The {@code Part} class encapsulates a single part of the multipart.
 */
@Getter
public class Part {
    public static final int FORM = 0;
    public static final int FILE = 1;
    public String name;
    public String filename;
    public Headers headers;
    public InputStream body;
    public int type = Part.FORM; // 0 : form, 1: file
    public String contentType;
    public Path path;

    public Path getPath() {
        return path;
    }

    public String getFilename() {
        return filename;
    }

    /***
     * 以字符串形式返回部分的主体。如果该部分的
     * 头信息未指定字符集，则使用UTF-8编码。
     *
     * @return 该部分的主体以字符串形式返回
     * @throws IOException 如果发生IO错误
     */
    public String getString() throws IOException {
        String charset = headers.getParams(CONTENT_TYPE).get("charset");
        return readToken(body, -1, charset == null ? "UTF-8" : charset, 8192);
    }

    public byte[] getBytes() throws IOException {
        return Bytes.toByteArray(body);
    }

    public void writeToTempFile() throws IOException {
        path = Files.createTempFile("temp-file-", ".dat");
        path.toFile().deleteOnExit();
        try (OutputStream os = new FileOutputStream(path.toFile())) {
            transfer(body, os, -1);
        }
    }
}
