package me.asu.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

public class Bytes {
    /** A convenience array containing the carriage-return and line feed chars. */
    public static final    byte[]              CRLF         = { 0x0d, 0x0a };

    public Bytes() {
    }

    /**
     * Converts strings to bytes by casting the chars to bytes.
     * This is a fast way to encode a string as ISO-8859-1/US-ASCII bytes.
     * If multiple strings are provided, their bytes are concatenated.
     *
     * @param strings the strings to convert (containing only ISO-8859-1 chars)
     * @return the byte array
     */
    public static byte[] getBytes(String... strings) {
        int n = 0;
        for (String s : strings)
            n += s.length();
        byte[] b = new byte[n];
        n = 0;
        for (String s : strings)
            for (int i = 0, len = s.length(); i < len; i++)
                b[n++] = (byte)s.charAt(i);
        return b;
    }


    /**
     * 快速判断是否是空串
     *
     * @param str 文本
     * @return true or false
     */
    public static boolean isEmpty(Object str) {
        return (str == null || "".equals(str.toString().trim()));
    }

    public static String toString(byte[] bytes) {
        if (bytes == null) {
            return "";
        } else {
            try {
                return new String(bytes, "utf-8");
            } catch (UnsupportedEncodingException var2) {
                var2.printStackTrace();
                return "";
            }
        }
    }
    public static byte[] toByteArray(InputStream input) throws IOException {
        if (input != null && input.available() != 0) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            boolean n = false;

            int n1;
            while (-1 != (n1 = input.read(buffer))) {
                baos.write(buffer, 0, n1);
            }

            return baos.toByteArray();
        } else {
            return null;
        }
    }


}
