package me.asu.http;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class DateUtils {
    static final String[] DATE_PATTERNS = {
            "EEE, dd MMM yyyy HH:mm:ss z", // [RFC9110#5.6.7] IMF-fixdate format
            "EEEE, dd-MMM-yy HH:mm:ss z",  // obsolete RFC 850 format
            "EEE MMM d HH:mm:ss yyyy",      // ANSI C's asctime() format
            "yyyy-MM-dd'T'HH:mm:sssX",
            "yyyy-MM-dd HH:mm:ss z",
            "yyyy/MM/dd HH:mm:ss z",
            "yyyy.MM.dd HH:mm:ss z",
    };

    static final long MIN_MILLIS = -62167392000000L; // 0001-01-01T00:00:00Z

    static final long MAX_MILLIS = 253402300799999L; // 9999-12-31T23:59:59.999Z

    // 线程安全、可复用的格式器：固定英文、固定 GMT 字面量
    static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
                    .withZone(ZoneOffset.UTC);
    /**
     * A GMT (UTC) timezone instance.
     */
    static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    /**
     * Parses a date string in one of the supported {@link #DATE_PATTERNS}.
     * <p>
     * Received date header values must be in one of the following formats:
     * Sun, 06 Nov 1994 08:49:37 GMT  ; IMF-fixdate
     * Sunday, 06-Nov-94 08:49:37 GMT ; obsolete RFC 850 format
     * Sun Nov  6 08:49:37 1994       ; ANSI C's asctime() format
     *
     * @param time a string representation of a time value
     * @return the parsed date value
     * @throws IllegalArgumentException if the given string does not contain
     *                                  a valid date format in any of the supported formats
     */
    public static Date parseDate(String time) {
        // [RFC9110#5.6.7] interpret 2-digit years >50 years in future as past,
        // SDF defaults to >20 years which covers it (see set2DigitYearStart)
        for (String pattern : DATE_PATTERNS) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(pattern, Locale.US);
                df.setLenient(false);
                df.setTimeZone(GMT);
                return df.parse(time);
            } catch (ParseException ignore) {
            }
        }
        throw new IllegalArgumentException("invalid date format: " + time);
    }


    /**
     * Formats the given time value as a string in IMF-fixdate format.
     *
     * @param time the time in milliseconds since 1970-01-01T00:00:00Z
     * @return the given time value as a string in IMF-fixdate format
     */
    public static String formatDate(long time) {
        if (time < MIN_MILLIS || time > MAX_MILLIS) {
            throw new IllegalArgumentException("year out of range (0001-9999): " + time);
        }
        // 直接在 UTC 时区格式化，结尾固定 'GMT'
        return IMF_FIXDATE.format(Instant.ofEpochMilli(time));
    }
}