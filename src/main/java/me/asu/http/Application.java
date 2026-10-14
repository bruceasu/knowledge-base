package me.asu.http;


import lombok.Data;
import lombok.Getter;
import me.asu.log.Log;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static me.asu.http.Strings.isEmpty;

/**
 * @author suk
 * @since 2018/11/16
 */
@Getter
public class Application {
    private transient HTTPServer               httpServer;
    private transient ExecutorService          executor;
    private           AppConfig                config      = new AppConfig();

    public Application() throws IOException {
        createHttpServer(config.getPort());
        addDefaultHandlers();
    }
    public Application(int port) throws IOException {
        if (port > 0) config.setPort(port);
        createHttpServer(port);
        addDefaultHandlers();
    }

    public void addRoute(String path, ContextHandler handler, String... methods) {
        ensureServerCreated();
        Objects.requireNonNull(path);
        Objects.requireNonNull(handler);
        httpServer.addContext(path, handler, methods);
    }

    public void addStaticRout(String path, String dir) throws IOException {
        httpServer.addContext(path + "/{*}", new FileContextHandler(dir));
    }
    /**
     * 为给定对象的所有被 {@link Context} 注解标记的方法添加上下文。
     *
     * @param o 需要添加上下文的对象，包含被注解的方法
     * @throws IllegalArgumentException 如果某个被 Context 注解标记的方法具有 {@link Context 无效签名}
     */
    public void addRoutes(Object o) throws IllegalArgumentException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            // add to contexts those with @Context annotation
            for (Method m : c.getDeclaredMethods()) {
                Context context = m.getAnnotation(Context.class);
                if (context != null) {
                    m.setAccessible(true); // allow access to private method
                    ContextHandler handler = new MethodContextHandler(m, o);
                    httpServer.addContext(context.value(), handler, context.methods());
                }
            }
        }
    }
    void ensureServerCreated() {
        Objects.requireNonNull(httpServer, "httpServer is null");
    }

    public void setServerSocketFactory(ServerSocketFactory serverSocketFactory) {
        ensureServerCreated();
        httpServer.setServerSocketFactory(serverSocketFactory);
    }

    void addDefaultHandlers() {
        addRoute("/{*}", new DeathHandler());
    }

    void createExecutor() {
        if (config.isVirtualThreads()) {
            executor = Executors.newCachedThreadPool(new NamedThreadFactory("Http-Worker-"));
        } else {
            executor = new ThreadPoolExecutor(config.getThreads(), config.getThreads(), 0L,
                    TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                    new NamedThreadFactory("Http-Worker-"));
        }
    }

    void createHttpServer(int port) {
        httpServer = new HTTPServer(port);
        if (System.getProperty("javax.net.ssl.keyStore") != null) // enable SSL if configured
            httpServer.setServerSocketFactory(SSLServerSocketFactory.getDefault());
    }

    /**
     * 启动服务
     *
     * @return 端口
     */
    public int run(AppConfig config) throws IOException {
        if (config != null) {
            this.config = config;
            if (isEmpty(config.getHost())) {
                config.setHost(AppConfig.DEFAULT_HOST);
            }
            if (config.getPort() < 0 && config.getPort() > 65535) {
                config.setPort(AppConfig.DEFAULT_PORT);
            }

            if (config.getThreads() < 1) {
                config.setThreads(AppConfig.DEFAULT_THREADS);
            }
        }

        return run();
    }

    /**
     * 启动服务
     *
     * @return 端口
     */
    public int run() throws IOException {
        createExecutor();
        httpServer.setExecutor(executor);
        httpServer.start();
        Log.info("Server is start at: " + config.getPort());
        return config.getPort();
    }

    /**
     * 停止服务器
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        Log.info("Server is shutdown.");
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        shutdown();
    }

    @Data
    public static class AppConfig {
        static final int                   DEFAULT_THREADS = Math.max(200, 32 * Runtime.getRuntime().availableProcessors());
        static final int                   DEFAULT_PORT    = 8000;
        static final String                DEFAULT_HOST    = "0.0.0.0";

        HTTPServer.GzipConfig gzipConfig   = new HTTPServer.GzipConfig();
        HTTPServer.CorsConfig corsConfig   = new HTTPServer.CorsConfig();
        int                   port         = DEFAULT_PORT;
        String                host         = DEFAULT_HOST;
        int                   threads      = DEFAULT_THREADS;
        boolean               virtualThreads = true;
        Charset               bodyEncoding = StandardCharsets.UTF_8;
        Charset               uriEncoding  = StandardCharsets.UTF_8;
        boolean               enableGzip   = false;
        boolean               enableCors   = false;

        public HTTPServer.GzipConfig getGzipConfig() {
            return gzipConfig;
        }

        public HTTPServer.CorsConfig getCorsConfig() {
            return corsConfig;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public boolean isVirtualThreads() {
            return virtualThreads;
        }

        public void setVirtualThreads(boolean virtualThreads) {
            this.virtualThreads = virtualThreads;
        }
    }
}
