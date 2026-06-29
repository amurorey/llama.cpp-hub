# 基于 Netty 构建 Web 服务

本文档介绍本项目（llama-server）基于 Netty 4.x 构建 HTTP 服务器的架构设计，重点覆盖 HTTP 协议的编解码、请求路由、流式处理、响应发送等环节。

---

## 1. 整体架构

```
                         ┌──────────────────────────────────────────┐
                         │              Netty ServerBootstrap       │
                         │   bossGroup(1) + workerGroup(4)          │
                         │   NioServerSocketChannel                  │
                         │   SO_BACKLOG=1024, SO_KEEPALIVE=true      │
                         └──────────────────────────────────────────┘
                                      │
                                      ▼
                         ┌──────────────────────────────────────────┐
                         │   HttpHttpsUnificationHandler            │
                         │   (协议探测：首字节 0x16 = TLS ClientHello)│
                         │   探测完成 → 移除自身，切换 Pipeline       │
                         └──────────────────────────────────────────┘
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
            ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐
            │   HTTPS 模式  │  │   HTTP 模式   │  │ 308 重定向到HTTPS │
            └──────────────┘  └──────────────┘  └──────────────────┘
                    │                 │
                    └────────┬────────┘
                             ▼
              ┌────────────────────────────────────────────────┐
              │                 Pipeline 业务层                  │
              │                                                │
              │  1. OpenAIChatStreamingHandler  (拦截超大请求体)  │
              │  2. FileUploadRouterHandler     (分块上传拦截)     │
              │  3. EasyChatStreamingHandler    (流式写入临时文件)  │
              │  4. HttpObjectAggregator(16MB)  (聚合为 FullHttpRequest)
              │  5. ChunkedWriteHandler         (ChunkedFile 输出)
              │  6. WebSocketServerProtocolHandler("/ws")
              │  7. WebSocketServerHandler
              │  8. BasicRouterHandler          (系统 API + 静态资源)
              │  9. CompletionRouterHandler     (创作服务 API)
              │ 10. FileDownloadRouterHandler   (下载 API)
              │ 11. LlamaRouterHandler          (核心 API 路由)
              │ 12. CloseOnExceptionHandler     (兜底)
              └────────────────────────────────────────────────┘
```

---

## 2. 服务器启动

### 2.1 Bootstrap 配置

`LlamaServer.bindOpenAI(int port)`（`LlamaServer.java:1057`）：

```java
EventLoopGroup bossGroup = new NioEventLoopGroup(1);
EventLoopGroup workerGroup = new NioEventLoopGroup(4);

ServerBootstrap bootstrap = new ServerBootstrap();
bootstrap.group(bossGroup, workerGroup)
    .channel(NioServerSocketChannel.class)
    .option(ChannelOption.SO_BACKLOG, 1024)
    .childOption(ChannelOption.SO_KEEPALIVE, true)
    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(32 * 1024, 48 * 1024))
    .childHandler(new ChannelInitializer<SocketChannel>() {
        @Override
        protected void initChannel(SocketChannel ch) throws Exception {
            ch.pipeline().addLast(
                new HttpHttpsUnificationHandler(
                    httpsSslContext, port, WEBSOCKET_PATH, MAX_HTTP_CONTENT_LENGTH));
        }
    });

ChannelFuture future = bootstrap.bind(port).sync();
```

**关键参数说明**：

| 参数 | 值 | 含义 |
|------|-----|------|
| Boss 线程数 | 1 | 仅处理 TCP Accept 事件 |
| Worker 线程数 | 4 | 处理所有 I/O 读/写事件 |
| SO_BACKLOG | 1024 | 等待连接队列长度 |
| SO_KEEPALIVE | true | 开启 TCP 心跳探测 |
| WRITE_BUFFER_LOW_WATER_MARK | 32 KB | 写缓冲低水位，低于此值恢复写 |
| WRITE_BUFFER_HIGH_WATER_MARK | 48 KB | 写缓冲高水位，高于此值暂停写 |
| MAX_HTTP_CONTENT_LENGTH | 16 MB | HttpObjectAggregator 最大聚合体 |

---

## 3. 协议探测 — HTTP/HTTPS 统一端口

