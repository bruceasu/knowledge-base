
package me.asu.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * A low overhead, lightweight logging system.
 *
 * @author Nathan Sweet <misc@n4te.com>
 */
public class Log {
    /**
     * No logging at all.
     */
    public static final int LEVEL_NONE = 7;
    public static final int LEVEL_FATAL = 6;
    /**
     * Critical errors. The application may no longer work correctly.
     */
    public static final int LEVEL_ERROR = 5;
    /**
     * Important warnings. The application will continue to work correctly.
     */
    public static final int LEVEL_WARN = 4;
    /**
     * Informative messages. Typically used for deployment.
     */
    public static final int LEVEL_INFO = 3;
    /**
     * Debug messages. This level is useful during development.
     */
    public static final int LEVEL_DEBUG = 2;
    /**
     * Trace messages. A lot of information is logged, so this level is usually only needed when debugging a problem.
     */
    public static final int LEVEL_TRACE = 1;

    /**
     * The level of messages that will be logged. Compiling this and the booleans below as "final" will cause the compiler to
     * remove all "if (Log.info) ..." type statements below the set level.
     */
    static private int level = LEVEL_INFO;

    /**
     * True when the ERROR level will be logged.
     */
    public static boolean FATAL = level <= LEVEL_FATAL;
    public static boolean ERROR = level <= LEVEL_ERROR;
    /**
     * True when the WARN level will be logged.
     */
    public static boolean WARN = level <= LEVEL_WARN;
    /**
     * True when the INFO level will be logged.
     */
    public static boolean INFO = level <= LEVEL_INFO;
    /**
     * True when the DEBUG level will be logged.
     */
    public static boolean DEBUG = level <= LEVEL_DEBUG;
    /**
     * True when the TRACE level will be logged.
     */
    public static boolean TRACE = level <= LEVEL_TRACE;

    public static int get() {
        return level;
    }

    /**
     * Sets the level to log. If a version of this class is being used that has a final log level, this has no affect.
     */
    public static void set(int level) {
        // Comment out method contents when compiling fixed level JARs.
        Log.level = level;
        FATAL = level <= LEVEL_FATAL;
        ERROR = level <= LEVEL_ERROR;
        WARN = level <= LEVEL_WARN;
        INFO = level <= LEVEL_INFO;
        DEBUG = level <= LEVEL_DEBUG;
        TRACE = level <= LEVEL_TRACE;
    }


    public static void NONE() {
        set(LEVEL_NONE);
    }

    public static void FATAL() {
        set(LEVEL_FATAL);
    }

    public static void ERROR() {
        set(LEVEL_ERROR);
    }

    public static void WARN() {
        set(LEVEL_WARN);
    }

    public static void INFO() {
        set(LEVEL_INFO);
    }

    public static void DEBUG() {
        set(LEVEL_DEBUG);
    }

    public static void TRACE() {
        set(LEVEL_TRACE);
    }

    /**
     * Sets the logger that will write the log messages.
     */
    public static void setLogger(Logger logger) {
        Log.logger = logger;
    }

    static private Logger logger = new Logger();

    public static void fatal(String message, Throwable ex) {if (FATAL) logger.log(LEVEL_FATAL, null, message, ex);}

    public static void fatal(String category, String message, Throwable ex) {
        if (FATAL) logger.log(LEVEL_FATAL, category, message, ex);
    }

    public static void fatal(String message) {if (FATAL) logger.log(LEVEL_FATAL, null, message, null);}

    public static void fatal(String category, String message) {
        if (FATAL) logger.log(LEVEL_FATAL, category, message, null);
    }

    public static void fatal(String fmt, Object... params) {
        if (FATAL) logger.log(LEVEL_FATAL, null, String.format(fmt, params), null);
    }


    public static void error(String message, Throwable ex) {if (ERROR) logger.log(LEVEL_ERROR, null, message, ex);}

    public static void error(String category, String message, Throwable ex) {
        if (ERROR) logger.log(LEVEL_ERROR, category, message, ex);
    }

    public static void error(String message) {if (ERROR) logger.log(LEVEL_ERROR, null, message, null);}

    public static void error(String category, String message) {
        if (ERROR) logger.log(LEVEL_ERROR, category, message, null);
    }

    public static void error(String fmt, Object... params) {
        if (ERROR) logger.log(LEVEL_ERROR, null, String.format(fmt, params), null);
    }

    public static void warn(String message, Throwable ex) {if (WARN) logger.log(LEVEL_WARN, null, message, ex);}

