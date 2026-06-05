package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamingForwarder {

    private static final Logger logger = LoggerFactory.getLogger(StreamingForwarder.class);

    private static final int QUEUE_CAPACITY = 16;
    private static final int CHUNK_PREVIEW_MAX = 120;

    private static final byte[] EOF_MARKER = new byte[0];

    /* ---------- 状态机常量 ---------- */
    private static final int STATE_NORMAL      = 0;
    private static final int STATE_KEY_MODEL   = 1;
    private static final int STATE_MODEL_VALUE = 2;
    private static final int STATE_DONE        = 3;

    private static final byte[] MODEL_KEY = "model".getBytes(StandardCharsets.US_ASCII);

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private volatile IOException failure;

    private final UnifiedBodyBuffer bodyBuffer = new UnifiedBodyBuffer();

    private String modelName;

    /* 状态机字段（跨 chunk 持久化） */
    private int state = STATE_NORMAL;
    private int depth;
    private boolean inString;
    private int keyMatchLen;
    private StringBuilder modelValueBuf;
    private boolean escapePending;
    private boolean afterColon;
    private boolean inValueString;

    /* nodeId 由外部从请求头设置，不从 body 提取 */
    private volatile String nodeId;

    private volatile byte[] lastChunk;
    private final AtomicLong chunkSeq = new AtomicLong(0);

    public StreamingForwarder() {
    }

    /**
     * 从请求头设置 nodeId。
     */
    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void offer(byte[] chunk) throws IOException {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        if (closed.get()) {
            throw new IOException("stream closed");
        }
        long seq = chunkSeq.incrementAndGet();
        try {
            bodyBuffer.write(chunk);
            extractFields(chunk);
        } catch (IOException e) {
            fail(e);
            throw e;
        }
        Object marker = seq;
        try {
            while (!closed.get() && !failed.get()) {
                if (queue.offer(marker, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while enqueuing", e);
        }
        if (failed.get() && failure != null) {
            throw failure;
        }
        throw new IOException("stream closed");
    }

    public void offerLast(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        this.lastChunk = chunk;
    }

    public void complete() {
        closed.compareAndSet(false, true);
        queue.offer(EOF_MARKER);
    }

    public void fail(IOException e) {
        failed.compareAndSet(false, true);
        this.failure = e;
        closed.set(true);
        queue.offer(EOF_MARKER);
    }

    /**
     * 等待所有 chunk 到达（已在 offer() 中写入 bodyBuffer），返回路由信息。
     */
    public TransformResult extract() throws IOException {
        while (true) {
            try {
                Object marker = queue.poll(1, TimeUnit.SECONDS);
                if (marker == EOF_MARKER) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for chunk", e);
            }
        }
        if (failed.get() && failure != null) {
            throw failure;
        }

        /* 处理最后一个 chunk（必须在检查 modelName 之前） */
        byte[] storedLast = this.lastChunk;
        if (storedLast != null && storedLast.length > 0) {
            try {
                bodyBuffer.write(storedLast);
            } catch (IOException e) {
                fail(e);
                throw e;
            }
            extractFields(storedLast);
        }

        if (modelName == null || modelName.isBlank()) {
            throw new ForwarderException(400, "Missing required parameter: model", "model");
        }

        return new TransformResult(modelName, nodeId);
    }

    /**
     * 将 bodyBuffer 中的数据流式转发到目标输出流。
     * 有 nodeId → 纯转发；无 nodeId → 注入采样参数后转发。
     */
    public void streamBody(OutputStream output) throws IOException {
        if (nodeId != null && !nodeId.isBlank()) {
            long written = bodyBuffer.streamTo(output);
            logger.info("[远程代理] nodeId={}, streamed={} bytes", nodeId, written);
        } else {
            String injection = SamplingInjectionBuilder.buildInjectionString(modelName);
            if (!injection.isEmpty()) {
                long injected = bodyBuffer.streamInjected(output, injection);
                logger.info("[注入] model={}, injected={} bytes: ,{}", modelName, injected, injection);
            } else {
                bodyBuffer.streamTo(output);
            }
        }
    }

    /**
     * 基于状态机的 JSON 字段提取。
     * 逐字节扫描，追踪嵌套深度和字符串状态，仅提取顶层 "model" 字段。
     * 内存 O(1)，不依赖 JSON 总大小。
     */
    void extractFields(byte[] chunk) {
        if (state == STATE_DONE) {
            return;
        }

        logger.debug("[状态机] === chunk: {} 字节, preview={}", chunk.length, previewChunk(chunk));

        for (int i = 0; i < chunk.length; i++) {
            byte b = chunk[i];
            int prevState = state;

            switch (state) {

                /* ===== 主状态：逐字节扫描 JSON 结构 ===== */
                default:
                case STATE_NORMAL: {
                    if (escapePending) {
                        escapePending = false;
                        break;
                    }
                    if (b == '\\') {
                        escapePending = true;
                        break;
                    }
                    if (b == '"') {
                        if (!inString) {
                            inString = true;
                            /* 前瞻检查：是否为顶层 "model" key */
                            if (depth == 1 && modelName == null && matchesKey(chunk, i + 1, MODEL_KEY)) {
                                keyMatchLen = 0;
                                state = STATE_KEY_MODEL;
                                logger.debug("[状态机] pos={} 匹配到 model key 开头", i);
                                break;
                            }
                        } else {
                            inString = false;
                        }
                        break;
                    }
                    if (inString) {
                        break;
                    }
                    if (b == '{') {
                        depth++;
                        break;
                    }
                    if (b == '}') {
                        depth--;
                        if (depth < 0) depth = 0;
                        break;
                    }
                    break;
                }

                /* ===== 消耗 "model" key 剩余字符（前瞻已确认匹配） ===== */
                case STATE_KEY_MODEL: {
                    keyMatchLen++;
                    if (keyMatchLen == MODEL_KEY.length) {
                        afterColon = false;
                        inValueString = false;
                        modelValueBuf = null;
                        state = STATE_MODEL_VALUE;
                        logger.debug("[状态机] pos={} model key 匹配完成，解析 value", i);
                    }
                    break;
                }

                /* ===== 解析 model 的字符串 value ===== */
                case STATE_MODEL_VALUE: {
                    if (escapePending) {
                        escapePending = false;
                        if (modelValueBuf != null) {
                            modelValueBuf.append((char) b);
                        }
                        break;
                    }
                    if (b == '\\') {
                        escapePending = true;
                        break;
                    }
                    if (b == '"') {
                        if (!afterColon) {
                            /* key 的关闭引号 */
                            break;
                        }
                        if (!inValueString) {
                            /* value 的打开引号 */
                            inValueString = true;
                            break;
                        }
                        /* value 的关闭引号 —— 提取完成 */
                        if (modelValueBuf == null) {
                            modelName = "";
                        } else {
                            modelName = modelValueBuf.toString();
                            modelValueBuf = null;
                        }
                        bodyBuffer.setModelFound();
                        logger.info("[状态机] *** 提取到 model={}", modelName);
                        state = STATE_DONE;
                        break;
                    }
                    if (b == ':') {
                        afterColon = true;
                        break;
                    }
                    if (isWhitespace(b)) {
                        break;
                    }
                    /* value 字符 */
                    if (modelValueBuf == null) {
                        modelValueBuf = new StringBuilder(32);
                    }
                    modelValueBuf.append((char) b);
                    break;
                }
            }

            if (prevState != state) {
                logger.debug("[状态机] {} -> {}", stateName(prevState), stateName(state));
            }
        }
    }

    static String stateName(int s) {
        return switch (s) {
            case STATE_NORMAL -> "NORMAL";
            case STATE_KEY_MODEL -> "KEY_MODEL";
            case STATE_MODEL_VALUE -> "MODEL_VALUE";
            case STATE_DONE -> "DONE";
            default -> "UNKNOWN(" + s + ")";
        };
    }

    static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    /**
     * 检查 chunk 中从 offset 开始是否完整匹配 key。
     * chunk 数据不足时返回 false（等下个 chunk 再试）。
     */
    static boolean matchesKey(byte[] chunk, int offset, byte[] key) {
        if (offset + key.length > chunk.length) {
            return false;
        }
        for (int k = 0; k < key.length; k++) {
            if (chunk[offset + k] != key[k]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 关闭并清理 bodyBuffer 资源。
     */
    public void close() throws IOException {
        bodyBuffer.close();
    }

    String getModelName() {
        return modelName;
    }

    static String previewChunk(byte[] chunk) {
        int len = Math.min(chunk.length, CHUNK_PREVIEW_MAX);
        String preview = new String(chunk, 0, len, StandardCharsets.UTF_8);
        if (chunk.length > CHUNK_PREVIEW_MAX) {
            preview += "...(+" + (chunk.length - CHUNK_PREVIEW_MAX) + "bytes)";
        }
        return preview;
    }

    public static class TransformResult {
        private final String modelName;
        private final String nodeId;

        public TransformResult(String modelName, String nodeId) {
            this.modelName = modelName;
            this.nodeId = nodeId;
        }

        public String getModelName() {
            return modelName;
        }

        public String getNodeId() {
            return nodeId;
        }
    }

    public static class ForwarderException extends IOException {
        private static final long serialVersionUID = 1L;
		private final int httpStatus;
        private final String param;

        public ForwarderException(int httpStatus, String message, String param) {
            super(message);
            this.httpStatus = httpStatus;
            this.param = param;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public String getParam() {
            return param;
        }
    }
}