`HttpHttpsUnificationHandler`（`HttpHttpsUnificationHandler.java:31`）实现了 **同一个 TCP 端口同时接受 HTTP 和 HTTPS 请求** 的功能。

### 3.1 探测原理

TLS 协议的 ClientHello 消息的第一个字节固定为 `0x16`（ContentTypeHandshake）。HTTP 请求的第一个字节是 ASCII 字符（如 `G`、`P`、`O`）。

```
channelActive() → 安装 ReadTimeoutHandler(10s)，防止恶意空连接挂起

decode() {
    读取第一个字节：
      if 首字节 == 0x16 (TLS ClientHello)
          → enableHttps()   ：插入 SslHandler，走 HTTPS 协议栈
      else if HTTPS 已启用
          → enableHttpRedirect()：插入 HttpServerCodec + 308 重定向处理器
      else
          → enableHttp()   ：直接走普通 HTTP 协议栈

    移除 ReadTimeoutHandler 和自身，已读取的字节重新交给新 Pipeline
}
```

### 3.2 HTTPS 模式

```
pipeline.addLast(new SslHandler(engine));
// 后续插入全部业务 handler
```

### 3.3 HTTP 重定向到 HTTPS

`HttpToHttpsRedirectHandler`（`HttpToHttpsRedirectHandler.java:20`）使用 **308 Permanent Redirect**，保留请求方法（POST 不会降级为 GET），同时支持 WebSocket 的 `ws://` → `wss://` 重定向：

```java
boolean isWebSocket = "websocket".equalsIgnoreCase(request.headers().get("UPGRADE"));
String scheme = isWebSocket ? "wss" : "https";
String location = scheme + "://" + host + portPart + uri;

FullHttpResponse response = new DefaultFullHttpResponse(
    HttpVersion.HTTP_1_1, new HttpResponseStatus(308, "Permanent Redirect"));
response.headers().set(HttpHeaderNames.LOCATION, location);
ctx.writeAndFlush(response);
ctx.close();
```

---

## 4. Pipeline 业务层详解

### 4.1 Handler 顺序与职责

```
┌────────────────────────────────────────────────────────────────┐
│  序号  │ Handler                        │ 阶段           │
│ ├──────┼────────────────────────────────┼───────────────────┤
│   1    │ OpenAIChatStreamingHandler     │ HttpObject 阶段    │
│   2    │ FileUploadRouterHandler        │ HttpObject 阶段    │
│   3    │ EasyChatStreamingHandler       │ HttpObject 阶段    │
│   4    │ HttpObjectAggregator(16MB)     │ 聚合为 FullHttpRequest
│   5    │ ChunkedWriteHandler            │ Chunked 输出       │
│   6    │ WebSocketServerProtocolHandler │ WebSocket 握手     │
│   7    │ WebSocketServerHandler         │ WebSocket 帧处理   │
│   8    │ BasicRouterHandler             │ 系统 API + 静态资源│
│   9    │ CompletionRouterHandler        │ 创作服务 API       │
│  10    │ FileDownloadRouterHandler      │ 下载 API           │
│  11    │ LlamaRouterHandler             │ 核心 API 路由      │
│  12    │ CloseOnExceptionHandler        │ 兜底异常处理       │
└────────────────────────────────────────────────────────────────┘
```

### 4.2 HttpObject 阶段 Handler

前三个 Handler 工作在 **`HttpObject` 阶段**（`HttpServerCodec` 解码后、`HttpObjectAggregator` 聚合前），直接处理 `HttpRequest` 和 `HttpContent` 帧：

#### 4.2.1 OpenAIChatStreamingHandler

拦截 `/v1/chat/completions`、`/v1/messages`、`/v1/complete` 等聊天端点的超大请求体：

- 在 `HttpRequest` 到达时检测 URI 是否命中聊天端点
- 命中后设置 `intercepting = true`，后续所有 `HttpContent` 帧由本 Handler 消费
- 创建 `ChatStreamSession` 在独立线程中"边收边发"，在收到请求体时就解析出 `model` 字段并开始建立转发连接
- 连接断开时主动 `cancel()` 释放底层 llama.cpp 进程连接