    public static void warn(String category, String message, Throwable ex) {
        if (WARN) logger.log(LEVEL_WARN, category, message, ex);
    }

    public static void warn(String message) {if (WARN) logger.log(LEVEL_WARN, null, message, null);}

    public static void warn(String category, String message) {
        if (WARN) logger.log(LEVEL_WARN, category, message, null);
    }

    public static void warn(String fmt, Object... params) {
        if (WARN) logger.log(LEVEL_WARN, null, String.format(fmt, params), null);
    }

    public static void info(String message, Throwable ex) {if (INFO) logger.log(LEVEL_INFO, null, message, ex);}

    public static void info(String category, String message, Throwable ex) {
        if (INFO) logger.log(LEVEL_INFO, category, message, ex);
    }

    public static void info(String message) {if (INFO) logger.log(LEVEL_INFO, null, message, null);}

    public static void info(String category, String message) {
        if (INFO) logger.log(LEVEL_INFO, category, message, null);
    }

    public static void info(String fmt, Object... params) {
        if (INFO) logger.log(LEVEL_INFO, null, String.format(fmt, params), null);
    }

    public static void debug(String message, Throwable ex) {if (DEBUG) logger.log(LEVEL_DEBUG, null, message, ex);}

    public static void debug(String category, String message, Throwable ex) {
        if (DEBUG) logger.log(LEVEL_DEBUG, category, message, ex);
    }

    public static void debug(String message) {if (DEBUG) logger.log(LEVEL_DEBUG, null, message, null);}

    public static void debug(String category, String message) {
        if (DEBUG) logger.log(LEVEL_DEBUG, category, message, null);
    }

    public static void debug(String fmt, Object... params) {
        if (DEBUG) logger.log(LEVEL_DEBUG, null, String.format(fmt, params), null);
    }

    public static void trace(String message, Throwable ex) {if (TRACE) logger.log(LEVEL_TRACE, null, message, ex);}

    public static void trace(String category, String message, Throwable ex) {
        if (TRACE) logger.log(LEVEL_TRACE, category, message, ex);
    }

    public static void trace(String message) {if (TRACE) logger.log(LEVEL_TRACE, null, message, null);}

    public static void trace(String category, String message) {
        if (TRACE) logger.log(LEVEL_TRACE, category, message, null);
    }

    public static void trace(String fmt, Object... params) {
        if (TRACE) logger.log(LEVEL_TRACE, null, String.format(fmt, params), null);
    }

    private Log() {}

    /**
     * Performs the actual logging. Default implementation logs to System.out. Extended and use {@link Log#logger} set to handle
     * logging differently.
     */
    public static class Logger {
        private final long firstLogTime = System.currentTimeMillis();
        private final NumberFormat formatter = new DecimalFormat("000");
        private final NumberFormat formatter2 = new DecimalFormat("00");

        public void log(int level, String category, String message, Throwable ex) {
            StringBuffer sb = new StringBuffer(1000);
            long time = System.currentTimeMillis() - firstLogTime;
            long minutes = time / (1000 * 60);
            long seconds = time / (1000) % 60;
            long millis = time % 60;
            sb.append( getFullDateTime()).append("/");
            sb.append(formatter2.format(minutes));
            sb.append(':');
            sb.append(formatter2.format(seconds));
            sb.append('.').append(formatter.format(millis));

            if (LOG_DESC_MAP.containsKey(level)) sb.append("[").append(LOG_DESC_MAP.get(level)).append("] ");

            if (category != null) sb.append('[').append(category).append("] ");

            sb.append(message);

            if (ex != null) {
                StringWriter writer = new StringWriter(256);
                ex.printStackTrace(new PrintWriter(writer));
                sb.append('\n');
                sb.append(writer.toString().trim());
            }
            String str =  sb.toString();


            print(str);
        }

        /**
         * Prints the message to System.out. Called by the default implementation of {@link #log(int, String, String, Throwable)}.
         */
        protected void print(String message) {System.out.println(message);}
    }
    /**
     * 获取完全格式的的日期格式
     * @return 格式如 2015-10-31 10:33:25.012
     */
    public static String getFullDateTime(){
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSX");
        return sdf.format(new Date());
    }

    /** 日志类型描述map*/
    @SuppressWarnings("serial")
    public static Map<Integer, String> LOG_DESC_MAP = new HashMap<Integer, String>(){{
        put(1,"TRACE");
        put(2,"DEBUG");
        put(3,"INFO");
        put(4,"WARN");
        put(5,"ERROR");
        put(6,"FATAL");
    }};
}