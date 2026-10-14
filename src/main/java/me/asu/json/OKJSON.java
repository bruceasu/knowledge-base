/*
 * okjson - A small efficient flexible JSON parser/generator for Java
 * author	: calvin
 * email	: calvinwilliams@163.com
 *
 * See the file LICENSE in base directory.
 */

package me.asu.json;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import me.asu.log.Log;

import java.io.IOException;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class OKJSON {
    public static final int OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE = 1;
    public static final int OPTIONS_PRETTY_FORMAT_ENABLE = 2;
    public static final int OPTIONS_STRICT_POLICY = 4;
    public static final int OPTIONS_NULLABLE = 8;

    public static final int OKJSON_ERROR_END_OF_BUFFER = OkJsonParser.OKJSON_ERROR_END_OF_BUFFER;
    public static final int OKJSON_ERROR_UNEXPECT = OkJsonParser.OKJSON_ERROR_UNEXPECT;
    public static final int OKJSON_ERROR_EXCEPTION = OkJsonParser.OKJSON_ERROR_EXCEPTION;
    public static final int OKJSON_ERROR_INVALID_BYTE = OkJsonParser.OKJSON_ERROR_INVALID_BYTE;
    public static final int OKJSON_ERROR_FIND_FIRST_LEFT_BRACE = OkJsonParser.OKJSON_ERROR_FIND_FIRST_LEFT_BRACE;
    public static final int OKJSON_ERROR_NAME_INVALID = OkJsonParser.OKJSON_ERROR_NAME_INVALID;
    public static final int OKJSON_ERROR_EXPECT_COLON_AFTER_NAME = OkJsonParser.OKJSON_ERROR_EXPECT_COLON_AFTER_NAME;
    public static final int OKJSON_ERROR_UNEXPECT_TOKEN_AFTER_LEFT_BRACE =
            OkJsonParser.OKJSON_ERROR_UNEXPECT_TOKEN_AFTER_LEFT_BRACE;
    public static final int OKJSON_ERROR_PORPERTY_TYPE_NOT_MATCH_IN_OBJECT =
            OkJsonParser.OKJSON_ERROR_PORPERTY_TYPE_NOT_MATCH_IN_OBJECT;
    public static final int OKJSON_ERROR_NAME_NOT_FOUND_IN_OBJECT = OkJsonParser.OKJSON_ERROR_NAME_NOT_FOUND_IN_OBJECT;
    public static final int OKJSON_ERROR_NEW_OBJECT = OkJsonParser.OKJSON_ERROR_NEW_OBJECT;

    private static ThreadLocal<OkJsonGenerator> okjsonGeneratorCache = new ThreadLocal<>();
    private static ThreadLocal<OkJsonParser> okjsonParserCache = new ThreadLocal<>();
    ;

    private static ThreadLocal<Integer> errorCode = new ThreadLocal<Integer>();
    private static ThreadLocal<String> errorDesc = new ThreadLocal<String>();

    public static Integer getErrorCode() {
        return errorCode.get();
    }

    public static String getErrorDesc() {
        return errorDesc.get();
    }

    public static int stringify(Object object, String filePath, int options) {
        try {
            String jsonString = stringify(object, options);
            Files.write(Paths.get(filePath), jsonString.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String stringify(Object object, int options) {
        OkJsonGenerator okjsonGenerator;
        if (okjsonGeneratorCache.get() == null) {
            okjsonGenerator = new OkJsonGenerator();
            okjsonGeneratorCache.set(okjsonGenerator);
        } else {
            okjsonGenerator = okjsonGeneratorCache.get();
        }

        Options opt = Options.fromMask(options);
        applyOptions(okjsonGenerator, opt);
        try {
            return okjsonGenerator.stringify(object);
        } catch (Exception e) {
            errorCode.set(OKJSON_ERROR_UNEXPECT);
            errorDesc.set(e.getMessage());
            return null;
        } finally {
            okjsonGeneratorCache.set(null);
        }
    }

    public static <T> T fileToJson(String filePath, Class<T> clazz, int options) {
        String jsonString = null;

        try {
            jsonString = new String(Files.readAllBytes(Paths.get(filePath)));
        } catch (IOException e) {
            return null;
        }

        return toJson(jsonString, clazz, options);
    }
    private static OkJsonParser getOkJsonParserApplied(Options opt) {
        OkJsonParser p = getOkJsonParser();
        applyOptions(p, opt);
        return p;
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static <T> T fillToSubclassMap(Class<T> clazz, Map<String,Object> src) throws Exception {
        Map m = (Map) newInstance(clazz);
        m.putAll(src);
        return (T) m;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static <T> T fillToSubclassList(Class<T> clazz, List<Object> src) throws Exception {
        List l = (List) newInstance(clazz);
        l.addAll(src);
        return (T) l;
    }
    public static <T> T toJson(String jsonString, Class<T> clazz, int options) {
        Options opt = Options.fromMask(options);
        try {
            if (clazz == Map.class || Map.class.isAssignableFrom(clazz)) {
                Map<String, Object> m = getOkJsonParserApplied(opt).toMap(jsonString);
                return Map.class.equals(clazz) ? (T) m : fillToSubclassMap(clazz, m);
            }
            if (clazz == List.class || List.class.isAssignableFrom(clazz)) {
                List<Object> src = getOkJsonParserApplied(opt).toList(jsonString);
                return List.class.equals(clazz) ? (T) src : fillToSubclassList(clazz, src);
            }
            OkJsonParser p = getOkJsonParserApplied(opt);
            T obj = newInstance(clazz);
            obj = p.toJson(jsonString, obj);
            errorCode.set(p.getErrorCode());
            errorDesc.set(p.getErrorDesc());
            return obj;
        }  catch (Exception e) {
            Log.error(e.getMessage(), e);
            errorCode.set(OKJSON_ERROR_EXCEPTION);
            errorDesc.set(e.getMessage());
            return null;
        }finally {
            okjsonParserCache.set(null);
        }
    }

    public static <T> T toList(String jsonString, int options) {
        final OkJsonParser okjsonParser = getOkJsonParser();
        if (okjsonParser == null) return null;

        // 绑定选项
        okjsonParser.setDirectAccessPropertyEnable((options & OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE) != 0);
        okjsonParser.setStrictPolicyEnable((options & OPTIONS_STRICT_POLICY) != 0);

        List<Object> list = okjsonParser.toList(jsonString);
        errorCode.set(okjsonParser.getErrorCode());
        errorDesc.set(okjsonParser.getErrorDesc());
        return (T) list;
    }

    public static Map<String, Object> toMap(String jsonString, int options) {
        try {
            final OkJsonParser okjsonParser = getOkJsonParser();
            if (okjsonParser == null) return null;

            if ((options & OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE) != 0) {
                okjsonParser.setDirectAccessPropertyEnable(true);
            } else {
                okjsonParser.setDirectAccessPropertyEnable(false);
            }

            if ((options & OPTIONS_STRICT_POLICY) != 0) {
                okjsonParser.setStrictPolicyEnable(true);
            } else {
                okjsonParser.setStrictPolicyEnable(false);
            }

            Map<String, Object> object = okjsonParser.toMap(jsonString);

            errorCode.set(okjsonParser.getErrorCode());
            errorDesc.set(okjsonParser.getErrorDesc());

            return object;
        } finally {
            okjsonParserCache.set(null);
        }
    }

    /**
     * Get OKJsonParser
     *
     * @return {@link OkJsonParser}
     */
    static synchronized OkJsonParser getOkJsonParser() {
        OkJsonParser okjsonParser = okjsonParserCache.get();
        if (okjsonParser == null) {
            okjsonParser = new OkJsonParser();
            okjsonParserCache.set(okjsonParser);
        }

        return okjsonParser;
    }
    // ====== 封装统一应用 ======
    static void applyOptions(OkJsonParser p, Options o) {
        p.setDirectAccessPropertyEnable(o.direct);
        p.setStrictPolicyEnable(o.strict);
    }
    static void applyOptions(OkJsonGenerator g, Options o) {
        g.setDirectAccessPropertyEnable(o.direct);
        g.setPrettyFormatEnable(o.pretty);
        g.setNullEnable(o.nullable);
    }
    // 统一构造
    static <T> T newInstance(Class<T> clazz) throws Exception {
        Constructor<T> c = clazz.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }
}

// ====== 集中管理选项 ======
final class Options {
    final boolean direct;
    final boolean pretty;
    final boolean strict;
    final boolean nullable;

    private Options(boolean direct, boolean pretty, boolean strict, boolean nullable) {
        this.direct = direct; this.pretty = pretty; this.strict = strict; this.nullable = nullable;
    }
    static Options fromMask(int mask) {
        return new Options(
                (mask & OKJSON.OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE) != 0,
                (mask & OKJSON.OPTIONS_PRETTY_FORMAT_ENABLE) != 0,
                (mask & OKJSON.OPTIONS_STRICT_POLICY) != 0,
                (mask & OKJSON.OPTIONS_NULLABLE) != 0
        );
    }
}

@Setter
@Getter
class OkJsonParser {
    private boolean strictPolicyEnable;
    private boolean directAccessPropertyEnable;
    private boolean prettyFormatEnable;

    private Integer errorCode;
    private String errorDesc;

    enum TokenType {
        TOKEN_TYPE_LEFT_BRACE, // {
        TOKEN_TYPE_RIGHT_BRACE, // }
        TOKEN_TYPE_LEFT_BRACKET, // [
        TOKEN_TYPE_RIGHT_BRACKET, // ]
        TOKEN_TYPE_COLON, // :
        TOKEN_TYPE_COMMA, // ,
        TOKEN_TYPE_STRING, // "ABC"
        TOKEN_TYPE_INTEGER, // 123
        TOKEN_TYPE_DECIMAL, // 123.456
        TOKEN_TYPE_BOOL, // true or false
        TOKEN_TYPE_NULL // null
    }

    private static final ThreadLocal<LinkedHashMap<String, LinkedHashMap<String, Field>>> stringMapFieldsCache =
            ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<LinkedHashMap<String, LinkedHashMap<String, Method>>> stringMapMethodsCache =
            ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<StringBuilder> fieldStringBuilderCache =
            ThreadLocal.withInitial(() -> new StringBuilder(1024));

    private int jsonOffset;
    private int jsonLength;

    private TokenType tokenType;
    private int beginOffset;
    private int endOffset;
    private boolean booleanValue;

    public static final int OKJSON_ERROR_END_OF_BUFFER = 1;
    public static final int OKJSON_ERROR_UNEXPECT = -4;
    public static final int OKJSON_ERROR_EXCEPTION = -8;
    public static final int OKJSON_ERROR_INVALID_BYTE = -11;
    public static final int OKJSON_ERROR_FIND_FIRST_LEFT_BRACE = -21;
    public static final int OKJSON_ERROR_NAME_INVALID = -22;
    public static final int OKJSON_ERROR_EXPECT_COLON_AFTER_NAME = -23;
    public static final int OKJSON_ERROR_UNEXPECT_TOKEN_AFTER_LEFT_BRACE = -24;
    public static final int OKJSON_ERROR_PORPERTY_TYPE_NOT_MATCH_IN_OBJECT = -26;
    public static final int OKJSON_ERROR_NAME_NOT_FOUND_IN_OBJECT = -28;
    public static final int OKJSON_ERROR_NEW_OBJECT = -31;

    // 统一构造
    static <T> T newInstance(Class<T> clazz) throws Exception {
        Constructor<T> c = clazz.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private int fail(int code, String msg) {
        this.errorCode = code;
        this.errorDesc = msg;
        return code;
    }

    private int tokenJsonString(char[] jsonCharArray) {
        StringBuilder fieldStringBuilder;
        char ch;

        fieldStringBuilder = fieldStringBuilderCache.get();
        fieldStringBuilder.setLength(0);

        jsonOffset++;
        beginOffset = jsonOffset;
        while (jsonOffset < jsonLength) {
            ch = jsonCharArray[jsonOffset];
            if (ch == '"') {
                tokenType = TokenType.TOKEN_TYPE_STRING;
                if (jsonOffset > beginOffset) {
                    fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset);
                }
                endOffset = jsonOffset - 1;
                jsonOffset++;
                return 0;
            } else if (ch == '\\') {
                jsonOffset++;
                if (jsonOffset >= jsonLength) {
                    return OKJSON_ERROR_END_OF_BUFFER;
                }
                ch = jsonCharArray[jsonOffset];
                if (ch == '"') {
                    if (jsonOffset > beginOffset + 1)
                        fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset - 1);
                    fieldStringBuilder.append('"');
                    beginOffset = jsonOffset + 1;
                } else if (ch == '\\') {
                    if (jsonOffset > beginOffset + 1)
                        fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset - 1);
                    fieldStringBuilder.append("\\");
                    beginOffset = jsonOffset + 1;
                } else if (ch == '/') {
                    if (jsonOffset > beginOffset + 1)
                        fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset - 1);
                    fieldStringBuilder.append('/');
                    beginOffset = jsonOffset + 1;
                } else if (ch == 'b') {
                    if (jsonOffset > beginOffset + 1)
                        fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset - 1);
                    fieldStringBuilder.append('\b');
                    beginOffset = jsonOffset + 1;
                } else if (ch == 'f') {
                    if (jsonOffset > beginOffset + 1)
                        fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset - 1);
                    fieldStringBuilder.append('\f');
                    beginOffset = jsonOffset + 1;
                } else if (ch == 'n') {
                    if (jsonOffset > beginOffset + 1)
                        fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset - 1);
                    fieldStringBuilder.append('\n');
                    beginOffset = jsonOffset + 1;
                } else if (ch == 'r') {
                    if (jsonOffset > beginOffset + 1)
                        fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset - 1);
                    fieldStringBuilder.append('\r');
                    beginOffset = jsonOffset + 1;
                } else if (ch == 't') {
                    if (jsonOffset > beginOffset + 1)
                        fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset - 1);
                    fieldStringBuilder.append('\t');
                    beginOffset = jsonOffset + 1;
                } else if (ch == 'u') {
                    if (jsonOffset + 4 >= jsonLength) return OKJSON_ERROR_END_OF_BUFFER;
                    char h1 = jsonCharArray[++jsonOffset];
                    char h2 = jsonCharArray[++jsonOffset];
                    char h3 = jsonCharArray[++jsonOffset];
                    char h4 = jsonCharArray[++jsonOffset];
                    if (!(isHex(h1) && isHex(h2) && isHex(h3) && isHex(h4))) {
                        errorDesc = "Invalid unicode escape";
                        return OKJSON_ERROR_INVALID_BYTE;
                    }
                    int codePoint = Integer.parseInt(
                            new String(new char[]{h1, h2, h3, h4}), 16);
                    if (fieldStringBuilder.length() == 0) {
                        fieldStringBuilder.append(jsonCharArray, beginOffset, (jsonOffset - 4) - beginOffset - 1);
                    }
                    fieldStringBuilder.append((char) codePoint);
                    beginOffset = jsonOffset + 1;
                } else {
                    fieldStringBuilder.append(jsonCharArray, beginOffset, jsonOffset - beginOffset - 1);
                    fieldStringBuilder.append(ch);
                }
            }

            jsonOffset++;
        }

        return OKJSON_ERROR_END_OF_BUFFER;
    }
    private boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private int tokenJsonNumber(char[] jsonCharArray) {
        char ch;
        boolean decimalPointFlag;

        beginOffset = jsonOffset;

        ch = jsonCharArray[jsonOffset];
        if (ch == '-') {
            jsonOffset++;
        }

        decimalPointFlag = false;
        while (jsonOffset < jsonLength) {
            ch = jsonCharArray[jsonOffset];
            if ('0' <= ch && ch <= '9') {
                jsonOffset++;
            } else if (ch == '.') {
                decimalPointFlag = true;
                jsonOffset++;
            } else if (ch == 'e' || ch == 'E') {
                jsonOffset++;
                if (jsonOffset >= jsonLength) return fail(OKJSON_ERROR_END_OF_BUFFER,"");
                ch = jsonCharArray[jsonOffset];
                if (ch == '-' || ch == '+') {
                    jsonOffset++;
                } else if ('0' <= ch && ch <= '9') {
                    jsonOffset++;
                }
            } else {
                if (decimalPointFlag == true)
                    tokenType = TokenType.TOKEN_TYPE_DECIMAL;
                else
                    tokenType = TokenType.TOKEN_TYPE_INTEGER;
                endOffset = jsonOffset - 1;
                return 0;
            }
        }

        return fail(OKJSON_ERROR_END_OF_BUFFER, "");
    }

    private int tokenJsonWord(char[] jsonCharArray) {
        char ch;

        while (jsonOffset < jsonLength) {
            ch = jsonCharArray[jsonOffset];
            if (ch == ' ' || ch == '\b' || ch == '\t' || ch == '\f' || ch == '\r' || ch == '\n') {
                jsonOffset++;
            } else if (ch == '{') {
                tokenType = TokenType.TOKEN_TYPE_LEFT_BRACE;
                beginOffset = jsonOffset;
                endOffset = jsonOffset;
                jsonOffset++;
                return 0;
            } else if (ch == '}') {
                tokenType = TokenType.TOKEN_TYPE_RIGHT_BRACE;
                beginOffset = jsonOffset;
                endOffset = jsonOffset;
                jsonOffset++;
                return 0;
            } else if (ch == '[') {
                tokenType = TokenType.TOKEN_TYPE_LEFT_BRACKET;
                beginOffset = jsonOffset;
                endOffset = jsonOffset;
                jsonOffset++;
                return 0;
            } else if (ch == ']') {
                tokenType = TokenType.TOKEN_TYPE_RIGHT_BRACKET;
                beginOffset = jsonOffset;
                endOffset = jsonOffset;
                jsonOffset++;
                return 0;
            } else if (ch == '"') {
                return tokenJsonString(jsonCharArray);
            } else if (ch == ':') {
                tokenType = TokenType.TOKEN_TYPE_COLON;
                beginOffset = jsonOffset;
                endOffset = jsonOffset;
                jsonOffset++;
                return 0;
            } else if (ch == ',') {
                tokenType = TokenType.TOKEN_TYPE_COMMA;
                beginOffset = jsonOffset;
                endOffset = jsonOffset;
                jsonOffset++;
                return 0;
            } else if (ch == '-' || ('0' <= ch && ch <= '9')) {
                return tokenJsonNumber(jsonCharArray);
            } else if (ch == 't') {
                beginOffset = jsonOffset;
                jsonOffset++;
                if (jsonOffset >= jsonLength) {
                    return OKJSON_ERROR_END_OF_BUFFER;
                }
                ch = jsonCharArray[jsonOffset];
                if (ch == 'r') {
                    jsonOffset++;
                    if (jsonOffset >= jsonLength) {
                        return OKJSON_ERROR_END_OF_BUFFER;
                    }
                    ch = jsonCharArray[jsonOffset];
                    if (ch == 'u') {
                        jsonOffset++;
                        if (jsonOffset >= jsonLength) {
                            return OKJSON_ERROR_END_OF_BUFFER;
                        }
                        ch = jsonCharArray[jsonOffset];
                        if (ch == 'e') {
                            tokenType = TokenType.TOKEN_TYPE_BOOL;
                            booleanValue = true;
                            endOffset = jsonOffset;
                            jsonOffset++;
                            return 0;
                        }
                    }
                }
            } else if (ch == 'f') {
                beginOffset = jsonOffset;
                jsonOffset++;
                if (jsonOffset >= jsonLength) {
                    return OKJSON_ERROR_END_OF_BUFFER;
                }
                ch = jsonCharArray[jsonOffset];
                if (ch == 'a') {
                    jsonOffset++;
                    if (jsonOffset >= jsonLength) {
                        return OKJSON_ERROR_END_OF_BUFFER;
                    }
                    ch = jsonCharArray[jsonOffset];
                    if (ch == 'l') {
                        jsonOffset++;
                        if (jsonOffset >= jsonLength) {
                            return OKJSON_ERROR_END_OF_BUFFER;
                        }
                        ch = jsonCharArray[jsonOffset];
                        if (ch == 's') {
                            jsonOffset++;
                            if (jsonOffset >= jsonLength) {
                                return OKJSON_ERROR_END_OF_BUFFER;
                            }
                            ch = jsonCharArray[jsonOffset];
                            if (ch == 'e') {
                                tokenType = TokenType.TOKEN_TYPE_BOOL;
                                booleanValue = false;
                                endOffset = jsonOffset;
                                jsonOffset++;
                                return 0;
                            }
                        }
                    }
                }
            } else if (ch == 'n') {
                beginOffset = jsonOffset;
                jsonOffset++;
                if (jsonOffset >= jsonLength) {
                    return OKJSON_ERROR_END_OF_BUFFER;
                }
                ch = jsonCharArray[jsonOffset];
                if (ch == 'u') {
                    jsonOffset++;
                    if (jsonOffset >= jsonLength) {
                        return OKJSON_ERROR_END_OF_BUFFER;
                    }
                    ch = jsonCharArray[jsonOffset];
                    if (ch == 'l') {
                        jsonOffset++;
                        if (jsonOffset >= jsonLength) {
                            return OKJSON_ERROR_END_OF_BUFFER;
                        }
                        ch = jsonCharArray[jsonOffset];
                        if (ch == 'l') {
                            tokenType = TokenType.TOKEN_TYPE_NULL;
                            booleanValue = true;
                            endOffset = jsonOffset;
                            jsonOffset++;
                            return 0;
                        }
                    }
                }
            } else {
                return fail(OKJSON_ERROR_INVALID_BYTE,"Invalid byte '" + ch + "'");
            }
        }

        return fail(OKJSON_ERROR_END_OF_BUFFER, "");
    }

    Object convertTokenTo(
            char[] json, TokenType t, int begin, int end, boolean boolValue,
            @SuppressWarnings("rawtypes") Class target,  Field field) throws Exception {

        if (t == TokenType.TOKEN_TYPE_NULL) return null;

        String s = new String(json, begin, end - begin + 1);
        if (target == String.class) {
            StringBuilder sb = fieldStringBuilderCache.get();
            return (sb.length() > 0) ? sb.toString() : s;
        }
        if (target == Boolean.class || target == boolean.class) {
            return (t == TokenType.TOKEN_TYPE_BOOL) ? Boolean.valueOf(boolValue) : null;
        }
        if (Number.class.isAssignableFrom(target) || target.isPrimitive()) {
            if (t == TokenType.TOKEN_TYPE_INTEGER || t == TokenType.TOKEN_TYPE_DECIMAL) {
                if (target == Byte.class || target == byte.class)    return Byte.valueOf(s);
                if (target == Short.class || target == short.class)  return Short.valueOf(s);
                if (target == Integer.class || target == int.class)  return Integer.valueOf(s);
                if (target == Long.class || target == long.class)    return Long.valueOf(s);
                if (target == Float.class || target == float.class)  return Float.valueOf(s);
                if (target == Double.class || target == double.class)return Double.valueOf(s);
                // 兜底：尽量不丢失精度
                if (t == TokenType.TOKEN_TYPE_INTEGER) return new BigInteger(s);
                return new BigDecimal(s);
            }
            return null;
        }
        if (target == LocalDate.class) {
            String pattern = resolvePattern(field, "yyyy-MM-dd");
            return LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern));
        }
        if (target == LocalTime.class) {
            String pattern = resolvePattern(field, "HH:mm:ss");
            return LocalTime.parse(s, DateTimeFormatter.ofPattern(pattern));
        }
        if (target == LocalDateTime.class) {
            String pattern = resolvePattern(field, "yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern));
        }
        return null; // 其余类型这里不处理
    }

    String resolvePattern(Field field, String def) {
        if (field != null && field.isAnnotationPresent(OkJsonDateTimeFormatter.class)) {
            return field.getAnnotation(OkJsonDateTimeFormatter.class).format();
        }
        return def;
    }

    int addArrayObject(char[] json, TokenType vt, int vb, int ve,
                               Object object, Field field) {

        try {
            Class<?> listType  = field.getType();
            if (!List.class.isAssignableFrom(listType)) {
                return strictPolicyEnable ? fail(OKJSON_ERROR_PORPERTY_TYPE_NOT_MATCH_IN_OBJECT,"") : 0;
            }

            // 取 List<T> 的 T
            Type g = field.getGenericType();
            if (!(g instanceof ParameterizedType)) return 0;
            Class<?> elemType = (Class<?>) ((ParameterizedType) g).getActualTypeArguments()[0];

            Object converted = convertTokenTo(json, vt, vb, ve, booleanValue, elemType, field);
            if (converted == null && vt != TokenType.TOKEN_TYPE_NULL) {
                return strictPolicyEnable ? fail(OKJSON_ERROR_PORPERTY_TYPE_NOT_MATCH_IN_OBJECT,"") : 0;
            }
            ((List<Object>) object).add(converted);
            return 0;

        } catch (Exception e) {
            Log.error(e.getMessage(), e);
            return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
        }

    }

    int stringToArrayObject(char[] jsonCharArray, Object object, Field field) {

        TokenType valueTokenType;
        int valueBeginOffset;
        int valueEndOffset;

        int nret;

        while (true) {
            // token "value" or '{'
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) {
                break;
            }
            if (nret != 0) {
                return nret;
            }

            if (tokenType == TokenType.TOKEN_TYPE_LEFT_BRACE) {
                try {
                    if (field != null) {
                        Class<?> clazz = field.getType();
                        if (clazz == ArrayList.class || clazz == LinkedList.class) {
                            Type type = field.getGenericType();
                            ParameterizedType pt = (ParameterizedType) type;
                            Class<?> typeClazz = (Class<?>) pt.getActualTypeArguments()[0];
                            Object childObject = newInstance(typeClazz);
                            nret = stringToObjectProperties(jsonCharArray, childObject);
                            if (nret != 0)
                                return nret;

                            ((List<Object>) object).add(childObject);
                        }
                    } else {
                        nret = stringToObjectProperties(jsonCharArray, null);
                        if (nret != 0)
                            return nret;
                    }
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            } else if (isTokenValueType(tokenType)) {
                ;
            } else {
                int beginPos = endOffset - 16;
                if (beginPos < 0)
                    beginPos = 0;
                return fail(OKJSON_ERROR_UNEXPECT_TOKEN_AFTER_LEFT_BRACE,
                        "unexpect \"" + String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1) + "\"");
            }

            valueTokenType = tokenType;
            valueBeginOffset = beginOffset;
            valueEndOffset = endOffset;

            // token ',' or ']'
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) {
                break;
            }
            if (nret != 0) {
                return nret;
            }

            if (tokenType == TokenType.TOKEN_TYPE_COMMA || tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACKET) {
                if (object != null && field != null) {
                    errorCode = addArrayObject(jsonCharArray, valueTokenType, valueBeginOffset, valueEndOffset,
                            object, field);
                    if (errorCode != 0)
                        return errorCode;
                }

                if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACKET)
                    break;
            } else {
                return fail(OKJSON_ERROR_UNEXPECT_TOKEN_AFTER_LEFT_BRACE,
                        "unexpect \"" + String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1) + "\"");
            }
        }

        return 0;
    }

    private int setObjectProperty(char[] jsonCharArray, TokenType valueTokenType, int valueBeginOffset,
                                  int valueEndOffset, Object object, Field field, Method method) {

        StringBuilder fieldStringBuilder;

        fieldStringBuilder = fieldStringBuilderCache.get();

        if (field.getType() == String.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_STRING) {
                try {
                    setString(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method,
                            fieldStringBuilder);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType() == Byte.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
                try {
                    setByte(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType() == Short.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
                try {
                    setShort(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType() == Integer.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
                try {
                    setInteger(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType() == Long.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
                try {
                    setLong(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType() == Float.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_DECIMAL) {
                try {
                    setFloat(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType() == Double.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_DECIMAL) {
                try {
                    setDouble(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType() == Boolean.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_BOOL) {
                try {
                    setBoolean(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType().getName().equals("byte") && valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
            try {
                setByte2(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
            }
        } else if (field.getType().getName().equals("short") && valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
            try {
                setShort2(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
            }
        } else if (field.getType().getName().equals("int") && valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
            try {
                setInteger2(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
            }
        } else if (field.getType().getName().equals("long") && valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
            try {
                setLong2(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
            }
        } else if (field.getType().getName().equals("float") && valueTokenType == TokenType.TOKEN_TYPE_DECIMAL) {
            try {
                setFloat2(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
            }
        } else if (field.getType().getName().equals("double") && valueTokenType == TokenType.TOKEN_TYPE_DECIMAL) {
            try {
                setDouble2(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
            }
        } else if (field.getType().getName().equals("boolean") && valueTokenType == TokenType.TOKEN_TYPE_BOOL) {
            try {
                setBoolean2(object, field, method);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
            }
        } else if (field.getType() == LocalDate.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_STRING) {
                try {
                    setLocalDate(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method,
                            fieldStringBuilder);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType() == LocalTime.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_STRING) {
                try {
                    setLoadTime(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method,
                            fieldStringBuilder);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (field.getType() == LocalDateTime.class) {
            if (valueTokenType == TokenType.TOKEN_TYPE_STRING) {
                try {
                    setLocalDateTime(jsonCharArray, valueBeginOffset, valueEndOffset, object, field, method,
                            fieldStringBuilder);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
                }
            }
        } else if (Enum.class.isAssignableFrom(field.getType()) && valueTokenType == TokenType.TOKEN_TYPE_STRING) {
            try {
                setEnum(object, field, method, fieldStringBuilder);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
            }
        } else if (valueTokenType == TokenType.TOKEN_TYPE_NULL) {
            try {
                setNull(object, field, method);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return fail(OKJSON_ERROR_EXCEPTION, e.getMessage());
            }
        } else {
            if (strictPolicyEnable) return fail(OKJSON_ERROR_PORPERTY_TYPE_NOT_MATCH_IN_OBJECT,"");
        }

        return 0;
    }

    private void setNull(Object object, Field field, Method method) throws IllegalAccessException,
            InvocationTargetException {
        if (method != null) {
            method.invoke(object, (Object) null);
        } else if (directAccessPropertyEnable) {
            field.set(object, null);
        }
    }

    @SuppressWarnings("unchecked")
    private void setEnum(Object object, Field field, Method method, StringBuilder fieldStringBuilder)
            throws IllegalAccessException, InvocationTargetException {
        Object o = Enum.valueOf(((Class<Enum>) field.getType()), fieldStringBuilder.toString());
        if (method != null) {
            method.invoke(object, o);
        } else if (directAccessPropertyEnable) {
            field.set(object, o);
        }
    }

    private void setLocalDateTime(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object,
                                  Field field, Method method, StringBuilder fieldStringBuilder)
            throws IllegalAccessException, InvocationTargetException {
        OkJsonDateTimeFormatter okjsonDateTimeFormatter;
        String defaultDateTimeFormatter;
        LocalDateTime localDateTime;
        if (field.isAnnotationPresent(OkJsonDateTimeFormatter.class)) {
            okjsonDateTimeFormatter = field.getAnnotation(OkJsonDateTimeFormatter.class);
            defaultDateTimeFormatter = okjsonDateTimeFormatter.format();
        } else {
            defaultDateTimeFormatter = "yyyy-MM-dd HH:mm:ss";
        }
        if (fieldStringBuilder.length() > 0) {
            localDateTime = LocalDateTime.parse(fieldStringBuilder.toString(),
                    DateTimeFormatter.ofPattern(defaultDateTimeFormatter));
        } else {
            localDateTime = LocalDateTime.parse(new String(jsonCharArray, valueBeginOffset,
                    valueEndOffset - valueBeginOffset +
                            1), DateTimeFormatter.ofPattern(defaultDateTimeFormatter));
        }
        if (method != null) {
            method.invoke(object, localDateTime);
        } else if (directAccessPropertyEnable) {
            field.set(object, localDateTime);
        }
    }

    private void setLoadTime(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object,
                             Field field, Method method, StringBuilder fieldStringBuilder) throws IllegalAccessException,
            InvocationTargetException {
        OkJsonDateTimeFormatter okjsonDateTimeFormatter;
        String defaultDateTimeFormatter;
        LocalTime localTime;
        if (field.isAnnotationPresent(OkJsonDateTimeFormatter.class)) {
            okjsonDateTimeFormatter = field.getAnnotation(OkJsonDateTimeFormatter.class);
            defaultDateTimeFormatter = okjsonDateTimeFormatter.format();
        } else {
            defaultDateTimeFormatter = "HH:mm:ss";
        }
        if (fieldStringBuilder.length() > 0) {
            localTime = LocalTime.parse(fieldStringBuilder.toString(),
                    DateTimeFormatter.ofPattern(defaultDateTimeFormatter));
        } else {
            localTime = LocalTime.parse(new String(jsonCharArray, valueBeginOffset,
                    valueEndOffset - valueBeginOffset +
                            1), DateTimeFormatter.ofPattern(defaultDateTimeFormatter));
        }
        if (method != null) {
            method.invoke(object, localTime);
        } else if (directAccessPropertyEnable) {
            field.set(object, localTime);
        }
    }

    private void setLocalDate(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object,
                              Field field, Method method, StringBuilder fieldStringBuilder) throws IllegalAccessException,
            InvocationTargetException {
        OkJsonDateTimeFormatter okjsonDateTimeFormatter;
        String defaultDateTimeFormatter;
        LocalDate localDate;
        if (field.isAnnotationPresent(OkJsonDateTimeFormatter.class)) {
            okjsonDateTimeFormatter = field.getAnnotation(OkJsonDateTimeFormatter.class);
            defaultDateTimeFormatter = okjsonDateTimeFormatter.format();
        } else {
            defaultDateTimeFormatter = "yyyy-MM-dd";
        }
        if (fieldStringBuilder.length() > 0) {
            localDate = LocalDate.parse(fieldStringBuilder.toString(),
                    DateTimeFormatter.ofPattern(defaultDateTimeFormatter));
        } else {
            localDate = LocalDate.parse(new String(jsonCharArray, valueBeginOffset,
                    valueEndOffset - valueBeginOffset +
                            1), DateTimeFormatter.ofPattern(defaultDateTimeFormatter));
        }
        if (method != null) {
            method.invoke(object, localDate);
        } else if (directAccessPropertyEnable) {
            field.set(object, localDate);
        }
    }

    private void setBoolean2(Object object, Field field, Method method) throws IllegalAccessException,
            InvocationTargetException {
        if (method != null) {
            method.invoke(object, booleanValue);
        } else if (directAccessPropertyEnable) {
            field.setBoolean(object, booleanValue);
        }
    }

    private void setDouble2(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object,
                            Field field, Method method) throws IllegalAccessException, InvocationTargetException {
        double value = Double.valueOf(new String(jsonCharArray, valueBeginOffset,
                valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.setDouble(object, value);
        }
    }

    private void setFloat2(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field
            , Method method) throws IllegalAccessException, InvocationTargetException {
        float value = Float.valueOf(new String(jsonCharArray, valueBeginOffset,
                valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.setFloat(object, value);
        }
    }

    private void setLong2(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field,
                          Method method) throws IllegalAccessException, InvocationTargetException {
        long value =
                Long.valueOf(new String(jsonCharArray, valueBeginOffset, valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.setLong(object, value);
        }
    }

    private void setInteger2(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object,
                             Field field, Method method) throws IllegalAccessException, InvocationTargetException {
        int value = Integer.valueOf(new String(jsonCharArray, valueBeginOffset,
                valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.setInt(object, value);
        }
    }

    private void setShort2(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field
            , Method method) throws IllegalAccessException, InvocationTargetException {
        short value = Integer.valueOf(new String(jsonCharArray, valueBeginOffset,
                valueEndOffset - valueBeginOffset + 1)).shortValue();
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.setShort(object, value);
        }
    }

    private void setByte2(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field,
                          Method method) throws IllegalAccessException, InvocationTargetException {
        byte value = Integer.valueOf(new String(jsonCharArray, valueBeginOffset,
                valueEndOffset - valueBeginOffset + 1)).byteValue();
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.setByte(object, value);
        }
    }

    private void setBoolean(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object,
                            Field field, Method method) throws IllegalAccessException, InvocationTargetException {
        Boolean value = Boolean.valueOf(new String(jsonCharArray, valueBeginOffset,
                valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.set(object, value);
        }
    }

    private void setDouble(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field
            , Method method) throws IllegalAccessException, InvocationTargetException {
        Double value = Double.valueOf(new String(jsonCharArray, valueBeginOffset,
                valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.set(object, value);
        }
    }

    private void setFloat(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field,
                          Method method) throws IllegalAccessException, InvocationTargetException {
        Float value = Float.valueOf(new String(jsonCharArray, valueBeginOffset,
                valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.set(object, value);
        }
    }

    private void setLong(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field,
                         Method method) throws IllegalAccessException, InvocationTargetException {
        Long value = Long.valueOf(new String(jsonCharArray, valueBeginOffset, valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.set(object, value);
        }
    }

    private void setInteger(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object,
                            Field field, Method method) throws IllegalAccessException, InvocationTargetException {
        Integer value = Integer.valueOf(new String(jsonCharArray, valueBeginOffset,
                valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.set(object, value);
        }
    }

    private void setShort(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field,
                          Method method) throws IllegalAccessException, InvocationTargetException {
        Short value = Short.valueOf(new String(jsonCharArray, valueBeginOffset, valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.set(object, value);
        }
    }

    private void setByte(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field,
                         Method method) throws IllegalAccessException, InvocationTargetException {
        Byte value = Byte.valueOf(new String(jsonCharArray, valueBeginOffset, valueEndOffset - valueBeginOffset + 1));
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.set(object, value);
        }
    }

    private void setString(char[] jsonCharArray, int valueBeginOffset, int valueEndOffset, Object object, Field field
            , Method method, StringBuilder fieldStringBuilder) throws IllegalAccessException,
            InvocationTargetException {
        String value;
        if (fieldStringBuilder.length() > 0) {
            value = fieldStringBuilder.toString();
        } else {
            value = new String(jsonCharArray, valueBeginOffset, valueEndOffset - valueBeginOffset + 1);
        }
        if (method != null) {
            method.invoke(object, value);
        } else if (directAccessPropertyEnable) {
            field.set(object, value);
        }
    }

    private int stringToObjectProperties(char[] jsonCharArray, Object object) {

        Class clazz;
        LinkedHashMap<String, Field> stringMapFields;
        LinkedHashMap<String, Method> stringMapMethods;
        Field[] fields;
        Field field;
        Method method = null;
        TokenType fieldNameTokenType;
        int fieldNameBeginOffset;
        int fieldNameEndOffset;
        String fieldName;
        TokenType valueTokenType;
        int valueBeginOffset;
        int valueEndOffset;

        int nret;

        if (object != null) {
            clazz = object.getClass();

            stringMapFields = stringMapFieldsCache.get().get(clazz.getName());
            if (stringMapFields == null) {
                stringMapFields = new LinkedHashMap<String, Field>();
                stringMapFieldsCache.get().put(clazz.getName(), stringMapFields);
            }

            stringMapMethods = stringMapMethodsCache.get().get(clazz.getName());
            if (stringMapMethods == null) {
                stringMapMethods = new LinkedHashMap<String, Method>();
                stringMapMethodsCache.get().put(clazz.getName(), stringMapMethods);
            }

            if (stringMapFields.isEmpty()) {
                fields = clazz.getDeclaredFields();
                for (Field f : fields) {
                    f.setAccessible(true);

                    fieldName = f.getName();

                    method = null;
                    try {
                        method = clazz.getMethod("set" + fieldName.substring(0, 1).toUpperCase(Locale.getDefault()) +
                                fieldName.substring(1), f.getType());
                        method.setAccessible(true);
                    } catch (NoSuchMethodException e2) {
                        ;
                    } catch (SecurityException e2) {
                        ;
                    }


                    if (method != null && Modifier.isPublic(method.getModifiers())) {
                        stringMapMethods.put(fieldName, method);
                        stringMapFields.put(fieldName, f);
                    } else if (Modifier.isPublic(f.getModifiers())) {
                        stringMapFields.put(fieldName, f);
                    }
                }
            }
        } else {
            stringMapFields = null;
            stringMapMethods = null;
        }

        while (true) {
            // token "name"
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) {
                break;
            }
            if (nret != 0) {
                return nret;
            }

            fieldNameTokenType = tokenType;
            fieldNameBeginOffset = beginOffset;
            fieldNameEndOffset = endOffset;
            fieldName = new String(jsonCharArray, fieldNameBeginOffset, fieldNameEndOffset - fieldNameBeginOffset + 1);

            if (object != null) {
                field = stringMapFields.get(fieldName);
                if (field == null) {
                    if (strictPolicyEnable == true)
                        return OKJSON_ERROR_NAME_NOT_FOUND_IN_OBJECT;
                }

                method = stringMapMethods.get(fieldName);
            } else {
                field = null;
                method = null;
            }

            if (tokenType != TokenType.TOKEN_TYPE_STRING) {
                String found = String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1);
                errorDesc = "expect a name but \"" + found + "\"";
                return OKJSON_ERROR_NAME_INVALID;
            }

            // token ':' or ',' or '}' or ']'
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) break;
            if (nret != 0) return nret;

            if (tokenType == TokenType.TOKEN_TYPE_COLON) {
                ;
            } else if (tokenType == TokenType.TOKEN_TYPE_COMMA || tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACE) {
                clazz = field.getType();
                if (clazz == ArrayList.class || clazz == LinkedList.class) {
                    nret = addArrayObject(jsonCharArray, fieldNameTokenType, fieldNameBeginOffset, fieldNameEndOffset
                            , object, field);

                    if (nret != 0) return nret;

                    if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACE) break;
                }
            } else if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACKET) {
                break;
            } else {
                String found = String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1);
                errorDesc = "expect ':' but \"" + found + "\"";
                return OKJSON_ERROR_EXPECT_COLON_AFTER_NAME;
            }

            // token '{' or '[' or "value"
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) break;
            if (nret != 0) return nret;

            valueTokenType = tokenType;
            valueBeginOffset = beginOffset;
            valueEndOffset = endOffset;

            if (tokenType == TokenType.TOKEN_TYPE_LEFT_BRACE || tokenType == TokenType.TOKEN_TYPE_LEFT_BRACKET) {
                try {
                    Object childObject;

                    if (field != null) {
                        childObject = newInstance(field.getType());
                        if (childObject == null)
                            return OKJSON_ERROR_UNEXPECT;
                    } else {
                        childObject = null;
                    }

                    if (tokenType == TokenType.TOKEN_TYPE_LEFT_BRACE) {
                        nret = stringToObjectProperties(jsonCharArray, childObject);
                    } else {
                        nret = stringToArrayObject(jsonCharArray, childObject, field);
                    }
                    if (nret != 0)
                        return nret;

                    if (field != null) {
                        field.set(object, childObject);
                    }
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return OKJSON_ERROR_EXCEPTION;
                }
            } else {
                if (object != null && field != null) {
                    nret = setObjectProperty(jsonCharArray, valueTokenType, valueBeginOffset, valueEndOffset, object,
                            field, method);
                    if (nret != 0)
                        return nret;
                }
            }

            // token ',' or '}' or ']'
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) break;
            if (nret != 0) return nret;

            if (tokenType == TokenType.TOKEN_TYPE_COMMA) {
                ;
            } else if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACE) {
                break;
            } else if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACKET) {
                break;
            } else {
                String found = String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1);
                errorDesc = "expect ',' or '}' or ']' but \"" + found + "\"";
                return OKJSON_ERROR_EXPECT_COLON_AFTER_NAME;
            }
        }

        return 0;
    }

    private int addArrayMap(String value, TokenType valueTokenType, List<Object> list) {
        try {
            if (valueTokenType == TokenType.TOKEN_TYPE_STRING) {
                StringBuilder sb = fieldStringBuilderCache.get();
                list.add((sb != null && sb.length() > 0) ? sb.toString() : value);
                sb.setLength(0); // 清空，避免后续误用旧内容
            } else if (valueTokenType == TokenType.TOKEN_TYPE_NULL) {
                list.add(null);
            } else if (valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
                BigInteger v = new BigInteger(value);
                list.add(v);
            } else if (valueTokenType == TokenType.TOKEN_TYPE_DECIMAL) {
                BigDecimal v = new BigDecimal(value);
                list.add(v);
            } else if (valueTokenType == TokenType.TOKEN_TYPE_BOOL) {
                list.add(booleanValue);
            }
        } catch (Exception e) {
            Log.error(e.getMessage(), e);
            return OKJSON_ERROR_EXCEPTION;
        }

        return 0;
    }

    private int stringToArrayMap(char[] jsonCharArray, List<Object> list) {

        TokenType valueTokenType;
        int valueBeginOffset;
        int valueEndOffset;

        int nret;

        while (true) {
            // 读到一个元素：值 / 对象 / 或者子数组
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) break;
            if (nret != 0) return nret;

            if (tokenType == TokenType.TOKEN_TYPE_LEFT_BRACE) {
                // 子对象
                try {
                    Map<String, Object> childObject = new LinkedHashMap<>();
                    nret = toMapItems(jsonCharArray, childObject);
                    if (nret != 0) return nret;

                    list.add(childObject);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return OKJSON_ERROR_EXCEPTION;
                }
            } else if (tokenType == TokenType.TOKEN_TYPE_LEFT_BRACKET) {
                // 子数组（递归）
                try {
                    List<Object> childList = new LinkedList<>();
                    nret = stringToArrayMap(jsonCharArray, childList);
                    if (nret != 0) return nret;
                    list.add(childList);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return OKJSON_ERROR_EXCEPTION;
                }
            }else if (isTokenValueType(tokenType)) {
                // 原始值（数字/布尔/字符串）
                ;
            } else {
                int beginPos = endOffset - 16; // ? 看不懂
                if (beginPos < 0) beginPos = 0;
                String found = String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1);
                errorDesc = "unexpect \"" + found + "\"";
                return OKJSON_ERROR_UNEXPECT_TOKEN_AFTER_LEFT_BRACE;
            }

            valueTokenType = tokenType;
            valueBeginOffset = beginOffset;
            valueEndOffset = endOffset;

            // token ',' or ']'
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) break;
            if (nret != 0) return nret;

            if (tokenType == TokenType.TOKEN_TYPE_COMMA ||
                    tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACKET) {
                // 只有当本元素是“值类型”时，才在这里补充 list.add(...)
                // （对象/子数组已经在上面提前 add 过了）
                if (list != null && isTokenValueType(valueTokenType)) {
                    String value = new String(jsonCharArray, valueBeginOffset, valueEndOffset - valueBeginOffset + 1);

                    errorCode = addArrayMap(value, valueTokenType, list);

                    if (errorCode != 0) return errorCode;
                }

                if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACKET) break;
            } else {
                String found = String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1);
                errorDesc = "unexpect \"" + found + "\"";
                return OKJSON_ERROR_UNEXPECT_TOKEN_AFTER_LEFT_BRACE;
            }
        }

        return 0;
    }

    static boolean isTokenValueType(TokenType valueTokenType) {
        return valueTokenType == TokenType.TOKEN_TYPE_STRING ||
                valueTokenType == TokenType.TOKEN_TYPE_INTEGER ||
                valueTokenType == TokenType.TOKEN_TYPE_DECIMAL ||
                valueTokenType == TokenType.TOKEN_TYPE_BOOL ||
                valueTokenType == TokenType.TOKEN_TYPE_NULL
                ;
    }

    private int setMapItem(String value, TokenType valueTokenType, Map<String, Object> object, String field) {

        StringBuilder fieldStringBuilder;

        fieldStringBuilder = fieldStringBuilderCache.get();

        if (valueTokenType == TokenType.TOKEN_TYPE_STRING) {
            try {
                object.put(field, fieldStringBuilder.length() > 0 ? fieldStringBuilder.toString() : value);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return OKJSON_ERROR_EXCEPTION;
            }
        } else if (valueTokenType == TokenType.TOKEN_TYPE_INTEGER) {
            try {
                // json 应该不会有很大的整数，用Long也足够了。
                BigInteger v = new BigInteger(value);
                object.put(field, v);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return OKJSON_ERROR_EXCEPTION;
            }
        } else if (valueTokenType == TokenType.TOKEN_TYPE_DECIMAL) {
            try {
                BigDecimal v = new BigDecimal(value);
                object.put(field, v);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return OKJSON_ERROR_EXCEPTION;
            }
        } else if (valueTokenType == TokenType.TOKEN_TYPE_BOOL) {
            try {
                Boolean v = Boolean.valueOf(value);
                object.put(field, v);
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
                return OKJSON_ERROR_EXCEPTION;
            }
        } else if (valueTokenType == TokenType.TOKEN_TYPE_NULL) {
            // 不是每个Map都支持null, 这里简单忽略
        }


        return 0;
    }

    int toMapItems(char[] jsonCharArray, Map<String, Object> map) {

        TokenType fieldNameTokenType;
        int fieldNameBeginOffset;
        int fieldNameEndOffset;
        String fieldName;

        TokenType valueTokenType;
        int valueBeginOffset;
        int valueEndOffset;

        int nret;

        while (true) {
            // token "name"
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) break;
            if (nret != 0) return nret;

            fieldNameTokenType = tokenType;
            fieldNameBeginOffset = beginOffset;
            fieldNameEndOffset = endOffset;
            fieldName = new String(jsonCharArray, fieldNameBeginOffset, fieldNameEndOffset - fieldNameBeginOffset + 1);


            if (tokenType != TokenType.TOKEN_TYPE_STRING) {
                String found = String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1);
                errorDesc = "expect a name but \"" + found + "\"";
                return OKJSON_ERROR_NAME_INVALID;
            }

            // token ':' or ',' or '}' or ']'
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) break;
            if (nret != 0) return nret;

            if (tokenType == TokenType.TOKEN_TYPE_COLON) {
                ;
            } else if (tokenType == TokenType.TOKEN_TYPE_COMMA || tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACE) {
                String value = new String(jsonCharArray, fieldNameBeginOffset,
                        fieldNameEndOffset - fieldNameBeginOffset + 1);
                nret = setMapItem(value, fieldNameTokenType, map, fieldName);
                if (nret != 0) return nret;

                if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACE) break;
            } else if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACKET) {
                break;
            } else {
                String found = String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1);
                errorDesc = "expect ':' but \"" + found + "\"";
                return OKJSON_ERROR_EXPECT_COLON_AFTER_NAME;
            }

            // token '{' or '[' or "value"
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) break;
            if (nret != 0) return nret;

            valueTokenType = tokenType;
            valueBeginOffset = beginOffset;
            valueEndOffset = endOffset;

            if (tokenType == TokenType.TOKEN_TYPE_LEFT_BRACE) {
                try {
                    // sub map
                    Map<String, Object> childObject = new LinkedHashMap<>();
                    ;
                    nret = toMapItems(jsonCharArray, (Map<String, Object>) childObject);
                    if (nret != 0) return nret;
                    map.put(fieldName, childObject);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return OKJSON_ERROR_EXCEPTION;
                }
            } else if (tokenType == TokenType.TOKEN_TYPE_LEFT_BRACKET) {
                try {
                    //  sub List
                    List<Object> childObject = new LinkedList<>();
                    nret = stringToArrayMap(jsonCharArray, childObject);
                    if (nret != 0) return nret;
                    map.put(fieldName, childObject);
                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                    return OKJSON_ERROR_EXCEPTION;
                }
            } else {
                String value = new String(jsonCharArray, valueBeginOffset, valueEndOffset - valueBeginOffset + 1);
                nret = setMapItem(value, valueTokenType, map, fieldName);
                if (nret != 0) return nret;
            }

            // token ',' or '}' or ']'
            nret = tokenJsonWord(jsonCharArray);
            if (nret == OKJSON_ERROR_END_OF_BUFFER) break;
            if (nret != 0) return nret;

            if (tokenType == TokenType.TOKEN_TYPE_COMMA) {
                ;
            } else if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACE) {
                break;
            } else if (tokenType == TokenType.TOKEN_TYPE_RIGHT_BRACKET) {
                break;
            } else {
                String found = String.copyValueOf(jsonCharArray, beginOffset, endOffset - beginOffset + 1);
                errorDesc = "expect ',' or '}' or ']' but \"" + found + "\"";
                return OKJSON_ERROR_EXPECT_COLON_AFTER_NAME;
            }
        }

        return 0;
    }

    public <T> T fileToObject(String filePath, T object) {
        String jsonString = null;

        try {
            jsonString = new String(Files.readAllBytes(Paths.get(filePath)));
        } catch (IOException e) {
            return null;
        }

        return toJson(jsonString, object);
    }

    public <T> T toJson(String jsonString, T object) {
        char[] jsonCharArray;

        jsonCharArray = jsonString.toCharArray();
        jsonOffset = 0;
        jsonLength = jsonCharArray.length;

        errorCode = tokenJsonWord(jsonCharArray);
        if (errorCode != 0) {
            return null;
        }

        if (tokenType != TokenType.TOKEN_TYPE_LEFT_BRACE) {
            errorCode = OKJSON_ERROR_FIND_FIRST_LEFT_BRACE;
            return null;
        }

        errorCode = stringToObjectProperties(jsonCharArray, object);
        if (errorCode != 0)
            return null;

        return object;
    }

    public Map<String, Object> toMap(String jsonString) {
        char[] jsonCharArray;

        jsonCharArray = jsonString.toCharArray();
        jsonOffset = 0;
        jsonLength = jsonCharArray.length;

        errorCode = tokenJsonWord(jsonCharArray);
        if (errorCode != 0) {
            return null;
        }

        if (tokenType != TokenType.TOKEN_TYPE_LEFT_BRACE) {
            errorCode = OKJSON_ERROR_FIND_FIRST_LEFT_BRACE;
            return null;
        }

        Map<String, Object> object = new LinkedHashMap<>();
        errorCode = toMapItems(jsonCharArray, object);

        if (errorCode != 0) return null;

        return object;
    }

    public Map<String, Object> fileToMap(String filePath) {
        String jsonString = null;

        try {
            jsonString = new String(Files.readAllBytes(Paths.get(filePath)));
        } catch (IOException e) {
            return null;
        }

        return toMap(jsonString);
    }
    public List<Object> toList(String jsonString) {
        char[] jsonCharArray = jsonString.toCharArray();
        jsonOffset = 0;
        jsonLength = jsonCharArray.length;

        errorCode = tokenJsonWord(jsonCharArray);
        if (errorCode != 0) return null;

        if (tokenType != TokenType.TOKEN_TYPE_LEFT_BRACKET) {
            errorDesc = "expect '['";
            errorCode = OKJSON_ERROR_UNEXPECT; // 也可按需新增专门错误码
            return null;
        }

        List<Object> list = new LinkedList<>();
        errorCode = stringToArrayMap(jsonCharArray, list);
        if (errorCode != 0) return null;

        return list;
    }

    public OkJsonParser() {
        this.strictPolicyEnable = false;
        this.directAccessPropertyEnable = false;
        this.prettyFormatEnable = false;
        this.errorCode = 0;
        this.errorDesc = null;
    }

    public boolean isStrictPolicyEnable() {
        return strictPolicyEnable;
    }

    public void setStrictPolicyEnable(boolean strictPolicyEnable) {
        this.strictPolicyEnable = strictPolicyEnable;
    }

    public boolean isDirectAccessPropertyEnable() {
        return directAccessPropertyEnable;
    }

    public void setDirectAccessPropertyEnable(boolean directAccessPropertyEnable) {
        this.directAccessPropertyEnable = directAccessPropertyEnable;
    }

    public boolean isPrettyFormatEnable() {
        return prettyFormatEnable;
    }

    public void setPrettyFormatEnable(boolean prettyFormatEnable) {
        this.prettyFormatEnable = prettyFormatEnable;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public String getErrorDesc() {
        return errorDesc;
    }
}

@Getter
@Setter
class OkJsonGenerator {
    @Data
    static class OkJsonClassField {
        char[] fieldName;
        ClassFieldType type;
        Field field;
        Method getter;
        OkJsonDateTimeFormatter okjsonDateTimeFormatter;

        @SuppressWarnings("unchecked")
        <T> T getFieldValue(Object object)
                throws Exception {
            T value = null;
            if (getter != null) {
                value = (T) (getter.invoke(object));
            } else {
                value = (T) (field.get(object));
            }
            return value;
        }
    }

    enum ClassFieldType {
        STRING,
        SCALAR,
        LOCAL_DATE,
        LOCAL_TIME,
        LOCAL_DATE_TIME,
        LIST,
        ARRAY,
        SUBCLASS,
        MAP,
        ENUM,
    }

    private static final ThreadLocal<LinkedHashMap<String, LinkedList<OkJsonClassField>>>
            classMapFieldListCache = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<OkJsonCharArrayBuilder>
            jsonByteArrayBuilderCache = ThreadLocal.withInitial(() -> new OkJsonCharArrayBuilder(1024));
    private static final ThreadLocal<OkJsonCharArrayBuilder>
            fieldByteArrayBuilderCache = ThreadLocal.withInitial(() -> new OkJsonCharArrayBuilder(1024));
    private static final ThreadLocal<LinkedHashMap<Class<?>, Boolean>>
            basicTypeClassMapBooleanCache = ThreadLocal.withInitial(OkJsonGenerator::createBasicTypes);

    static final char SEP_FIELD_CHAR = ',';
    static final char[] SEP_FIELD_CHAR_PRETTY = ",\n".toCharArray();
    static final String NULL_STRING = "null";

    static LinkedHashMap<Class<?>, Boolean> createBasicTypes() {
        LinkedHashMap<Class<?>, Boolean> basicTypeClassMapString = new LinkedHashMap<>();
        basicTypeClassMapString.put(String.class, Boolean.TRUE);
        basicTypeClassMapString.put(Byte.class, Boolean.TRUE);
        basicTypeClassMapString.put(Short.class, Boolean.TRUE);
        basicTypeClassMapString.put(Integer.class, Boolean.TRUE);
        basicTypeClassMapString.put(Long.class, Boolean.TRUE);
        basicTypeClassMapString.put(Float.class, Boolean.TRUE);
        basicTypeClassMapString.put(Double.class, Boolean.TRUE);
        basicTypeClassMapString.put(Boolean.class, Boolean.TRUE);
        basicTypeClassMapString.put(LocalDate.class, Boolean.TRUE);
        basicTypeClassMapString.put(LocalTime.class, Boolean.TRUE);
        basicTypeClassMapString.put(LocalDateTime.class, Boolean.TRUE);
        basicTypeClassMapString.put(BigInteger.class, Boolean.TRUE);
        basicTypeClassMapString.put(BigDecimal.class, Boolean.TRUE);
        return basicTypeClassMapString;
    }

    static void setClassType(Class<?> type, OkJsonClassField classField) {
        if (type == null) return;

        LinkedHashMap<Class<?>, Boolean> basicTypeClassMapBoolean = basicTypeClassMapBooleanCache.get();

        if (type == String.class) classField.type = ClassFieldType.STRING;
        else if (type.isArray()) classField.type = ClassFieldType.ARRAY;
        else if (type == LocalDate.class) classField.type = ClassFieldType.LOCAL_DATE;
        else if (type == LocalTime.class) classField.type = ClassFieldType.LOCAL_TIME;
        else if (type == LocalDateTime.class) classField.type = ClassFieldType.LOCAL_DATE_TIME;
        else if (List.class.isAssignableFrom(type)) classField.type = ClassFieldType.LIST;
        else if (Map.class.isAssignableFrom(type)) classField.type = ClassFieldType.MAP;
        else if (basicTypeClassMapBoolean.get(type) != null
                || type.isPrimitive()
                || (Number.class.isAssignableFrom(type)))
            classField.type = ClassFieldType.SCALAR;
        else if (Enum.class.isAssignableFrom(type)) classField.type = ClassFieldType.ENUM;
        else classField.type = ClassFieldType.SUBCLASS;
    }

    static LinkedList<OkJsonClassField> ensureClassFieldListFilled(
            Class<?> clazz) {
        LinkedList<OkJsonClassField> classFieldList = classMapFieldListCache.get()
                .computeIfAbsent(clazz.getName(), k -> new LinkedList<>());
        if (classFieldList.isEmpty()) {
            Field[] fields = clazz.getDeclaredFields();
            for (Field f : fields) {
                createOkJsonClassFieldWithBeanField(classFieldList, clazz, f);
            }
        }

        return classFieldList;
    }

    static void createOkJsonClassFieldWithBeanField(
            LinkedList<OkJsonClassField> classFieldList, Class<?> clazz, Field f) {
        f.setAccessible(true);
        OkJsonClassField classField = new OkJsonClassField();

        if (f.isAnnotationPresent(OkJsonField.class)) {
            classField.fieldName =
                    f.getAnnotation(OkJsonField.class).value().toCharArray();
        } else {
            classField.fieldName = f.getName().toCharArray();
        }

        classField.field = f;
        Class<?> type = f.getType();
        setClassType(type, classField);

        try {
            String methodName;
            if (type == Boolean.class || type.getName().equals("boolean")) {
                methodName = "is" + capitalWord(f);
                Method method = clazz.getMethod(methodName);
                if (method != null) {
                    classField.getter = method;
                    classField.getter.setAccessible(true);
                    if (method.isAnnotationPresent(OkJsonField.class)) {
                        classField.fieldName =
                                method.getAnnotation(OkJsonField.class).value().toCharArray();
                    }
                }
            }

            if (classField.getter == null) {
                methodName = "get" + capitalWord(f);
                Method method = clazz.getMethod(methodName);
                if (method != null) {
                    classField.getter = method;
                    classField.getter.setAccessible(true);
                    if (method.isAnnotationPresent(OkJsonField.class)) {
                        classField.fieldName =
                                method.getAnnotation(OkJsonField.class).value().toCharArray();
                    }
                }
            }
        } catch (NoSuchMethodException e) {
            // ignored
        }

        if (f.isAnnotationPresent(OkJsonDateTimeFormatter.class)) {
            classField.okjsonDateTimeFormatter =
                    f.getAnnotation(OkJsonDateTimeFormatter.class);
        } else {
            classField.okjsonDateTimeFormatter = null;
        }

        if (Modifier.isPublic(f.getModifiers())) {
            classFieldList.add(classField);
        } else if (classField.getter != null
                && Modifier.isPublic(classField.getter.getModifiers())) {
            classFieldList.add(classField);
        }
    }

    static String capitalWord(Field f) {
        return f.getName().substring(0, 1).toUpperCase(Locale.getDefault()) +
                f.getName().substring(1);
    }

    private boolean strictPolicyEnable;
    private boolean directAccessPropertyEnable;
    private boolean prettyFormatEnable;
    private boolean nullEnable;

    private Integer errorCode;
    private String errorDesc;


    public OkJsonGenerator() {
        this.strictPolicyEnable = false;
        this.directAccessPropertyEnable = false;
        this.prettyFormatEnable = false;
        this.nullEnable = false;
        this.errorCode = 0;
        this.errorDesc = null;
    }

    public boolean isStrictPolicyEnable() {
        return strictPolicyEnable;
    }

    public void setStrictPolicyEnable(boolean strictPolicyEnable) {
        this.strictPolicyEnable = strictPolicyEnable;
    }

    public boolean isDirectAccessPropertyEnable() {
        return directAccessPropertyEnable;
    }

    public void setDirectAccessPropertyEnable(boolean directAccessPropertyEnable) {
        this.directAccessPropertyEnable = directAccessPropertyEnable;
    }

    public boolean isPrettyFormatEnable() {
        return prettyFormatEnable;
    }

    public void setPrettyFormatEnable(boolean prettyFormatEnable) {
        this.prettyFormatEnable = prettyFormatEnable;
    }

    public boolean isNullEnable() {
        return nullEnable;
    }

    public void setNullEnable(boolean nullEnable) {
        this.nullEnable = nullEnable;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public String getErrorDesc() {
        return errorDesc;
    }

    public void stringifyToFile(Object object, String filePath) {

        try {
            String jsonString = stringify(object);
            Files.write(Paths.get(filePath), jsonString.getBytes(), StandardOpenOption.WRITE);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 转换对象成JSON
     *
     * @param object 对象
     * @return JSON
     */
    @SuppressWarnings("unchecked")
    public String stringify(Object object) throws Exception {
        if (object == null) return "null";
        if (object instanceof String) {
            return object.toString();
        } else if (object instanceof List) {
            return stringifyList((List) object);
        } else if (object.getClass().isArray()) {
            return stringifyArray(object);
        } else if (object instanceof Map) {
            return stringifyMap((Map<String, Object>) object);
        }


        OkJsonCharArrayBuilder jsonCharArrayBuilder;
        jsonCharArrayBuilder = jsonByteArrayBuilderCache.get();
        jsonCharArrayBuilder.setLength(0);

        beginObject(jsonCharArrayBuilder);
        stringifyObject(object, jsonCharArrayBuilder, 0);
        endObject(jsonCharArrayBuilder, 0);
        return jsonCharArrayBuilder.toString();


    }

    void stringifyObject(Object object, OkJsonCharArrayBuilder jsonCharArrayBuilder, int depth)
            throws Exception {
        Class<?> clazz;

        if (object instanceof Map) {
            stringifyMap((Map<String, Object>) object, jsonCharArrayBuilder, depth + 1);
        } else {
            clazz = object.getClass();
            LinkedList<OkJsonClassField> classFieldList;
            classFieldList = ensureClassFieldListFilled(clazz);
            int fieldIndex = 0;
            for (OkJsonClassField classField : classFieldList) {
                Object value = classField.getFieldValue(object);
                if (value == null) {
                    if (nullEnable) {
                        fieldIndex++;
                        writeComma1(jsonCharArrayBuilder, fieldIndex);
                        writeEmptyField(jsonCharArrayBuilder, depth, classField.fieldName);
                    }
                    continue;
                }

                fieldIndex++;
                new FieldWriter(jsonCharArrayBuilder, depth, classField, fieldIndex, value).write();
            }

            jsonCharArrayBuilder.appendEnter(prettyFormatEnable);
        }

    }

    String stringifyMap(Map<String, Object> object) throws Exception {
        if (object == null) return "null";
        OkJsonCharArrayBuilder jsonCharArrayBuilder = jsonByteArrayBuilderCache.get();
        jsonCharArrayBuilder.setLength(0);

        beginObject(jsonCharArrayBuilder);
        stringifyMap(object, jsonCharArrayBuilder, 0);
        endObject(jsonCharArrayBuilder, 0);
        return jsonCharArrayBuilder.toString();
    }

    void stringifyMap(Map<String, Object> m, OkJsonCharArrayBuilder jsonCharArrayBuilder, int depth)
            throws Exception {
        Class<?> clazz;

        Set<String> keys = m.keySet();
        int fieldIndex = 0;
        for (String k : keys) {
            Object value = m.get(k);
            if (value == null) {
                if (nullEnable) {
                    fieldIndex++;
                    writeComma1(jsonCharArrayBuilder, fieldIndex);
                    writeEmptyField(jsonCharArrayBuilder, depth, k.toCharArray());
                }
                continue;
            }

            OkJsonClassField classField = new OkJsonClassField();
            classField.fieldName = k.toCharArray();
            Class<?> type = value.getClass();
            setClassType(type, classField);

            fieldIndex++;
            new FieldWriter(jsonCharArrayBuilder, depth, classField, fieldIndex, value).write();
        }

        jsonCharArrayBuilder.appendEnter(prettyFormatEnable);

    }

    @SuppressWarnings("rawtypes")
    String stringifyList(List list) throws Exception {
        if (list == null) return "null";

        OkJsonCharArrayBuilder jsonCharArrayBuilder = jsonByteArrayBuilderCache.get();
        jsonCharArrayBuilder.setLength(0);
        beginArray(jsonCharArrayBuilder);

        // handle Array
        int length = list.size();
        for (int i = 0; i < length; i++) {
            Object value = list.get(i);
            OkJsonClassField classField = new OkJsonClassField();
            classField.fieldName = null;
            Class<?> type = value.getClass();
            setClassType(type, classField);

            writeCommaWithDepth(jsonCharArrayBuilder,  1,  i);

            if (value == null) {
                jsonCharArrayBuilder.appendString(NULL_STRING);
                continue;
            }

            new ElementWriter(value, classField, jsonCharArrayBuilder, 1).write();
        }
        jsonCharArrayBuilder.appendEnter(prettyFormatEnable);
        jsonCharArrayBuilder.appendChar(']');
        return jsonCharArrayBuilder.toString();
    }

    String stringifyArray(Object object) throws Exception {
        if (object == null) return "null";
        if (!object.getClass().isArray()) {
            throw new IllegalArgumentException("Object is not an array");
        }

        if (classMapFieldListCache.get() == null) classMapFieldListCache.set(new LinkedHashMap<>());
        OkJsonCharArrayBuilder jsonCharArrayBuilder;
        if (jsonByteArrayBuilderCache.get() == null) {
            jsonCharArrayBuilder = new OkJsonCharArrayBuilder(1024);
            jsonByteArrayBuilderCache.set(jsonCharArrayBuilder);
        } else {
            jsonCharArrayBuilder = jsonByteArrayBuilderCache.get();
        }
        jsonCharArrayBuilder.setLength(0);
        if (fieldByteArrayBuilderCache.get() == null) fieldByteArrayBuilderCache.set(new OkJsonCharArrayBuilder(1024));
        if (basicTypeClassMapBooleanCache.get() == null) basicTypeClassMapBooleanCache.set(createBasicTypes());

        beginArray(jsonCharArrayBuilder);
        // handle Array
        int length = Array.getLength(object);
        for (int i = 0; i < length; i++) {
            Object value = Array.get(object, i);
            OkJsonClassField classField = new OkJsonClassField();
            classField.fieldName = null;
            Class<?> type = value.getClass();
            setClassType(type, classField);

            writeCommaWithDepth(jsonCharArrayBuilder,  0,  i);
            if (value == null) {
                jsonCharArrayBuilder.appendString(NULL_STRING);
                continue;
            }

            new ElementWriter(value, classField, jsonCharArrayBuilder, 0).write();

        }
        jsonCharArrayBuilder.appendEnter(prettyFormatEnable);
        jsonCharArrayBuilder.appendChar(']');
        return jsonCharArrayBuilder.toString();
    }

    String unfoldEscape(String value) {

        OkJsonCharArrayBuilder fieldCharArrayBuilder = fieldByteArrayBuilderCache.get();
        char[] jsonCharArrayBuilder;
        int jsonCharArrayLength;
        int jsonCharArrayIndex;
        int segmentBeginOffset;
        int segmentLen;
        char c;

        if (value == null) return null;

        jsonCharArrayBuilder = value.toCharArray();
        jsonCharArrayLength = value.length();

        fieldCharArrayBuilder.setLength(0);

        segmentBeginOffset = 0;
        for (jsonCharArrayIndex = 0; jsonCharArrayIndex < jsonCharArrayLength; jsonCharArrayIndex++) {
            c = jsonCharArrayBuilder[jsonCharArrayIndex];
            if (c == '\"') {
                segmentLen = jsonCharArrayIndex - segmentBeginOffset;
                if (segmentLen > 0)
                    fieldCharArrayBuilder.appendBytesFromOffsetWithLength(jsonCharArrayBuilder, segmentBeginOffset,
                            segmentLen);
                fieldCharArrayBuilder.appendCharArray("\\\"".toCharArray());
                segmentBeginOffset = jsonCharArrayIndex + 1;
            } else if (c == '\\') {
                segmentLen = jsonCharArrayIndex - segmentBeginOffset;
                if (segmentLen > 0)
                    fieldCharArrayBuilder.appendBytesFromOffsetWithLength(jsonCharArrayBuilder, segmentBeginOffset,
                            segmentLen);
                fieldCharArrayBuilder.appendCharArray("\\\\".toCharArray());
                segmentBeginOffset = jsonCharArrayIndex + 1;
            } else if (c == '/') {
                segmentLen = jsonCharArrayIndex - segmentBeginOffset;
                if (segmentLen > 0)
                    fieldCharArrayBuilder.appendBytesFromOffsetWithLength(jsonCharArrayBuilder, segmentBeginOffset,
                            segmentLen);
                fieldCharArrayBuilder.appendCharArray("\\/".toCharArray());
                segmentBeginOffset = jsonCharArrayIndex + 1;
            } else if (c == '\t') {
                segmentLen = jsonCharArrayIndex - segmentBeginOffset;
                if (segmentLen > 0)
                    fieldCharArrayBuilder.appendBytesFromOffsetWithLength(jsonCharArrayBuilder, segmentBeginOffset,
                            segmentLen);
                fieldCharArrayBuilder.appendCharArray("\\t".toCharArray());
                segmentBeginOffset = jsonCharArrayIndex + 1;
            } else if (c == '\f') {
                segmentLen = jsonCharArrayIndex - segmentBeginOffset;
                if (segmentLen > 0)
                    fieldCharArrayBuilder.appendBytesFromOffsetWithLength(jsonCharArrayBuilder, segmentBeginOffset,
                            segmentLen);
                fieldCharArrayBuilder.appendCharArray("\\f".toCharArray());
                segmentBeginOffset = jsonCharArrayIndex + 1;
            } else if (c == '\b') {
                segmentLen = jsonCharArrayIndex - segmentBeginOffset;
                if (segmentLen > 0)
                    fieldCharArrayBuilder.appendBytesFromOffsetWithLength(jsonCharArrayBuilder, segmentBeginOffset,
                            segmentLen);
                fieldCharArrayBuilder.appendCharArray("\\b".toCharArray());
                segmentBeginOffset = jsonCharArrayIndex + 1;
            } else if (c == '\n') {
                segmentLen = jsonCharArrayIndex - segmentBeginOffset;
                if (segmentLen > 0)
                    fieldCharArrayBuilder.appendBytesFromOffsetWithLength(jsonCharArrayBuilder, segmentBeginOffset,
                            segmentLen);
                fieldCharArrayBuilder.appendCharArray("\\n".toCharArray());
                segmentBeginOffset = jsonCharArrayIndex + 1;
            } else if (c == '\r') {
                segmentLen = jsonCharArrayIndex - segmentBeginOffset;
                if (segmentLen > 0)
                    fieldCharArrayBuilder.appendBytesFromOffsetWithLength(jsonCharArrayBuilder, segmentBeginOffset,
                            segmentLen);
                fieldCharArrayBuilder.appendCharArray("\\r".toCharArray());
                segmentBeginOffset = jsonCharArrayIndex + 1;
            }
        }
        if (fieldCharArrayBuilder.getLength() > 0 && segmentBeginOffset < jsonCharArrayIndex) {
            segmentLen = jsonCharArrayIndex - segmentBeginOffset;
            if (segmentLen > 0)
                fieldCharArrayBuilder.appendBytesFromOffsetWithLength(jsonCharArrayBuilder, segmentBeginOffset,
                        segmentLen);
        }

        if (fieldCharArrayBuilder.getLength() == 0)
            return value;
        else
            return fieldCharArrayBuilder.toString();
    }

    void writeComma1(OkJsonCharArrayBuilder jsonCharArrayBuilder, int fieldIndex) {
        if (fieldIndex > 1) {
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendCharArray(SEP_FIELD_CHAR_PRETTY);
            } else {
                jsonCharArrayBuilder.appendChar(SEP_FIELD_CHAR);
            }
        }
    }

    void writeEmptyField(OkJsonCharArrayBuilder jsonCharArrayBuilder, int depth, char[] fieldName) {
        if (nullEnable) {
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendTabs(depth + 1);
                jsonCharArrayBuilder.appendPairPretty(fieldName, NULL_STRING);
            } else {
                jsonCharArrayBuilder.appendPair(fieldName, NULL_STRING);
            }
        }
    }

    void stringifyList(List<Object> list, OkJsonClassField classField,
                       OkJsonCharArrayBuilder jsonCharArrayBuilder,
                       int depth) throws Exception {
        if (list.size() == 0) {
            jsonCharArrayBuilder.appendString("[]");
            return;
        }
        beginArray(jsonCharArrayBuilder);
        for (int i = 0; i < list.size(); i++) {
            writeCommaWithDepth(jsonCharArrayBuilder, depth, i);
            Object object = list.get(i);
            if (object == null) {
                jsonCharArrayBuilder.appendString("null");
                continue;
            }
            OkJsonClassField cf = new OkJsonClassField();
            setClassType(object.getClass(), cf);
            new ElementWriter(object, cf, jsonCharArrayBuilder, depth).write();
        }
        jsonCharArrayBuilder.appendEnter(prettyFormatEnable);
        endArray(jsonCharArrayBuilder, depth);
    }

    void beginArray(OkJsonCharArrayBuilder jsonCharArrayBuilder) {
        if (prettyFormatEnable)
            jsonCharArrayBuilder.appendString("[\n");
        else
            jsonCharArrayBuilder.appendChar('[');
    }

    void endArray(OkJsonCharArrayBuilder jsonCharArrayBuilder, int depth) {
        if (prettyFormatEnable) {
            jsonCharArrayBuilder.appendTabs(depth);
        }
        jsonCharArrayBuilder.appendChar(']');
    }


    private void beginObject(OkJsonCharArrayBuilder jsonCharArrayBuilder) {
        if (prettyFormatEnable) {
            jsonCharArrayBuilder.appendString("{\n");
        } else {
            jsonCharArrayBuilder.appendChar('{');
        }
    }

    private void endObject(OkJsonCharArrayBuilder jsonCharArrayBuilder, int depth) {
        if (prettyFormatEnable) jsonCharArrayBuilder.appendTabs(depth );
        jsonCharArrayBuilder.appendChar('}');
    }

    void stringifyArray(Object array, int arrayCount, OkJsonClassField classField,
                        OkJsonCharArrayBuilder jsonCharArrayBuilder, int depth)
            throws Exception {
        if (array == null || !array.getClass().isArray() || Array.getLength(array) == 0) {
            jsonCharArrayBuilder.appendString("[]");
            return;
        }

        beginArray(jsonCharArrayBuilder);
        int len = Array.getLength(array);
        for (int i = 0; i < len; i++) {
            writeCommaWithDepth(jsonCharArrayBuilder, depth, i);
            Object object = Array.get(array, i);
            if (object == null) {
                writeCommaWithDepth(jsonCharArrayBuilder, depth, i);
                jsonCharArrayBuilder.appendString("null");
                continue;
            }
            OkJsonClassField cf = new OkJsonClassField();
            setClassType(object.getClass(), cf);
            new ElementWriter(object, cf, jsonCharArrayBuilder, depth).write();
        }

        jsonCharArrayBuilder.appendEnter(prettyFormatEnable);
        endArray(jsonCharArrayBuilder, depth);
    }



    private void writeCommaWithDepth(OkJsonCharArrayBuilder jsonCharArrayBuilder, int depth, int arrayIndex) {
        if (arrayIndex > 0) {
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendCharArray(SEP_FIELD_CHAR_PRETTY).appendTabs(depth + 1);
            } else {
                jsonCharArrayBuilder.appendChar(SEP_FIELD_CHAR);
            }
        } else {
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendTabs(depth + 1);
            }
        }
    }

    class ElementWriter {
        OkJsonCharArrayBuilder jsonCharArrayBuilder;
        int depth;
        OkJsonClassField classField;
        Object value;

        ElementWriter(Object value, OkJsonClassField classField, OkJsonCharArrayBuilder jsonCharArrayBuilder, int depth) throws Exception {
            this.classField = classField;
            this.jsonCharArrayBuilder = jsonCharArrayBuilder;
            this.depth = depth;
            this.value = value;
        }

        void write() throws Exception {
            switch (classField.type) {
            case STRING:
            case ENUM:
                writeStringField();
                break;
            case SCALAR:
                writeScalarField();
                break;
            case LOCAL_DATE:
                writeLocalDate();
                break;
            case LOCAL_TIME:
                writeLocalTime();
                break;
            case LOCAL_DATE_TIME:
                writeLocalDateTime( );
                break;
            case LIST:
                stringifyList((List)value, classField, jsonCharArrayBuilder, depth + 1);
                break;
            case ARRAY:
                stringifyArray(value, Array.getLength(value), classField, jsonCharArrayBuilder, depth + 1);
                break;
            case SUBCLASS:
                beginObject(jsonCharArrayBuilder);
                stringifyObject(value, jsonCharArrayBuilder, depth+1);
                endObject(jsonCharArrayBuilder, depth+1);
                break;
            case MAP:
                beginObject(jsonCharArrayBuilder);
                stringifyMap((Map<String, Object>) value, jsonCharArrayBuilder, depth+1);
                endObject(jsonCharArrayBuilder, depth+1);
                break;
            }
        }

        private void writeScalarField() {
            jsonCharArrayBuilder.appendString(value.toString());
        }

        private void writeStringField() {
            String string = unfoldEscape(value.toString());
            jsonCharArrayBuilder.appendJsonStringWithQuote(string);
        }


        void writeLocalDateTime() {
            LocalDateTime localDateTime = (LocalDateTime) value;
            String dateTimeFormatter;
            if (classField.okjsonDateTimeFormatter != null) {
                dateTimeFormatter = classField.okjsonDateTimeFormatter.format();
            } else {
                dateTimeFormatter = "yyyy-MM-dd'T'HH:mm:ss.SSS";
            }
            String localDateTimeString = DateTimeFormatter.ofPattern(dateTimeFormatter).format(localDateTime);
            if (prettyFormatEnable) jsonCharArrayBuilder.appendTabs(depth + 1);
            jsonCharArrayBuilder.appendJsonStringWithQuote(localDateTimeString);
        }

        void writeLocalTime() {
            LocalTime localTime = (LocalTime) value;
            String timeFormatter;
            if (classField.okjsonDateTimeFormatter != null) {
                timeFormatter = classField.okjsonDateTimeFormatter.format();
            } else {
                timeFormatter = "HH:mm:ss.SSS";
            }
            String localTimeString = DateTimeFormatter.ofPattern(timeFormatter).format(localTime);
            if (prettyFormatEnable) jsonCharArrayBuilder.appendTabs(depth + 1);
            jsonCharArrayBuilder.appendJsonStringWithQuote(localTimeString);
        }

        void writeLocalDate() {
            LocalDate localDate = (LocalDate) value;
            String dateFormatter = "yyyy-MM-dd";
            if (classField.okjsonDateTimeFormatter != null) {
                dateFormatter = classField.okjsonDateTimeFormatter.format();
            }
            String localDateString = DateTimeFormatter.ofPattern(dateFormatter).format(localDate);
            jsonCharArrayBuilder.appendJsonStringWithQuote(localDateString);
        }
    }

    class FieldWriter {
        OkJsonCharArrayBuilder jsonCharArrayBuilder;
        int depth;
        OkJsonClassField classField;
        int fieldIndex;
        Object value;

        FieldWriter(OkJsonCharArrayBuilder jsonCharArrayBuilder, int depth, OkJsonClassField classField, int fieldIndex,
                    Object value) {
            this.classField = classField;
            this.jsonCharArrayBuilder = jsonCharArrayBuilder;
            this.depth = depth;
            this.fieldIndex = fieldIndex;
            this.value = value;

        }

        @SuppressWarnings("unchecked")
        void write() throws Exception {
            writeComma1(jsonCharArrayBuilder, fieldIndex);
            switch (classField.type) {
            case STRING:
                writeStringField();
                break;
            case SCALAR:
                writeScalarField();
                break;
            case LOCAL_DATE:
                writeLocalDateField();
                break;
            case LOCAL_TIME:
                writeLocalTimeField();
                break;
            case LOCAL_DATE_TIME:
                writeLocalDateTimeField();
                break;
            case LIST:
                writeListField(jsonCharArrayBuilder, depth, classField, (List<Object>) value);
                break;
            case ARRAY:
                writeArrayField(value, jsonCharArrayBuilder, depth, classField);
                break;
            case ENUM:
                writeEnumField();
                break;
            case SUBCLASS:
            case MAP:
                writeObjectOrMapField(jsonCharArrayBuilder, depth, classField, value);
                break;
            }
        }


        void writeObjectOrMapField(OkJsonCharArrayBuilder jsonCharArrayBuilder,
                                   int depth, OkJsonClassField classField, Object subObject)
                throws Exception {
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendTabs(depth + 1);
                jsonCharArrayBuilder.appendPairPretty(classField.fieldName, '{');
                stringifyObject(subObject, jsonCharArrayBuilder, depth + 1);
                jsonCharArrayBuilder.appendTabs(depth + 1).appendChar('}');
            } else {
                jsonCharArrayBuilder.appendPair(classField.fieldName, '{');
                stringifyObject(subObject, jsonCharArrayBuilder, depth + 1);
                jsonCharArrayBuilder.appendChar('}');
            }
        }

        void writeEnumField() {
            Enum enumObject = (Enum) value;
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendTabs(depth + 1);
                jsonCharArrayBuilder.appendPairWithQuotePretty(classField.fieldName, enumObject.name());
            } else {
                jsonCharArrayBuilder.appendPairWithQuote(classField.fieldName, enumObject.name());
            }
        }

        void writeLocalDateTimeField() {
            LocalDateTime localDateTime = (LocalDateTime) value;
            String defaultDateTimeFormatter = "yyyy-MM-dd HH:mm:ss";
            if (classField.okjsonDateTimeFormatter != null)
                defaultDateTimeFormatter = classField.okjsonDateTimeFormatter.format();
            String localDateTimeString = DateTimeFormatter.ofPattern(defaultDateTimeFormatter).format(localDateTime);
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendTabs(depth + 1);
                jsonCharArrayBuilder.appendPairWithQuotePretty(classField.fieldName, localDateTimeString);
            } else {
                jsonCharArrayBuilder.appendPairWithQuote(classField.fieldName, localDateTimeString);
            }
        }

        void writeLocalTimeField() {
            LocalTime localTime = (LocalTime) value;
            String defaultDateTimeFormatter = "HH:mm:ss";
            if (classField.okjsonDateTimeFormatter != null)
                defaultDateTimeFormatter = classField.okjsonDateTimeFormatter.format();
            String localTimeString = DateTimeFormatter.ofPattern(defaultDateTimeFormatter).format(localTime);
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendTabs(depth + 1);
                jsonCharArrayBuilder.appendPairWithQuotePretty(classField.fieldName, localTimeString);
            } else {
                jsonCharArrayBuilder.appendPairWithQuote(classField.fieldName, localTimeString);
            }
        }

        void writeLocalDateField() {
            LocalDate localDate = (LocalDate) value;
            String defaultDateTimeFormatter = "yyyy-MM-dd";
            if (classField.okjsonDateTimeFormatter != null)
                defaultDateTimeFormatter = classField.okjsonDateTimeFormatter.format();
            String localDateString = DateTimeFormatter.ofPattern(defaultDateTimeFormatter).format(localDate);
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendTabs(depth + 1);
                jsonCharArrayBuilder.appendPairWithQuotePretty(classField.fieldName, localDateString);
            } else {
                jsonCharArrayBuilder.appendPairWithQuote(classField.fieldName, localDateString);
            }
        }

        void writeScalarField() {
            if (prettyFormatEnable) {
                jsonCharArrayBuilder.appendTabs(depth + 1);
                jsonCharArrayBuilder.appendPairPretty(classField.fieldName, value ==null ? "null" :value.toString());
            } else {
                jsonCharArrayBuilder.appendPair(classField.fieldName, value ==null ? "null": value.toString());
            }
        }

        void writeStringField() {
            String string = unfoldEscape((String)value);
            if (prettyFormatEnable) jsonCharArrayBuilder.appendTabs(depth + 1);
            if (prettyFormatEnable)
                jsonCharArrayBuilder.appendPairWithQuotePretty(classField.fieldName, string);
            else
                jsonCharArrayBuilder.appendPairWithQuote(classField.fieldName, string);
        }

        void writeListField(OkJsonCharArrayBuilder jsonCharArrayBuilder,
                            int depth, OkJsonClassField classField, List<Object> list)
                throws Exception {
            if (prettyFormatEnable) jsonCharArrayBuilder.appendTabs(depth + 1);
            if (prettyFormatEnable)
                jsonCharArrayBuilder.appendFieldNamePretty(classField.fieldName);
            else
                jsonCharArrayBuilder.appendFieldName(classField.fieldName);

            stringifyList(list, classField, jsonCharArrayBuilder, depth + 1);
        }


        void writeArrayField(Object array, OkJsonCharArrayBuilder jsonCharArrayBuilder,
                             int depth, OkJsonClassField classField) throws Exception {
            int arrayCount = Array.getLength(array);
            if (prettyFormatEnable) jsonCharArrayBuilder.appendTabs(depth + 1);

            if (prettyFormatEnable)
                jsonCharArrayBuilder.appendFieldNamePretty(classField.fieldName);
            else
                jsonCharArrayBuilder.appendFieldName(classField.fieldName);

            // 由 stringifyArray 写入 '[' ... ']'，这里不再手动加括号
            stringifyArray(array, arrayCount, classField, jsonCharArrayBuilder, depth + 1);
        }
    }


}

class OkJsonCharArrayBuilder {

    public char[] buf;
    public int bufSize;
    public int bufLength;

    final private static String TABS = "\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t";

    public OkJsonCharArrayBuilder() {
        this(16);
    }

    public OkJsonCharArrayBuilder(int initBufSize) {
        this.buf = new char[initBufSize];
        this.bufSize = initBufSize;
        this.bufLength = 0;
    }

    private void resize(int newSize) {
        char[] newBuf;
        int newBufSize;

        if (bufSize < 10240240) {
            newBufSize = bufSize * 2;
        } else {
            newBufSize = bufSize + 10240240;
        }
        if (newBufSize < newSize)
            newBufSize = newSize;
        newBuf = new char[newBufSize];
        System.arraycopy(buf, 0, newBuf, 0, bufLength);
        buf = newBuf;
        bufSize = newBufSize;
    }

    public OkJsonCharArrayBuilder appendChar(char c) {
        int newBufLength = bufLength + 1;

        if (newBufLength > bufSize) resize(newBufLength);

        buf[bufLength] = c;
        bufLength++;

        return this;
    }

    public OkJsonCharArrayBuilder appendCharArray(char[] charArray) {
        int newBufLength = bufLength + charArray.length;

        if (newBufLength > bufSize) resize(newBufLength);

        System.arraycopy(charArray, 0, buf, bufLength, charArray.length);
        bufLength = newBufLength;

        return this;
    }

    public OkJsonCharArrayBuilder appendCharArrayWith3(char[] charArray) {
        int newBufLength = bufLength + 3;

        if (newBufLength > bufSize) resize(newBufLength);

        buf[bufLength] = charArray[0];
        bufLength++;
        buf[bufLength] = charArray[1];
        bufLength++;
        buf[bufLength] = charArray[2];
        bufLength++;

        return this;
    }

    public OkJsonCharArrayBuilder appendCharArrayWith4(char[] charArray) {
        int newBufLength = bufLength + 4;

        if (newBufLength > bufSize) resize(newBufLength);

        buf[bufLength] = charArray[0];
        bufLength++;
        buf[bufLength] = charArray[1];
        bufLength++;
        buf[bufLength] = charArray[2];
        bufLength++;
        buf[bufLength] = charArray[3];
        bufLength++;

        return this;
    }

    public OkJsonCharArrayBuilder appendString(String str) {
        int strLength = str.length();
        int newBufLength = bufLength + strLength;

        if (newBufLength > bufSize) resize(newBufLength);

        str.getChars(0, strLength, buf, bufLength);
        bufLength = newBufLength;

        return this;
    }

    public OkJsonCharArrayBuilder appendBytesFromOffsetWithLength(char[] charArray, int offset, int len) {
        int newBufLength = bufLength + len;

        if (newBufLength > bufSize)
            resize(newBufLength);

        System.arraycopy(charArray, offset, buf, bufLength, len);
        bufLength = newBufLength;

        return this;
    }

    public OkJsonCharArrayBuilder appendTabs(int tabCount) {
        int newBufLength = bufLength + tabCount;

        if (newBufLength > bufSize)
            resize(newBufLength);

        if (tabCount <= TABS.length()) {
            System.arraycopy(TABS.toCharArray(), 0, buf, bufLength, tabCount);
            bufLength += tabCount;
        } else {
            for (int i = 0; i < tabCount; i++) {
                buf[bufLength] = '\t';
                bufLength++;
            }
        }

        return this;
    }

    public OkJsonCharArrayBuilder appendPair(char[] name, char c) {
        int newBufLength = bufLength + name.length + 4;

        if (newBufLength > bufSize) resize(newBufLength);

        buf[bufLength] = '"';
        bufLength++;
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength] = '"';
        bufLength++;
        buf[bufLength] = ':';
        bufLength++;
        buf[bufLength] = c;
        bufLength++;

        return this;
    }

    public OkJsonCharArrayBuilder appendPairPretty(char[] name, char c) {
        int newBufLength = bufLength + name.length + 7;

        if (newBufLength > bufSize) resize(newBufLength);

        buf[bufLength] = '"';
        bufLength++;
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength] = '"';
        bufLength++;
        buf[bufLength] = ' ';
        bufLength++;
        buf[bufLength] = ':';
        bufLength++;
        buf[bufLength] = ' ';
        bufLength++;
        buf[bufLength] = c;
        bufLength++;
        buf[bufLength] = '\n';
        bufLength++;

        return this;
    }

    public OkJsonCharArrayBuilder appendPair(char[] name, char[] str) {
        int newBufLength = bufLength + name.length + str.length + 3;

        if (newBufLength > bufSize)
            resize(newBufLength);

        buf[bufLength] = '"';
        bufLength++;
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength] = '"';
        bufLength++;
        buf[bufLength] = ':';
        bufLength++;
        System.arraycopy(str, 0, buf, bufLength, str.length);
        bufLength += str.length;

        return this;
    }

    public OkJsonCharArrayBuilder appendPairPretty(char[] name, char[] str) {
        int newBufLength = bufLength + name.length + str.length + 5;

        if (newBufLength > bufSize)
            resize(newBufLength);

        buf[bufLength] = '"';
        bufLength++;
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength] = '"';
        bufLength++;
        buf[bufLength] = ' ';
        bufLength++;
        buf[bufLength] = ':';
        bufLength++;
        buf[bufLength] = ' ';
        bufLength++;
        System.arraycopy(str, 0, buf, bufLength, str.length);
        bufLength += str.length;

        return this;
    }

    public OkJsonCharArrayBuilder appendPair(char[] name, String str) {
        int strLength = str.length();
        int newBufLength = bufLength + name.length + strLength + 3;

        if (newBufLength > bufSize)
            resize(newBufLength);

        buf[bufLength] = '"';
        bufLength++;
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength] = '"';
        bufLength++;
        buf[bufLength] = ':';
        bufLength++;
        str.getChars(0, strLength, buf, bufLength);
        bufLength += strLength;

        return this;
    }

    public OkJsonCharArrayBuilder appendPairPretty(char[] name, String str) {
        int strLength = str.length();
        int newBufLength = bufLength + name.length + strLength + 5;

        if (newBufLength > bufSize)
            resize(newBufLength);

        buf[bufLength] = '"';
        bufLength++;
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength] = '"';
        bufLength++;
        buf[bufLength] = ' ';
        bufLength++;
        buf[bufLength] = ':';
        bufLength++;
        buf[bufLength] = ' ';
        bufLength++;
        str.getChars(0, strLength, buf, bufLength);
        bufLength += strLength;
        // buf[bufLength] = ' ' ; bufLength++;

        return this;
    }

    public OkJsonCharArrayBuilder appendPairWithQuote(char[] name, String str) {
        int strLength = str.length();
        int newBufLength = bufLength + name.length + strLength + 5;

        if (newBufLength > bufSize)
            resize(newBufLength);

        buf[bufLength] = '"';
        bufLength++;
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength] = '"';
        bufLength++;
        buf[bufLength] = ':';
        bufLength++;
        buf[bufLength] = '"';
        bufLength++;
        str.getChars(0, strLength, buf, bufLength);
        bufLength += strLength;
        buf[bufLength] = '"';
        bufLength++;

        return this;
    }

    public OkJsonCharArrayBuilder appendPairWithQuotePretty(char[] name, String str) {
        int strLength = str.length();
        int newBufLength = bufLength + name.length + strLength + 7;

        if (newBufLength > bufSize)
            resize(newBufLength);

        buf[bufLength] = '"';
        bufLength++;
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength] = '"';
        bufLength++;
        buf[bufLength] = ' ';
        bufLength++;
        buf[bufLength] = ':';
        bufLength++;
        buf[bufLength] = ' ';
        bufLength++;
        buf[bufLength] = '"';
        bufLength++;
        str.getChars(0, strLength, buf, bufLength);
        bufLength += strLength;
        buf[bufLength] = '"';
        bufLength++;

        return this;
    }


    public OkJsonCharArrayBuilder appendJsonStringWithQuote(String str) {
        int strLength = str.length();
        int newBufLength = bufLength + strLength + 2;

        if (newBufLength > bufSize)
            resize(newBufLength);

        buf[bufLength] = '"';
        bufLength++;
        str.getChars(0, strLength, buf, bufLength);
        bufLength += strLength;
        buf[bufLength] = '"';
        bufLength++;

        return this;
    }

    public OkJsonCharArrayBuilder appendFieldName(char[] name) {
        int newBufLength = bufLength + name.length + 3;
        if (newBufLength > bufSize) resize(newBufLength);
        buf[bufLength++] = '"';
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength++] = '"';
        buf[bufLength++] = ':';
        return this;
    }

    public OkJsonCharArrayBuilder appendFieldNamePretty(char[] name) {
        int newBufLength = bufLength + name.length + 5;
        if (newBufLength > bufSize) resize(newBufLength);
        buf[bufLength++] = '"';
        System.arraycopy(name, 0, buf, bufLength, name.length);
        bufLength += name.length;
        buf[bufLength++] = '"';
        buf[bufLength++] = ' ';
        buf[bufLength++] = ':';
        buf[bufLength++] = ' ';
        return this;
    }

    public void appendEnter(boolean prettyFormatEnable) {
        if (prettyFormatEnable) appendChar('\n');
    }

    public void appendEmptyJsonArray(boolean prettyFormatEnable, int depth, char[] fieldName) {
        if (prettyFormatEnable) appendTabs(depth );
        appendPair(fieldName, "[]");
    }

    public int getLength() {
        return bufLength;
    }

    public void setLength(int length) {
        bufLength = length;
    }

    @Override
    public String toString() {
        return new String(buf, 0, bufLength);
    }
}

//    import java.lang.reflect.Array;
//
//if (obj != null && obj.getClass().isArray()) {
//        int len = Array.getLength(obj);
//        for (int i = 0; i < len; i++) {
//            Object elem = Array.get(obj, i); // 原始类型元素会被装箱
//            // 处理 elem ...
//        }
//    }