#### 4.2.2 FileUploadRouterHandler

拦截 `POST /api/uploads` 分块上传请求：

- 在 `HttpRequest` 中识别路径，创建 `RandomAccessFile` 写入磁盘
- 每个 `HttpContent` 帧的 `ByteBuf` 直接写入 RAF，无需内存中转
- 完成后通过 `ctx.channel().attr()` 将 RAF 和文件路径传递给下游
- 连接断开时自动清理临时文件

#### 4.2.3 EasyChatStreamingHandler

拦截 `POST /api/chat/stream-chat`，将请求体写入 `/cache/temp/easychat-*.bin`：

- 创建临时文件，所有 `HttpContent` 帧直接追加写入
- 完成后通过 `STREAMING_BODY_FILE` 属性键传递文件路径
- 构造一个空的 `DefaultFullHttpRequest` 传递给下游（因为实际数据已在磁盘上）

### 4.3 聚合层

```
HttpObjectAggregator(maxContentLength = 16 * 1024 * 1024)
```

将所有 `HttpContent` 帧聚合成一个 `FullHttpRequest`，然后传递给后续 handler。这就是为什么 `BasicRouterHandler`、`LlamaRouterHandler` 等下游 handler 接收的都是 `FullHttpRequest` 类型。

### 4.4 ChunkedWriteHandler

为后续 handler 的 `ChunkedFile` / `ChunkedInput` 流式输出提供支持。Netty 默认不会处理 `ChunkedInput`，需要显式添加此 handler。

---

## 5. 请求路由

所有业务 handler 在 `channelRead0(FullHttpRequest)` 中处理请求。采用 **URI 前缀匹配** 的路由策略。

### 5.1 BasicRouterHandler

处理系统 API、llama.cpp 原生 API 和静态资源。

#### 5.1.1 API 请求判断

`isApiRequest(String uri)` 判断 URI 是否为 API 请求：

```java
private boolean isApiRequest(String uri) {
    // 1. 通用系统 API
    if (uri.startsWith("/api/") ||
        uri.startsWith("/session") ||
        uri.startsWith("/tokenize") ||
        uri.startsWith("/apply-template") ||
        uri.startsWith("/infill"))
        return true;
    // 2. OpenAI 标准路径
    if (uri.startsWith("/v1")) return true;
    // 3. llama.cpp 基础端点
    if (uri.startsWith("/llama.cpp/models") ||
        uri.startsWith("/llama.cpp/v1/chat") ||
        uri.startsWith("/llama.cpp/v1/models") ||
        uri.startsWith("/llama.cpp/tools") ||
        uri.startsWith("/llama.cpp/slots") ||
        uri.startsWith("/llama.cpp/props"))
        return true;
    // 4. 显式端点
    if (uri.startsWith("/models") ||
        uri.startsWith("/chat/completion") ||
        uri.startsWith("/completions") ||
        uri.startsWith("/embeddings") ||
        uri.startsWith("/rerank") ||
        uri.startsWith("/responses") ||
        uri.startsWith("/slots"))
        return true;
    return false;
}
```

#### 5.1.2 专用端点直接处理

```java
if (uri.startsWith("/llama.cpp/props"))     → handleProps(ctx, request)
if (uri.startsWith("/llama.cpp/tools"))     → handleTools(ctx, request)
if (uri.startsWith("/llama.cpp/models/load"))  → handleLoadModel(ctx, request)
if (uri.startsWith("/llama.cpp/models/unload")) → handleUnloadModel(ctx, request)
```

#### 5.1.3 Controller Pipeline

其余 API 请求交给 `LinkedList<BaseController>` 按序处理：

```java
pipeline.add(new ChatStateController());
pipeline.add(new EasyChatController());
pipeline.add(new HuggingFaceController());
pipeline.add(new LlamacppController());
pipeline.add(new ModelActionController());
pipeline.add(new PerplexityController());
pipeline.add(new ModelInfoController());
pipeline.add(new ModelPathController());
pipeline.add(new NodeController());
pipeline.add(new ProxyController());
pipeline.add(new ParamController());
pipeline.add(new ToolController());
pipeline.add(new SystemController());
pipeline.add(new UsageReportController());
pipeline.add(new AutoLoadPolicyController());
pipeline.add(new CertController());
pipeline.add(new BuildController());

// 路由逻辑
for (BaseController c : pipeline) {
    handled = c.handleRequest(uri, ctx, request);
    if (handled) break;
}
if (!handled) ctx.fireChannelRead(request.retain());
```

