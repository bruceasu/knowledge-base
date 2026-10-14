package me.asu.http;

import java.io.*;
import java.util.LinkedList;
import java.util.List;


/**
 * 提供了一组创建 Reader/Writer/InputStream/OutputStream 的便利函数
 *
 * @author zozoh(zozohtnt @ gmail.com)
 * @author Wendal(wendal1985 @ gmail.com)
 * @author bonyfish(mc02cxj @ gmail.com)
 */
public abstract class Streams {

    private static final int BUF_SIZE = 8192;
    private static final byte[] UTF_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * 判断两个输入流是否严格相等
     */
    public static boolean equals(InputStream sA, InputStream sB) throws IOException {
        int dA;
        while ((dA = sA.read()) != -1) {
            int dB = sB.read();
            if (dA != dB) {
                return false;
            }
        }
        return sB.read() == -1;
    }

    /**
     * 将一段文本全部写入一个writer。
     * <p/>
     * <b style=color:red>注意</b>，它并不会关闭输出流
     *
     * @param cs 文本
     */
    public static void write(Writer writer, CharSequence cs) throws IOException {
        if (null != cs && null != writer) {
            writer.write(cs.toString());
            writer.flush();
        }
    }

    /**
     * 将一段文本全部写入一个writer。
     * <p/>
     * <b style=color:red>注意</b>，它会关闭输出流
     *
     * @param writer 输出流
     * @param cs     文本
     */
    public static void writeAndClose(Writer writer, CharSequence cs) {
        try {
            write(writer, cs);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            safeClose(writer);
        }
    }

    /**
     * 将输入流写入一个输出流。块大小为 8192
     * <p/>
     * <b style=color:red>注意</b>，它并不会关闭输入/出流
     *
     * @param ops 输出流
     * @param ins 输入流
     * @return 写入的字节数
     */
    public static long write(OutputStream ops, InputStream ins) throws IOException {
        return write(ops, ins, BUF_SIZE);
    }

    /**
     * 将输入流写入一个输出流。
     * <p/>
     * <b style=color:red>注意</b>，它并不会关闭输入/出流
     *
     * @param ops        输出流
     * @param ins        输入流
     * @param bufferSize 缓冲块大小
     * @return 写入的字节数
     */
    public static long write(OutputStream ops, InputStream ins, int bufferSize) throws IOException {
        if (null == ops || null == ins) {
            return 0;
        }

        byte[] buf = new byte[bufferSize];
        int len;
        long bytesCount = 0;
        while (-1 != (len = ins.read(buf))) {
            bytesCount += len;
            ops.write(buf, 0, len);
        }
        ops.flush();
        return bytesCount;
    }

    /**
     * 将输入流写入一个输出流。块大小为 8192
     * <p/>
     * <b style=color:red>注意</b>，它会关闭输入/出流
     *
     * @param ops 输出流
     * @param ins 输入流
     * @return 写入的字节数
     */
    public static long writeAndClose(OutputStream ops, InputStream ins) {
        try {
            return write(ops, ins);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            safeClose(ops);
            safeClose(ins);
        }
    }

    /**
     * 将文本输入流写入一个文本输出流。块大小为 8192
     * <p/>
     * <b style=color:red>注意</b>，它并不会关闭输入/出流
     *
     * @param writer 输出流
     * @param reader 输入流
     */
    public static void write(Writer writer, Reader reader) throws IOException {
        if (null == writer || null == reader) {
            return;
        }

        char[] cbuf = new char[BUF_SIZE];
        int len;
        while (-1 != (len = reader.read(cbuf))) {
            writer.write(cbuf, 0, len);
        }
    }

