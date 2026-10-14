package me.asu.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

import static me.asu.http.Bytes.CRLF;
import static me.asu.http.Bytes.getBytes;
import static me.asu.http.Streams.readLine;
import static me.asu.http.Strings.*;

/**
 * {@code Headers} 类封装了一组 HTTP 头部信息。
 * <p>
 * 头部名称在处理时不区分大小写，尽管该类保留了它们的原始大小写。
 * 同时，头部的插入顺序也得以保持。
 */
public class Headers implements Iterable<Header> {
    protected List<Header> headers = new ArrayList<Header>(32);

    /**
     * 从给定的流中读取标题。
     * 会去除值前后多余的空白字符。
     * 重复的标题会合并为一个单一元素列表值。
     *
     * @param in 从中读取标题的流
     * @return 读取的标题（如果不存在，可能为空）
     * @throws IOException 如果发生输入输出错误，或者标题格式错误
     *                     或者标题行超过100行
     */
    public static Headers readHeaders(InputStream in) throws IOException {
        Headers headers = new Headers();
        String  line;
        int     count   = 0;
        while ((line = readLine(in)).length() > 0) {
            int pos = line.indexOf(':');
            if (pos <= 0)
                throw new IOException("invalid header: \"" + line + "\"");
            String name  = line.substring(0, pos);
            String value = line.substring(pos + 1).trim(); // [RFC9112#5.1] remove OWS
            if (!name.equals(name.trim())) // [RFC9112#2.2/5] no WS before line or colon
                throw new IOException("invalid whitespace in header: \"" + line + "\"");
            Header prev = headers.replace(name, value);
            if (prev != null) // [RFC9110#5.3] concatenate repeated headers
                headers.replace(name, prev.getValue() + ", " + value);
            if (++count > 100)
                throw new IOException("too many header lines");
        }
        return headers;
    }

    /**
     * 返回已添加标题的数量。
     *
     * @return 已添加标题的数量
     */
    public int size() {
        return headers.size();
    }

    /**
     * 返回具有指定名称的第一个头部的值。
     *
     * @param name 头部名称（不区分大小写）
     * @return 头部值，如果不存在则返回 null
     */
    public String get(String name) {
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name))
                return header.getValue();
        }
        return null;
    }

    /**
     * 返回具有指定名称的头部的日期值。
     *
     * @param name 头部名称（不区分大小写）
     * @return 头部值作为日期，如果不存在或值不符合任何支持的日期格式，则返回 null
     */
    public Date getDate(String name) {
        try {
            String header = get(name);
            return header == null ? null : DateUtils.parseDate(header);
        } catch (IllegalArgumentException iae) {
            return null;
        }
    }

    /**
     * 返回是否存在具有给定名称的头部。
     *
     * @param name 标头名称（不区分大小写）
     * @return 是否存在具有给定名称的头部
     */
    public boolean contains(String name) {
        return get(name) != null;
    }

    /**
     * 将给定名称和值的头部添加到此
     * 头部集合的末尾。前导和尾随空格将被去除。
     *
     * @param name  头部名称（不区分大小写）
     * @param value 头部值
     */
    public void add(String name, String value) {
        Header header = new Header(name, value); // also validates
        headers.add(header);
    }

    /**
     * 将所有提供的头信息以其原始顺序添加到该头信息集合的末尾。
     *
     * @param headers 要添加的头信息
     */
    public void addAll(Headers headers) {
        for (Header header : headers)
            add(header.getName(), header.getValue());
    }

    /**
     * 添加一个具有指定名称和值的头信息，替换第一个
     * 具有相同名称的现有头信息。如果没有相同名称的现有头信息，
     * 则将其作为{@link #add}中的值添加。
     *
     * @param name  头信息名称（不区分大小写）
     * @param value 头信息值
     * @return 被替换的头信息，如果不存在则返回null
     */
    public Header replace(String name, String value) {
        Header prev = null;
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                prev = header;
                break;
            }
        }
        if (prev != null) {
            headers.remove(prev);
            add(name, value);
            return prev;
        } else {
            add(name, value);
            return null;
        }

    }

    /**
     * 移除所有具有指定名称的头部（如果存在的话）。
     *
     * @param name 头部名称（不区分大小写）
     */
    public void remove(String name) {
        Header prev = null;
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                prev = header;
                break;
            }
        }
        if (prev != null) {
            headers.remove(prev);
        }
    }

    /**
     * 将头部信息写入指定的输出流（包括尾部的回车换行符）。
     *
     * @param out 要写入头部信息的输出流
     * @throws IOException 如果发生错误
     */
    public void writeTo(OutputStream out) throws IOException {
        for (Header header : headers) {
            out.write(getBytes(header.getName(), ": ", header.getValue()));
            out.write(CRLF);
        }
        out.write(CRLF); // ends header block
    }

    /**
     * 返回一个头部的参数。参数顺序得以保持，
     * 第一个键（以迭代顺序）是头部的值
     * ，不包括参数。参数名称被转换为小写字母。
     *
     * @param name 头部名称（不区分大小写）
     * @return 头部的参数名称及其值
     */
    public Map<String, String> getParams(String name) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String param : split(get(name), ";", -1)) {
            String[] pair = split(param, "=", 2);
            // [RFC9110#5.6.6] param names are case-insensitive
            String p   = pair[0].toLowerCase(Locale.US); // normalize to lowercase
            String val = pair.length == 1 ? "" : trimLeft(trimRight(pair[1], '"'), '"');
            params.put(p, val);
        }
        return params;
    }

    /**
     * 返回一个迭代器，按照插入顺序遍历头部信息。
     * 如果在迭代过程中对头部集合进行了修改，
     * 则迭代结果是未定义的。移除操作是不支持的。
     *
     * @return 返回一个遍历头部信息的迭代器
     */
    @Override
    public Iterator<Header> iterator() {
        return new ArrayList(headers).iterator();
    }
}