#### 5.1.4 静态资源

非 API 请求映射到 classpath 下的 `web/` 目录：

```java
URL url = LlamaServer.class.getResource("/web" + path);
// 检查文件是否存在、是否为目录（禁止目录浏览）
// sendStaticFile(ctx, file, request)  — 支持 ETag + If-None-Match 缓存
```

移动端 UA 检测：自动将根路径 `/` 映射到 `index-mobile.html`。

### 5.2 LlamaRouterHandler

核心 API 路由，处理 OpenAI 和 Anthropic 兼容端点：

```java
// 统一路径归一化：/llama.cpp/v1/... → /v1/...
if (normUri.startsWith("/llama.cpp/v1/")) {
    normUri = "/v1" + normUri.substring("/llama.cpp/v1/".length());
}

// API Key 鉴权
if (normUri.startsWith("/v1") && !validateApiKey(request)) {
    sendErrorResponse(ctx, UNAUTHORIZED, "invalid api key");
    return;
}

// 路由分派
if (uri.startsWith("/llama.cpp/v1/models") ||
    uri.startsWith("/v1/models") || uri.startsWith("/models"))
    → openAIServerHandler.handleOpenAIModelsRequest() 或 anthropicService

if (uri.startsWith("/v1/completions") || uri.startsWith("/completions"))
    → openAIServerHandler.handleOpenAICompletionsRequest()

if (uri.startsWith("/v1/embeddings"))
    → openAIServerHandler.handleOpenAIEmbeddingsRequest()

if (uri.startsWith("/v1/responses"))
    → openAIServerHandler.handleOpenAIResponsesRequest()

if (uri.startsWith("/v1/rerank"))
    → openAIServerHandler.handleOpenAIRerankRequest()

if (uri.startsWith("/v1/audio/transcriptions"))
    → openAIServerHandler.handleOpenAIAudioTranscriptionsRequest()

if (uri.startsWith("/v1/messages/count_tokens"))
    → anthropicService.handleMessagesCountTokensRequest()

if (uri.startsWith("/llama.cpp/slots") || uri.startsWith("/slots"))
    → handleSlotsRequest() — 代理到模型进程或远程节点

if (uri.startsWith("/llama.cpp/v1/chat/completions/control"))
    → handleControlRequest() — 代理到模型进程或远程节点
```

**API Key 验证**支持两种方式：
- `Authorization: Bearer <key>`
- `x-api-key: <key>`

**Anthropic 客户端检测**：检查 `anthropic-version` 或 `x-api-key` 头，决定是否使用 Anthropic 服务处理。

**远程代理**：模型未本地加载时，通过 `NodeManager` 搜索远程节点，使用 `HttpURLConnection` 转发请求到远程节点的对应端点。

### 5.3 异步处理模型

所有 Router Handler 使用 **虚拟线程** 异步处理请求：

```java
private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();

@Override
protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    FullHttpRequest retained = request.retainedDuplicate();
    async.execute(() -> {
        try {
            this.handleRequest(ctx, retained);
        } finally {
            ReferenceCountUtil.release(retained);
        }
    });
}
```

---

## 6. 响应发送

`LlamaServer` 提供了多套响应方法，适用于不同场景：

### 6.1 标准 JSON 响应

```java
public static void sendJsonResponse(ChannelHandlerContext ctx, Object data) {
    String json = GSON.toJson(data);
    byte[] content = json.getBytes(CharsetUtil.UTF_8);

    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    setCorsHeaders(response.headers());
    response.content().writeBytes(content);

    ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) {
            ctx.close();
        }
    });
}
```

### 6.2 Express 风格 JSON 响应

```java
public static void sendExpressJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, Object data, boolean allowAllMethods) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status == null ? HttpResponseStatus.OK : status);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
    if (allowAllMethods) {
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
    }
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
    response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(content));
    response.headers().set("X-Powered-By", "Express");

    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
}
```