    /**
     * 将文本输入流写入一个文本输出流。块大小为 8192
     * <p/>
     * <b style=color:red>注意</b>，它会关闭输入/出流
     *
     * @param writer 输出流
     * @param reader 输入流
     */
    public static void writeAndClose(Writer writer, Reader reader) {
        try {
            write(writer, reader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            safeClose(writer);
            safeClose(reader);
        }
    }

    /**
     * 将一个字节数组写入一个输出流。
     * <p/>
     * <b style=color:red>注意</b>，它并不会关闭输出流
     *
     * @param ops   输出流
     * @param bytes 字节数组
     */
    public static void write(OutputStream ops, byte[] bytes) throws IOException {
        if (null == ops || null == bytes || bytes.length == 0) {
            return;
        }
        ops.write(bytes);
    }

    /**
     * 将一个字节数组写入一个输出流。
     * <p/>
     * <b style=color:red>注意</b>，它会关闭输出流
     *
     * @param ops   输出流
     * @param bytes 字节数组
     */
    public static void writeAndClose(OutputStream ops, byte[] bytes) {
        try {
            write(ops, bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            safeClose(ops);
        }
    }

    /**
     * 从一个文本流中读取全部内容并返回
     * <p/>
     * <b style=color:red>注意</b>，它并不会关闭输出流
     *
     * @param reader 文本输出流
     * @return 文本内容
     */
    public static StringBuilder read(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] cbuf = new char[BUF_SIZE];
        int len;
        while (-1 != (len = reader.read(cbuf))) {
            sb.append(cbuf, 0, len);
        }
        return sb;
    }

    /**
     * 从一个文本流中读取全部内容并返回
     * <p/>
     * <b style=color:red>注意</b>，它会关闭输入流
     *
     * @param reader 文本输入流
     * @return 文本内容
     */
    public static String readAndClose(Reader reader) {
        try {
            return read(reader).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            safeClose(reader);
        }
    }

    /**
     * 读取一个输入流中所有的字节
     *
     * @param ins 输入流，必须支持 available()
     * @return 一个字节数组
     */
    public static byte[] readBytes(InputStream ins) throws IOException {
        byte[] bytes = new byte[ins.available()];
        ins.read(bytes);
        return bytes;
    }

    /**
     * 读取一个输入流中所有的字节，并关闭输入流
     *
     * @param ins 输入流，必须支持 available()
     * @return 一个字节数组
     */
    public static byte[] readBytesAndClose(InputStream ins) {
        byte[] bytes = null;
        try {
            bytes = readBytes(ins);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            Streams.safeClose(ins);
        }
        return bytes;
    }

    /**
     * 关闭一个可关闭对象，可以接受 null。如果成功关闭，返回 true，发生异常 返回 false
     *
     * @param cb 可关闭对象
     * @return 是否成功关闭
     */
    public static boolean safeClose(Closeable cb) {
        if (null != cb) {
            try {
                cb.close();
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * 安全刷新一个可刷新的对象，可接受 null
     *
     * @param fa 可刷新对象
     */
    public static void safeFlush(Flushable fa) {
        if (null != fa) {
            try {
                fa.flush();
            } catch (IOException e) {
            }
        }
    }

    /**
     * 为一个输入流包裹一个缓冲流。如果这个输入流本身就是缓冲流，则直接返回
     *
     * @param ins 输入流。
     * @return 缓冲输入流
     */
    public static BufferedInputStream buff(InputStream ins) {
        if (ins == null) {
            throw new NullPointerException("ins is null!");
        }
        if (ins instanceof BufferedInputStream) {
            return (BufferedInputStream) ins;
        }
        // BufferedInputStream的构造方法,竟然是允许null参数的!! 我&$#^$&%
        return new BufferedInputStream(ins);
    }

    /**
     * 为一个输出流包裹一个缓冲流。如果这个输出流本身就是缓冲流，则直接返回
     *
     * @param ops 输出流。
     * @return 缓冲输出流
     */
    public static BufferedOutputStream buff(OutputStream ops) {
        if (ops == null) {
            throw new NullPointerException("ops is null!");
        }
        if (ops instanceof BufferedOutputStream) {
            return (BufferedOutputStream) ops;
        }
        return new BufferedOutputStream(ops);
    }

    /**
     * 为一个文本输入流包裹一个缓冲流。如果这个输入流本身就是缓冲流，则直接返回
     *
     * @param reader 文本输入流。
     * @return 缓冲文本输入流
     */
    public static BufferedReader buffr(Reader reader) {
        if (reader instanceof BufferedReader) {
            return (BufferedReader) reader;
        }
        return new BufferedReader(reader);
    }

    /**
     * 为一个文本输出流包裹一个缓冲流。如果这个文本输出流本身就是缓冲流，则直接返回
     *
     * @param ops 文本输出流。
     * @return 缓冲文本输出流
     */
    public static BufferedWriter buffw(Writer ops) {
        if (ops instanceof BufferedWriter) {
            return (BufferedWriter) ops;
        }
        return new BufferedWriter(ops);
    }

    /**
     * 根据一个文件路径建立一个输入流
     *
     * @param path 文件路径
     * @return 输入流
     */
    public static InputStream fileIn(String path) {
        InputStream ins = null;
        try {
            ins = new FileInputStream(path);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        if (null == ins) {
            // TODO 考虑一下,应该抛异常呢?还是返回null呢?

            throw new RuntimeException(new FileNotFoundException(path));
            // return null;
        }
        return buff(ins);
    }

    /**
     * 根据一个文件路径建立一个输入流
     *
     * @param file 文件
     * @return 输入流
     */
    public static InputStream fileIn(File file) {
        try {
            return buff(new FileInputStream(file));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据一个文件路径建立一个 UTF-8文本输入流 <b>警告!! 本方法会预先读取3个字节以判断该文件是否存在BOM头</b>
     * <p/>
     * <b>警告!! 如果存在BOM头,则自动跳过</b>
     * <p/>
     *
     * @param path 文件路径
     * @return 文本输入流
     */
    public static Reader fileInr(String path) {
        return utf8r(fileIn(path));
    }

    public static Reader fileInr(String path, String charset) {
        return fileInr(new File(path), charset);
    }

    /**
     * 根据一个文件路径建立一个 UTF-8 文本输入流 <b>警告!! 本方法会预先读取3个字节以判断该文件是否存在BOM头</b>
     * <p/>
     * <b>警告!! 如果存在BOM头,则自动跳过</b>
     * <p/>
     *
     * @param file 文件
     * @return 文本输入流
     */
    public static Reader fileInr(File file) {
        return utf8r(fileIn(file));
    }

    public static Reader fileInr(File file, String charset) {
        InputStream is = fileIn(file);
        try {
            return new InputStreamReader(is, charset);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return new InputStreamReader(utf8filte(is));
        }
    }

    /**
     * 判断并移除UTF-8的BOM头
     */
    public static InputStream utf8filte(InputStream in) {
        try {
            if (in.available() == -1) {
                return in;
            }
            PushbackInputStream pis = new PushbackInputStream(in, 3);
            byte[] header = new byte[3];
            int len = pis.read(header, 0, 3);
            if (len < 1) {
                return in;
            }
            if (header[0] != UTF_BOM[0] || header[1] != UTF_BOM[1] || header[2] != UTF_BOM[2]) {
                pis.unread(header, 0, len);
            }
            return pis;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据一个文件路径建立一个输出流
     *
     * @param path 文件路径
     * @return 输出流
     */
    public static OutputStream fileOut(String path) {
        return fileOut(new File(path));
    }

    /**
     * 根据一个文件建立一个输出流
     *
     * @param file 文件
     * @return 输出流
     */
    public static OutputStream fileOut(File file) {
        try {
            return buff(new FileOutputStream(file));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据一个文件路径建立一个 UTF-8 文本输出流
     *
     * @param path 文件路径
     * @return 文本输出流
     */
    public static Writer fileOutw(String path) {
        return fileOutw(new File(path));
    }

    /**
     * 根据一个文件建立一个 UTF-8 文本输出流
     *
     * @param file 文件
     * @return 输出流
     */
    public static Writer fileOutw(File file) {
        return utf8w(fileOut(file));
    }

    public static Reader utf8r(InputStream is) {
        try {
            return new InputStreamReader(utf8filte(is), "utf-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return new InputStreamReader(utf8filte(is));
        }
    }

    public static Writer utf8w(OutputStream os) {
        try {
            return new OutputStreamWriter(os, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return new OutputStreamWriter(os);
        }
    }

    public static InputStream nullInputStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    public static InputStream wrap(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }


    public static void appendWriteAndClose(File f, String text) {
        FileWriter fw = null;
        try {
            fw = new FileWriter(f, true);
            fw.write(text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            safeClose(fw);
        }

    }

    public static List<String> readLinesAndClose(InputStream in) {
        List<String> list = new LinkedList<String>();
        if (in == null) {
            return list;
        }
        try (BufferedReader buffr = buffr(utf8r(in))) {
            String line;
            while ((line = buffr.readLine()) != null) {
                list.add(line);
            }
        } catch (IOException e) {

        } finally {
            safeClose(in);
        }
        return list;
    }

    public static List<String> readLinesAndClose(Reader reader) {
        List<String> list = new LinkedList<String>();
        if (reader == null) {
            return list;
        }
        try (BufferedReader buffr = buffr(reader)) {
            String line;
            while ((line = buffr.readLine()) != null) {
                list.add(line);
            }
        } catch (IOException e) {

        } finally {
            safeClose(reader);
        }
        return list;
    }

    /**
     * 从当前流位置开始读取标记，直到给定分隔符字节的第一次出现，采用指定的编码方式。
     * 如果将换行符 (LF) 指定为分隔符，则 CRLF（回车换行对）也会被视为一个分隔符。
     *
     * @param in 从中读取标记的流
     * @param delim 标记结束的字节值，若标记在流末尾结束，则设为 -1
     * @param enc 字符编码名称
     * @param maxLength 读取的最大长度（以字节为单位）
     * @return 读取的标记（不包括分隔符）
     * @throws UnsupportedEncodingException 如果编码不支持
     * @throws EOFException 如果在找到分隔符之前到达流的末尾
     * @throws IOException 如果发生输入输出错误，或者在到达标记结束之前达到最大长度
     */
    public static String readToken(InputStream in, int delim,
                                   String enc, int maxLength) throws IOException {
        // note: we avoid using a ByteArrayOutputStream here because it
        // suffers the overhead of synchronization for each byte written
        int b;
        int len = 0; // buffer length
        int count = 0; // number of read bytes
        byte[] buf = null; // optimization - lazy allocation only if necessary
        while ((b = in.read()) != -1 && b != delim) {
            if (count == len) { // expand buffer
                if (count == maxLength)
                    throw new IOException("token too large (" + count + ")");
                len = len > 0 ? 2 * len : 256; // start small, double each expansion
                len = maxLength < len ? maxLength : len;
                byte[] expanded = new byte[len];
                if (buf != null)
                    System.arraycopy(buf, 0, expanded, 0, count);
                buf = expanded;
            }
            buf[count++] = (byte)b;
        }
        if (b < 0 && delim != -1)
            throw new EOFException("unexpected end of stream");
        if (delim == '\n' && count > 0 && buf[count - 1] == '\r')
            count--;
        return count > 0 ? new String(buf, 0, count, enc) : "";
    }

    /**
     * 从当前流的位置开始读取以 ISO-8859-1 编码的字符串，直到第一次遇到 LF 字符为止。
     *
     * @param in 从中读取行的输入流
     * @return 读取的字符串，排除终止的 LF 字符
     *         和（如果存在）紧接其前的 CR 字符
     * @throws EOFException 如果在找到 LF 字符之前到达流的末尾
     * @throws IOException 如果发生输入输出错误，或行的长度超过 8192 字节
     * @see #readToken(InputStream, int, String, int)
     */
    public static String readLine(InputStream in) throws IOException {
        return readToken(in, '\n', "ISO8859_1", 8192);
    }



    /**
     * 将数据从输入流传输到输出流。
     *
     * @param in 要传输的输入流
     * @param out 要传输到的输出流（若为 null 则丢弃输出）
     * @param len 要传输的字节数。若为负数，则传输输入流的全部内容。
     * @throws IOException 若发生 IO 错误，或者在请求的字节数读取完之前输入流结束
     */
    public static int transfer(InputStream in, OutputStream out, long len) throws IOException {
        if (len == 0 || out == null && len < 0 && in.read() < 0)
            return -1; // small optimization - avoid buffer creation
        byte[] buf = new byte[4096];
        int cnt = 0;
        while (len != 0) {
            int count = len < 0 || buf.length < len ? buf.length : (int)len;
            count = in.read(buf, 0, count);
            if (count < 0) {
                if (len > 0)
                    throw new IOException("unexpected end of stream");
                break;
            }
            if (out != null)
                out.write(buf, 0, count);
            len -= len > 0 ? count : 0;
            cnt += count;
        }
        return cnt;
    }

}
