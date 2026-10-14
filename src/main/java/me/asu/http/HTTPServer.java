package me.asu.http;

import lombok.Data;
import lombok.Getter;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static me.asu.http.Bytes.CRLF;
import static me.asu.http.Bytes.getBytes;
import static me.asu.http.HeaderKey.*;
import static me.asu.http.Streams.transfer;
import static me.asu.http.Strings.trimRight;

/**
 * 用于替换 JDK 的 HttpServer。
 */
@Data
public class HTTPServer {

    protected String name;
    protected volatile String directoryIndex = "index.html";
    protected volatile boolean allowGeneratedIndex;
    protected volatile boolean enableCors;
    protected final Set<String> methods = new CopyOnWriteArraySet<>();
    protected final ContextInfo rootContext = new ContextInfo("", ""); // root of context tree
    protected volatile int port = 80;
    protected volatile int nThreads = 256;
    protected volatile int socketTimeout = 5000;
    protected volatile ServerSocketFactory serverSocketFactory;
    protected volatile boolean secure = false;
    protected volatile Executor executor;
    protected volatile ServerSocket serv;
    protected CorsConfig corsConfig = new CorsConfig();
    protected GzipConfig gzipConfig = new GzipConfig();

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isEnableCors() {
        return enableCors;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * 将一个上下文及其相应的上下文处理器添加至本服务器。
     * 路径通过去除尾部斜杠（根路径除外）进行规范化，并且
     * 可以包含被花括号包围的路径参数，例如“/users/{username}”
     * 或者以星号开头的通配符路径参数，例如“/path/{*}”
     * （带斜杠）或“/path{*any}”（可以带或不带斜杠）。
     *
     * @param path    上下文的路径（必须以‘/’开头）
     * @param handler 该路径的上下文处理器
     * @param methods 该上下文处理器支持的HTTP方法（默认为“GET”）
     * @throws IllegalArgumentException 如果路径格式不正确
     */
    public void addContext(String path, ContextHandler handler, String... methods) {
        if (path == null || !path.startsWith("/") && !path.equals("*"))
            throw new IllegalArgumentException("invalid path: " + path);
        if (path.length() > 1)
            path = trimRight(path, '/'); // remove trailing slash
        ContextInfo context = rootContext.getContext(path, 0, true, 0, null);
        context.addHandler(handler, methods);
    }


    /**
     * 构造一个可以在默认HTTP端口80上接受连接的HTTP服务器。
     * 注意：必须调用 {@link #start()} 方法以开始接受连接。
     */
    public HTTPServer() {
        this(80);
    }


    /**
     * 构造一个可以在给定端口上接受连接的 HTTPServer。
     * 注意：必须调用 {@link #start()} 方法以开始接受连接。
     *
     * @param port 该服务器将接受连接的端口
     */
    public HTTPServer(int port) {
        setPort(port);
    }

    /**
     * 设置用于创建服务器套接字的工厂。
     * 如果为 null 或未设置，则使用默认的 {@link ServerSocketFactory#getDefault()}。
     * 对于安全套接字（HTTPS），请使用 SSLServerSocketFactory 实例。
     * 对于 HTTPS，端口通常也应更改，例如使用 443 端口而不是 80。
     * <p>
     * 如果使用 {@link SSLServerSocketFactory#getDefault()} 返回的默认 SSLServerSocketFactory，
     * 则必须设置适当的系统属性以配置默认的 JSSE 提供程序，例如
     * {@code javax.net.ssl.keyStore} 和 {@code javax.net.ssl.keyStorePassword}。
     *
     * @param factory 要使用的服务器套接字工厂
     */
    public void setServerSocketFactory(ServerSocketFactory factory) {
        this.serverSocketFactory = factory;
        this.secure = factory instanceof SSLServerSocketFactory;
    }

    /**
     * 启动此服务器。如果服务器已经启动，则不执行任何操作。
     * 注意：一旦服务器启动，服务器的配置更改方法
     * 不得再使用。如需修改配置，必须首先停止服务器。
     *
     * @throws IOException 如果服务器无法开始接受连接
     */
    public synchronized void start() throws IOException {
        if (serv != null) return;
        if (serverSocketFactory == null) serverSocketFactory = ServerSocketFactory.getDefault(); // plain sockets
        serv = serverSocketFactory.createServerSocket();
        serv.setReuseAddress(true);
        serv.bind(new InetSocketAddress(port));
        if (executor == null) {
            executor = Executors.newCachedThreadPool();
        }
        Response.gzipConfig = gzipConfig;
        new SocketHandlerThread().start();
    }

    /**
     * 停止此服务器。如果它已经停止，则不执行任何操作。
     * 新的连接将不被接受，但现有的连接可以继续完成。
     * 如果设置了{@link #setExecutor 执行器}，则必须单独关闭它。
     * 默认的{@code 执行器}将在60秒非活动后终止其线程。
     */
    public synchronized void stop() {
        try {
            if (serv != null) serv.close();
        } catch (IOException ignore) {}
        serv = null;
    }

    /**
     * 处理通过给定流进行的单个连接的通信。
     * 在连接上处理多个后续事务，直到流关闭、发生错误，或请求
     * 包含一个“Connection: close”标题，明确请求在事务结束后关闭连接。
     *
     * @param in   从中读取传入请求的流
     * @param out  将传出响应写入的流
     * @param sock 连接的套接字
     * @throws IOException 如果发生错误
     */
    protected void handleConnection(InputStream in, OutputStream out, Socket sock) throws IOException {
        in = new BufferedInputStream(in, 4096);
        out = new BufferedOutputStream(out, 4096);
        Request req;
        Response resp;
        do {
            // create request and response and handle transaction
            req = null;
            resp = new Response(out);
            try {
                req = new Request(this, in, sock);
                resp.setClientCapabilities(req);
                if (preprocess(req, resp)) handleMethod(req, resp);
            } catch (Throwable t) { // unhandled errors (not normal error responses like 404)
                if (req == null) { // error reading request
                    if (t instanceof IOException && t.getMessage().contains("missing request line"))
                        break; // we're not in the middle of a transaction - so just disconnect
                    resp.getHeaders().add(CONNECTION, CLOSE); // about to close connection
                    if (t instanceof InterruptedIOException) // e.g. SocketTimeoutException
                        resp.sendError(408);
                    else if (t instanceof IOException && t.getMessage().contains("URI too long"))
                        resp.sendError(414); // [RFC9112#3] must return 414 if URI is too long
                    else
                        resp.sendError(400, "Invalid request: " + t.getMessage());
                } else if (!resp.headersSent()) { // if headers were not already sent, we can send an error response
                    resp = new Response(out); // ignore whatever headers may have already been set
                    resp.getHeaders().add(CONNECTION, CLOSE); // about to close connection
                    resp.sendError(500, "Error processing request: " + t.getMessage());
                } // otherwise just abort the connection since we can't recover
                break; // proceed to close connection
            } finally {
                resp.close(); // close response and flush output
                // consume any leftover body data so next request can be processed
                if (req != null) transfer(req.getBody(), null, -1);
                // [RFC9112#9.3/9.6] persist connection unless client or server close explicitly (or legacy client)
                if (req != null) req.cleanup();
            }

        } while (!CLOSE.equalsIgnoreCase(resp.getHeaders().get(CONNECTION))
                && isVer11(req) && serv != null); // also close if the server is shutting down
    }

    /**
     * 预处理，执行各种验证检查 及所需的特殊头部处理， 可能返回一个适当的响应。
     *
     * @param req  请求
     * @param resp 响应
     * @return 是否应对该交易执行进一步处理
     * @throws IOException 如果发生错误
     */
    protected boolean preprocess(Request req, Response resp) throws IOException {
        // validate request
        if (isVer11(req)) {
            Headers headers = req.getHeaders();
            // [RFC9112#3.2] missing or multiple Host header gets 400
            String host = headers.get(HOST);
            if (host == null || host.indexOf(',') > -1) {
                resp.sendError(400, "Exactly one Host header is required");
                return false;
            }
            // return a continue response before reading body
            String expect = headers.get(EXPECT);
            if (expect != null) {
                if (expect.equalsIgnoreCase(CONTINUE_100)) {
                    Response tempResp = new Response(resp.getOutputStream());
                    tempResp.sendHeaders(100);
                    resp.getOutputStream().flush();
                } else {
                    // [RFC9110#10.1.1] if unknown expect, send 417
                    resp.sendError(417);
                    return false;
                }
            }
        } else if (!isSupportVer(req)) {  // [RFC9112#C1] drop HTTP/0.9 support
            resp.sendError(505);
            return false;
        }
        return true;
    }

    protected boolean isVer11(Request req) {
        int version = req.getVersion();
        return version == 11;
    }

    protected boolean isSupportVer(Request req) {
        int version = req.getVersion();
        return version >= 10 && version < 20;
    }

    /**
     * 根据请求方法处理交易。
     *
     * @param req  交易请求
     * @param resp 交易响应（响应将写入该处）
     * @throws IOException 如果发生错误
     */
    protected void handleMethod(Request req, Response resp) throws IOException {
        String method = req.getMethod();
        Map<String, ContextHandler> handlers = req.getContext().getHandlers();
        // [RFC9110#9.1] GET and HEAD must be supported
        if (method.equals("GET") || handlers.containsKey(method)) {
            serve(req, resp); // method is handled by context handler (or 404)
        } else if (method.equals("HEAD")) { // default HEAD handler
            req.method = "GET"; // identical to a GET
            resp.setDiscardBody(true); // process normally but discard body
            serve(req, resp);
        } else if (method.equals("TRACE")) { // default TRACE handler
            handleTrace(req, resp);
        } else {
            handleOption(req, resp);
        }
    }

    void handleOption(Request req, Response resp) throws IOException {
        String method = req.getMethod();
        Map<String, ContextHandler> handlers = req.getContext().getHandlers();
        Set<String> methods = new LinkedHashSet<>();
        methods.addAll(Arrays.asList("GET", "HEAD", "TRACE", "OPTIONS")); // built-in methods
        // "*" is a special server-wide (no-context) request supported by OPTIONS
        boolean isServerOptions = req.getPath().equals("*") && method.equals("OPTIONS");
        methods.addAll(isServerOptions ? req.server.methods : handlers.keySet());
        final Headers respHeaders = resp.getHeaders();
        respHeaders.add("Allow", String.join(", ", methods)); // [RFC9110#10.2.1]
        if (method.equals("OPTIONS")) { // default OPTIONS handler
            respHeaders.add("Content-Length", "0"); // no content
            if (isEnableCors()) {
                if (corsConfig.getAccessControlAllowCredentials() != null) {
                    respHeaders.add("Access-Control-Allow-Credentials",
                            corsConfig.getAccessControlAllowCredentials().toString());
                }
                if (Strings.isNotEmpty(corsConfig.getAccessControlAllowOrigin())) {
                    respHeaders.add("Access-Control-Allow-Origin",
                            corsConfig.getAccessControlAllowOrigin());
                } else {
                    respHeaders.add("Access-Control-Allow-Origin", "*");
                }
                if (Strings.isNotEmpty(corsConfig.getAccessControlAllowMethods())) {
                    respHeaders.add("Access-Control-Allow-Methods",
                            corsConfig.getAccessControlAllowMethods());
                } else {
                    respHeaders.add("Access-Control-Allow-Methods",
                            "OPTIONS, GET, POST, PUT, PATCH, DELETE, HEAD");
                }
                if (Strings.isNotEmpty(corsConfig.getAccessControlExposeHeaders())) {
                    respHeaders.add("Access-Control-Expose-Headers",
                            corsConfig.getAccessControlExposeHeaders());
                }
                if (corsConfig.getAccessControlMaxAge() != null) {
                    respHeaders.add("Access-Control-Max-Age",
                            corsConfig.getAccessControlMaxAge().toString());
                }
            }

            resp.sendHeaders(204);
        } else if (req.server.methods.contains(method)) {
            resp.sendError(405); // supported by server, but not this context (nor built-in)
        } else {
            resp.sendError(501); // unsupported method
        }
    }

    /**
     * 处理 TRACE 方法请求。
     *
     * @param req  请求
     * @param resp 响应，内容将写入此响应中
     * @throws IOException 若发生错误
     */
    void handleTrace(Request req, Response resp) throws IOException {
        resp.sendHeaders(200, -1, -1, null, "message/http", null);
        int version = req.getVersion();
        OutputStream out = resp.getBody();
        out.write(getBytes("TRACE ", req.getUri().toString(), " HTTP/" + version / 10 + "." + version % 10));
        out.write(CRLF);
        req.getHeaders().writeTo(out); // warning: this may disclose sensitive headers (cookies, auth etc.)
        transfer(req.getBody(), out, -1); // [RFC9110#9.3.8] client must not send content (but we echo it anyway)
    }

    /**
     * 通过调用所请求的上下文（路径）和HTTP方法的上下文处理器，来为请求提供内容。
     *
     * @param req  请求
     * @param resp 内容写入的响应
     * @throws IOException 如果发生错误
     */
    void serve(Request req, Response resp) throws IOException {
        // get context handler to handle request
        final ContextInfo context = req.getContext().getContext(req.getPath());
        final Map<String, ContextHandler> handlers = context.getHandlers();

        ContextHandler handler = handlers.get(req.getMethod());

        if (handler == null) {
            resp.sendError(404);
            return;
        }
        // serve request
        int status = 404;
        // add directory index if necessary
        String path = req.getPath();
        if (path.endsWith("/")) {
            String index = allowGeneratedIndex ? directoryIndex : null;
            if (index != null) {
                req.setPath(path + index); // 重新计算上下文和处理程序，并使用更新后的路径。
                ContextHandler indexHandler = handlers.get(req.getMethod());
                if (indexHandler != null)
                    status = indexHandler.serve(req, resp);
                req.setPath(path);
            }
        }
        if (status == 404)
            status = handler.serve(req, resp);

        if (status > 0) resp.sendError(status);
    }

    /**
     * {@code ContextInfo} 类保存单个上下文的信息。
     * 它同时作为用于将请求路径与上下文进行匹配的基数树中的一个节点。
     */
    @Getter
    public class ContextInfo {

        protected String path; // 从根节点到该节点的完整路径。
        protected String segment; // 该节点的路径段仅此而已。
        protected String param; // 参数名称（如果不是参数节点则为 null）
        protected int rank; // 定义匹配上下文的优先顺序。
        protected ContextInfo[] children = new ContextInfo[0];
        protected Map<String, ContextHandler> handlers = new ConcurrentHashMap<>(2);

        public Map<String, ContextHandler> getHandlers() {
            return handlers;
        }

        /**
         * 利用给定的上下文路径和片段构造一个上下文信息对象。
         *
         * @param path    完整的上下文路径（不包含结尾的斜杠）
         * @param segment 与此节点匹配的路径片段
         */
        public ContextInfo(String path, String segment) {
            this.path = path;
            this.segment = segment;
            if (segment.length() > 0 && segment.charAt(0) == '{')
                this.param = segment.substring(1, segment.length() - 1);
            // calculate sorting rank by number of literals (primary) and params (secondary)
            boolean param = false;
            for (int i = 0; i < path.length(); i++) {
                if (!param)
                    rank += path.charAt(i) == '{' ? 1 : 1 << 16;
                param = param && path.charAt(i) != '}' || path.charAt(i) == '{';
            }
        }

        /**
         * 返回至少被一个上下文明确支持的所有 HTTP 方法
         * （这可能包括或不包括具有必需或内置支持的方法）。
         *
         * @return 至少被一个上下文明确支持的所有 HTTP 方法
         */
        public Set<String> getMethods() {
            return methods;
        }

        /**
         * 返回给定路径的上下文信息。
         * <p>
         * 如果未找到给定路径的上下文，将返回根上下文。
         *
         * @param path 上下文的路径
         * @return 给定路径的上下文信息，如果不存在则返回根上下文
         */
        public ContextInfo getContext(String path) {
            // 所有的上下文路径均不带有结尾斜杠。
            return rootContext.getContext(path, 0, false, 0, null);
        }

        /**
         * 为给定的HTTP方法添加（或替换）上下文处理器。
         *
         * @param handler 上下文处理器
         * @param methods 由处理器支持的HTTP方法（默认为“GET”）
         */
        public void addHandler(ContextHandler handler, String... methods) {
            if (methods.length == 0)
                methods = new String[]{"GET"};
            for (String method : methods) {
                handlers.put(method, handler);
                HTTPServer.this.methods.add(method);
            }
        }

        /**
         * 返回或创建与给定路径匹配的上下文。
         * <p>
         * 该方法实现为递归的基数树节点获取/创建方法，其中节点为包含路径段的上下文信息
         * (ContextInfos)，每个路径参数位于一个单独的节点中。尽管稍显复杂，该数据结构
         * 完全支持 URL 匹配、路径参数、通配符、排名等所有功能，并且性能良好。
         * <p>
         * 在匹配字面量段或创建字面量段或参数段时，匹配是按字面意义进行的。当匹配一个
         * 参数时，匹配相当于不贪婪的正则表达式 "([^/]+?)"，即匹配到下一个斜杠（或结束）
         * 之前的最小字符数（但至少一个）。名称以星号开头的路径参数是通配符参数，能够
         * 匹配零个或多个字符，包括斜杠，相当于正则表达式 "(.*?)"。
         * <p>
         * 仅当匹配的节点是有效的上下文，即具有上下文处理器时，才会返回该节点。如果多个
         * 上下文匹配路径，则将返回排名最高的那个。
         *
         * @param path   一个上下文路径
         * @param i      在路径中的索引，应该与该上下文的段进行匹配
         * @param create 指定如果上下文不存在是否应创建该上下文
         * @param rank   允许匹配节点的最小排名
         * @param params 一个列表，用于添加匹配到的路径参数（如果不为 null）
         * @return 匹配或创建的上下文，如果没有匹配，则返回 null
         * （根上下文在没有匹配时返回自身）
         */
        protected ContextInfo getContext(String path, int i, boolean create, int rank, List<String[]> params) {
            int j = 0;                  // 当前节点的段内匹配索引
            int len = path.length();      // 完整路径长度
            String segment = this.segment;       // 当前节点的段落
            int slen = segment.length();   // 当前段落长度
            boolean param = this.param != null; // 是否是参数表达式

            if (!param || create) {
                for (; j < slen && i < len && segment.charAt(j) == path.charAt(i); i++, j++) ;
                if (j < slen && (j == 0 || !create || param))
                    return null;
            }

            // 搜索模式 - 当前节点完全匹配，因此继续搜索子节点。
            ContextInfo context = null; // the best matched context
            if (!create) {
                boolean wildcard = param && this.param.charAt(0) == '*';
                int start = i;
                if (param) // param - match reluctantly until slash or end, i.e. "([^/]+?)"
                    for (; i < len && (path.charAt(i) != '/' || wildcard); i++) ; // find maximal param end
                int end = i;
                // 请您开始翻译：查找与其余路径（递归地）匹配的最高排名子项。
                for (ContextInfo child : children) {
                    for (j = start + (param && !wildcard ? 1 : 0); j <= end; j++) { // param match >= 1
                        ContextInfo found = child.getContext(path, j, false, rank, params);
                        if (found != null) {
                            context = found; // 最佳完全匹配的后裔上下文
                            rank = found.rank; // 迄今为止最佳匹配排名
                            i = j; // 当前段落匹配的结束位置。
                        }
                    }
                }
                // 如果没有子节点匹配，但这是一个匹配节点（或根上下文）。
                if (context == null && (i == 0 || this.rank > rank && (i == len
                        || i == len - 1 && path.charAt(i) == '/') && !getHandlers().isEmpty())) {
                    context = this; // 返回此节点。
                    if (params != null)
                        params.clear(); // 重置新最佳匹配的参数。
                }
                if (params != null && param && context != null) // add this node's param
                    params.add(new String[]{this.param, path.substring(start, i)});
                return context;
            }

            // create mode - either split this node or continue to children
            if (j == slen) { // full segment match - continue to children
                for (ContextInfo child : children) {
                    context = child.getContext(path, i, true, rank, null);
                    if (context != null) // new node was created by descendant
                        return context; // at most one child can match literally, so we're done
                }
            } else { // partial segment match - split this node at the point the path diverges
                context = new ContextInfo(this.path, segment.substring(j));
                context.children = this.children;
                context.handlers.putAll(this.handlers);
                this.path = path.substring(0, i);
                this.segment = segment.substring(0, j);
                this.rank -= (slen - j) << 16;
                this.handlers.clear();
                this.children = new ContextInfo[]{context};
            }
            if (i == len) // 此节点（无论是之前存在的节点还是当前分裂的节点）是上下文。
                return this;
            // 附加新的子节点，同时递归地将参数拆分为单独的节点。
            int start = path.indexOf('{', i);
            int end = start < 0 ? len : start > i ? start : path.indexOf('}', start) + 1;
            if (end == 0)
                throw new IllegalArgumentException("unterminated param: " + path);
            context = new ContextInfo(path.substring(0, end), path.substring(i, end));
            ContextInfo[] children = new ContextInfo[this.children.length + 1];
            System.arraycopy(this.children, 0, children, 0, this.children.length);
            children[this.children.length] = context;
            this.children = children;
            return end == len ? context : context.getContext(path, i, true, rank, null);
        }
    }

    /**
     * {@code SocketHandlerThread} 类负责处理已接受的套接字。
     */
    class SocketHandlerThread extends Thread {
        @Override
        public void run() {
            setName(getClass().getSimpleName() + "-" + port);
            try {
                final ServerSocket serv = HTTPServer.this.serv; // keep local to avoid NPE when stopped
                while (serv != null && !serv.isClosed()) {
                    final Socket sock = serv.accept();
                    executor.execute(() -> {
                        try {
                            try {
                                sock.setSoTimeout(socketTimeout);
                                sock.setTcpNoDelay(true); // we buffer anyway, so improve latency
                                handleConnection(sock.getInputStream(), sock.getOutputStream(), sock);
                            } finally {
                                try {
                                    // [RFC9112#9.6] close socket gracefully
                                    // (except SSL socket which doesn't support half-closing)
                                    if (!(sock instanceof SSLSocket)) {
                                        sock.shutdownOutput(); // half-close socket (only output)
                                        transfer(sock.getInputStream(), null, -1); // consume input
                                    }
                                } finally {
                                    sock.close(); // and finally close socket fully
                                }
                            }
                        } catch (IOException ignore) {}
                    });
                }
            } catch (IOException ignore) {}
        }
    }

    @Data
    static class CorsConfig {
        /**
         * 该字段必填。它的值要么是请求时Origin字段的具体值，要么是一个*，表示接受任意域名的请求。
         */
        String accessControlAllowOrigin = "*";
        /**
         * 该字段必填。它的值是逗号分隔的一个具体的字符串或者*，表明服务器支持的所有跨域请求的方法。注意，返回的是所有支持的方法，而不单是浏览器请求的那个方法。这是为了避免多次"预检"请求。
         */
        String accessControlAllowMethods = "*";
        /**
         * 该字段可选。CORS请求时，XMLHttpRequest对象的getResponseHeader()方法只能拿到6个基本字段：Cache-Control、Content-Language、Content-Type、Expires、Last-Modified、Pragma。如果想拿到其他字段，就必须在Access-Control-Expose-Headers里面指定。
         */
        String accessControlExposeHeaders;
        /**
         * 该字段可选。它的值是一个布尔值，表示是否允许发送Cookie.默认情况下，不发生Cookie，即：false。对服务器有特殊要求的请求，比如请求方法是PUT或DELETE，或者Content-Type字段的类型是application/json，这个值只能设为true。如果服务器不要浏览器发送Cookie，删除该字段即可。
         */
        Boolean accessControlAllowCredentials;
        /**
         * 该字段可选，用来指定本次预检请求的有效期，单位为秒。在有效期间，不用发出另一条预检请求。
         */
        Long accessControlMaxAge;

        public String getAccessControlAllowOrigin() {
            return accessControlAllowOrigin;
        }

        public String getAccessControlAllowMethods() {
            return accessControlAllowMethods;
        }

        public String getAccessControlExposeHeaders() {
            return accessControlExposeHeaders;
        }

        public Boolean getAccessControlAllowCredentials() {
            return accessControlAllowCredentials;
        }

        public Long getAccessControlMaxAge() {
            return accessControlMaxAge;
        }
    }

    @Data
    public static class GzipConfig {
        final List<String> compressibleContentTypes = new ArrayList<>();
        long minLengthUsingGzip = 2048000;

        {
            compressibleContentTypes.add("text/*");
            compressibleContentTypes.add("*icon");
            compressibleContentTypes.add("*+xml");
            compressibleContentTypes.add("*/json");
            compressibleContentTypes.add("*/js");
            compressibleContentTypes.add("*/javascript");
            compressibleContentTypes.add("application/x-javascript");
            compressibleContentTypes.add("application/x-json");
        }
    }
}