### 6.3 流式 JSON 响应（大文件零拷贝）

`sendStreamedJsonResponse()` 将 prefix → 文件内容 → suffix 合并为单一 `ChunkedInput`，避免大文件（如含 base64 图片的会话）OOM：

```java
public static void sendStreamedJsonResponse(ChannelHandlerContext ctx, String prefix, Path file, String suffix) {
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
    setCorsHeaders(response.headers());

    ctx.write(response);
    ctx.write(new DefaultHttpContent(Unpooled.copiedBuffer(prefix, StandardCharsets.UTF_8)));

    FileInputStream fis = new FileInputStream(file.toFile());
    long fileLength = file.toFile().length();
    ByteBuf suffixBuf = Unpooled.copiedBuffer(suffix, StandardCharsets.UTF_8);
    ChunkedInput<ByteBuf> combined = new PrefixFileSuffixInput(fis, fileLength, suffixBuf);
    ctx.write(combined);

    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> ctx.close());
}
```

`PrefixFileSuffixInput` 实现了 `ChunkedInput<ByteBuf>`，按 FILE → SUFFIX → DONE 状态机分块读取，8KB 一块，确保文件完整传输后才发送 suffix 和 `LastHttpContent`。

### 6.4 文件下载

使用 `ChunkedFile` 实现磁盘直读，零堆内存：

```java
RandomAccessFile raf = new RandomAccessFile(file, "r");
long fileLength = raf.length();

HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
response.headers().set(HttpHeaderNames.CONTENT_TYPE, getContentType(file.getName()));
setCorsHeaders(response.headers());

ctx.write(response);
ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());
ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
lastContentFuture.addListener(ChannelFutureListener.CLOSE);
```

### 6.5 静态文件（支持 HTTP 缓存）

`sendStaticFile()` 额外实现了 ETag + `If-None-Match` 缓存：

```java
long lastModified = file.lastModified();
long fileLength = file.length();
String etag = "\"" + Long.toHexString(lastModified) + "-" + Long.toHexString(fileLength) + "\"";

String ifNoneMatch = request.headers().get(HttpHeaderNames.IF_NONE_MATCH);
if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
    response.headers().set(HttpHeaderNames.ETAG, etag);
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    return;
}
// 否则正常发送文件
```

### 6.6 CORS 响应头

所有响应统一设置 CORS 头：

```java
public static void setCorsHeaders(HttpHeaders headers) {
    headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
    headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
    headers.set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "86400");
}
```

---

## 7. 关键设计决策

### 7.1 为什么在 HttpObjectAggregator 之前放置拦截 Handler

Netty 的 `HttpObjectAggregator` 会将所有 `HttpContent` 帧在内存中拼成一个 `FullHttpRequest`，最大聚合体限制在 16MB。如果客户端发送超过此限制的请求体，聚合器会抛出异常。

通过在聚合器之前放置 `OpenAIChatStreamingHandler`、`EasyChatStreamingHandler`、`FileUploadRouterHandler`，可以在聚合发生之前就将超大请求体写入磁盘或直接在流中转发，从而绕过内存限制。

### 7.2 为什么使用虚拟线程

所有 Router Handler 使用 `Executors.newVirtualThreadPerTaskExecutor()` 处理请求：

```java
private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
```

这样 Netty 的 worker 线程（仅 4 个）不会被 I/O 阻塞的操作（如 HTTP 代理转发、文件读写）占用，保持高并发。虚拟线程在需要阻塞时自动让出平台线程，归还后继续执行。

### 7.3 为什么使用 308 重定向

`HttpToHttpsRedirectHandler` 使用 HTTP 308 Permanent Redirect 而不是常见的 301/302：

- **301/302**：浏览器会将 POST 请求自动降级为 GET，丢失请求体
- **308**：保留原始请求方法和请求体，客户端需要以相同方法重新请求 HTTPS URL

这对于需要保持 POST 语义的场景至关重要（如聊天请求、模型加载请求）。

### 7.4 为什么所有响应最后都关闭连接

所有响应方法都遵循 `writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)` 的模式：

