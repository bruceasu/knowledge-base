package me.asu.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * {@code MultipartInputStream} 解码一种内容类型为 "multipart/*" 的 InputStream（如 RFC 2046 中所定义），
 * 提供其各个部分的基础数据。
 * <p>
 * {@code InputStream} 方法（例如 {@link #read}）仅与当前部分相关，而 {@link #nextPart} 方法则用于
 * 向下一个部分的开头推进。
 */
public class MultipartInputStream extends FilterInputStream {
    /**
     * A convenience array containing the carriage-return and line feed chars.
     */
    public static final byte[] CRLF = {0x0d, 0x0a};

    /**
     * The Carriage Return ASCII character value.
     */
    public static final byte CR = 0x0D;

    /**
     * The Line Feed ASCII character value.
     */
    public static final byte LF = 0x0A;

    /**
     * The \t ASCII character value.
     */
    public static final byte TAB = 0x09;

    /**
     * The space  ASCII character value.
     */
    public static final byte SPC = 0x20;
    /**
     * The dash (-) ASCII character value.
     */
    public static final byte DASH = 0x2D;

    protected final byte[] boundary; // including leading CRLF--
    protected final byte[] buf = new byte[4096];
    protected int head, tail; // indices of current part's data in buf
    protected int end; // last index of input data read into buf
    protected int len; // length of found boundary
    protected int state; // initial, started data, start boundary, EOS, last boundary, epilogue

    /**
     * Constructs a MultipartInputStream with the given underlying stream.
     * <pre>
     * POST /t2/upload.do HTTP/1.1
     * User-Agent: SOHUWapRebot
     * Accept-Language: zh-cn,zh;q=0.5
     * Accept-Charset: GBK,utf-8;q=0.7,*;q=0.7
     * Connection: keep-alive
     * Content-Length: 60408
     * Content-Type:multipart/form-data; boundary=--ZnGpDtePMx0KrHh_G0X99Yef9r8JZsRJSXC
     * Host: localhost
     * <p>
     * --ZnGpDtePMx0KrHh_G0X99Yef9r8JZsRJSXC
     * Content-Disposition: form-data;name=”desc”
     * Content-Type: text/plain; charset=UTF-8
     * Content-Transfer-Encoding: 8bit
     * <p>
     * [……][……][……][……]………………………
     * --ZnGpDtePMx0KrHh_G0X99Yef9r8JZsRJSXC
     * Content-Disposition: form-data;name=”pic”; filename=”photo.jpg”
     * Content-Type: application/octet-stream
     * Content-Transfer-Encoding: binary
     * <p>
     * [图片二进制数据]
     * --ZnGpDtePMx0KrHh_G0X99Yef9r8JZsRJSXC--
     * </pre>
     *
     * @param in       the underlying multipart stream
     * @param boundary the multipart boundary
     * @throws NullPointerException     if the given stream or boundary is null
     * @throws IllegalArgumentException if the given boundary's size is not
     *                                  between 1 and 70
     */
    public MultipartInputStream(InputStream in, byte[] boundary) {
        super(in);
        int len = boundary.length;
        if (len == 0 || len > 70)
            throw new IllegalArgumentException("invalid boundary length");
        this.boundary = new byte[len + 4]; // CRLF--boundary
        System.arraycopy(CRLF, 0, this.boundary, 0, 2);
        this.boundary[2] = this.boundary[3] = '-';
        System.arraycopy(boundary, 0, this.boundary, 4, len);
    }

    @Override
    public int read() throws IOException {
        if (!fill())
            return -1;
        return buf[head++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!fill())
            return -1;
        len = Math.min(tail - head, len);
        System.arraycopy(buf, head, b, off, len); // throws IOOBE as necessary
        head += len;
        return len;
    }

    @Override
    public long skip(long len) throws IOException {
        if (len <= 0 || !fill())
            return 0;
        len = Math.min(tail - head, len);
        head += len;
        return len;
    }

    @Override
    public int available() throws IOException {
        return tail - head;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    /**
     * 将流的位置向前推进至下一部分的开始。
     * 在第一次调用此方法之前读取的数据为前言，
     * 在此方法返回false后读取的数据为尾声。
     *
     * @return 如果成功则返回true，如果没有更多部分则返回false
     * @throws IOException 如果发生错误则抛出该异常
     */
    public boolean nextPart() throws IOException {
        while (skip(buf.length) != 0) ; // skip current part (until boundary)
        head = tail += len; // the next part starts right after boundary
        state |= 1; // started data (after first boundary)
        if (state >= 8) { // found last boundary
            state |= 0x10; // now beyond last boundary (epilogue)
            return false;
        }
        findBoundary(); // update indices
        return true;
    }

    /**
     * 从底层流中填充缓冲区以获取更多数据。
     *
     * @return 如果当前部分有可用数据，则返回真；如果当前部分的结束已达到，则返回假
     * @throws IOException 如果发生错误或输入格式无效
     */
    protected boolean fill() throws IOException {
        // check if we already have more available data
        if (head != tail) // remember that if we continue, head == tail below
            return true;
        // if there's no more room, shift extra unread data to beginning of buffer
        if (tail > buf.length - 256) { // max boundary + whitespace supported size
            System.arraycopy(buf, tail, buf, 0, end -= tail);
            head = tail = 0;
        }
        // read more data and look for boundary (or potential partial boundary)
        int read;
        do {
            read = super.read(buf, end, buf.length - end);
            if (read < 0)
                state |= 4; // end of stream (EOS)
            else
                end += read;
            findBoundary(); // updates tail and length to next potential boundary
            // if we found a partial boundary with no data before it, we must
            // continue reading to determine if there is more data or not
        } while (read > 0 && tail == head && len == 0);
        // update and validate state
        if (tail != 0) // anything but a boundary right at the beginning
            state |= 1; // started data (preamble or after boundary)
        if (state < 8 && len > 0)
            state |= 2; // found start boundary
        if ((state & 6) == 4 // EOS but no start boundary found
                || len == 0 && ((state & 0xFC) == 4 // EOS but no last and no more boundaries
                || read == 0 && tail == head)) // boundary longer than buffer
            throw new IOException("missing boundary");
        if (state >= 0x10) // in epilogue
            tail = end; // ignore boundaries, return everything
        return tail > head; // available data in current part
    }

    /**
     * 在缓冲区剩余数据中寻找第一个（潜在的）边界。
     * 相应地更新尾部、长度和状态字段。
     *
     * @throws IOException 如果发生错误或输入格式无效
     */
    protected void findBoundary() throws IOException {
        // see RFC2046#5.1.1 for boundary syntax
        len = 0;
        int off = tail - ((state & 1) != 0 || buf[0] != DASH ? 0 : 2); // skip initial CRLF?
        for (int end = this.end; tail < end; tail++, off = tail) {
            int j = tail; // end of potential boundary
            // try to match boundary value (leading CRLF is optional at first boundary)
            while (j < end && j - off < boundary.length && buf[j] == boundary[j - off])
                j++;
            // return potential partial boundary which is cut off at end of current data
            if (j + 1 >= end) // at least two more chars needed for full boundary (CRLF or --)
                return;
            // if we found the boundary value, expand selection to include full line
            if (j - off == boundary.length) {
                // check if last boundary of entire multipart
                if (buf[j] == DASH && buf[j + 1] == DASH) {
                    j += 2;
                    state |= 8; // found last boundary that ends multipart
                }
                // allow linear whitespace after boundary
                while (j < end && (buf[j] == SPC || buf[j] == TAB))
                    j++;
                // check for CRLF (required, except in last boundary with no epilogue)
                if (j + 1 < end && buf[j] == 0x0D && buf[j + 1] == 0x0A) // found CRLF
                    len = j - tail + 2; // including optional whitespace and CRLF
                else if (j + 1 < end || (state & 4) != 0 && j + 1 == end) // should have found or never will
                    throw new IOException("boundary must end with CRLF");
                else if ((state & 4) != 0) // last boundary with no CRLF at end of data is valid
                    len = j - tail;
                return;
            }
        }
    }


}