```java
ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
    @Override
    public void operationComplete(ChannelFuture future) {
        ctx.close();
    }
});
```

这确保了：
1. 响应发送完成后立即关闭连接，释放 Netty 资源
2. 对于非长连接的服务模式，不需要维护 Keep-Alive 状态机
3. 避免半开连接和资源泄露

---

## 8. 完整请求处理流程

以一次 OpenAI 兼容的聊天请求为例：

```
客户端 POST /v1/chat/completions
    │
    ▼
HttpHttpsUnificationHandler.decode()
    │ 首字节检查 → 非 TLS → enableHttp() → 移除自身
    ▼
HttpServerCodec → 解码为 HttpRequest + HttpContent 帧
    │
    ▼
OpenAIChatStreamingHandler.channelRead()
    │ 匹配 /v1/chat/completions → 创建 ChatStreamSession
    │ 拦截后续 HttpContent 帧，边收边转发到 llama.cpp 进程
    ▼
HttpObjectAggregator → 聚合为 FullHttpRequest
    │
    ▼
ChunkedWriteHandler → 注册（为流式输出做准备）
    │
    ▼
WebSocketServerProtocolHandler → 非 WebSocket 握手，透传
    │
    ▼
WebSocketServerHandler → 非 WebSocket 帧，透传
    │
    ▼
BasicRouterHandler.channelRead0()
    │ isApiRequest() → true → 进入 API 分支
    │ 不是 /llama.cpp/* 专用端点
    │ Controller Pipeline 按序尝试 → 无匹配的
    │ ctx.fireChannelRead() → 传递给下游
    ▼
CompletionRouterHandler → 不是 /api/chat/completion/* → fireChannelRead
    ▼
FileDownloadRouterHandler → 不是 /api/downloads/* → fireChannelRead
    ▼
LlamaRouterHandler.channelRead0()
    │ 归一化 URI /llama.cpp/v1/... → /v1/...
    │ API Key 验证 → 通过
    │ 匹配 /v1/chat/completions → openAIServerHandler.handleChatCompletionsRequest()
    │ 建立到本地 llama.cpp 进程的连接
    │ 将请求转发到 llama.cpp，流式返回 SSE 响应
    ▼
ChunkedWriteHandler → 处理 ChunkedFile/ServerSentEvent 输出
    │
    ▼
CloseOnExceptionHandler → 无异常，透传
```

---

## 9. 文件路径

| 文件 | 作用 |
|------|------|
| `LlamaServer.java` | 入口、Bootstrap 配置、响应工具方法、配置管理 |
| `channel/HttpHttpsUnificationHandler.java` | HTTP/HTTPS 协议探测与动态 Pipeline 切换 |
| `channel/HttpToHttpsRedirectHandler.java` | HTTP → HTTPS 的 308 重定向 |
| `channel/OpenAIChatStreamingHandler.java` | 聊天端点超大请求体拦截，ChatStreamSession 流式转发 |
| `channel/FileUploadRouterHandler.java` | `/api/uploads` 分块上传拦截，直接写入磁盘 |
| `channel/EasyChatStreamingHandler.java` | `/api/chat/stream-chat` 流式写入临时文件 |
| `channel/BasicRouterHandler.java` | 系统 API 路由 + 静态资源 + Controller Pipeline |
| `channel/CompletionRouterHandler.java` | 创作服务 API（character CRUD、文件管理） |
| `channel/FileDownloadRouterHandler.java` | 下载 API（列表、创建、暂停、恢复、删除、统计、流式下载） |
| `channel/LlamaRouterHandler.java` | 核心 API 路由（OpenAI/Anthropic 兼容 + 远程代理） |
| `controller/BaseController.java` | Controller 接口定义 |
| `websocket/WebSocketManager.java` | WebSocket 连接管理、心跳、事件广播 |
| `websocket/WebSocketServerHandler.java` | WebSocket 帧处理 |
| `io/ConsoleBroadcastOutputStream.java` | stdout/stderr 重定向到 Log4j2 和 WebSocket |
| `io/ConsoleBufferLogAppender.java` | Log4j2 Appender，将日志行广播到 WebSocket |
