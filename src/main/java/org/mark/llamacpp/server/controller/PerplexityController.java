package org.mark.llamacpp.server.controller;

import java.util.concurrent.ConcurrentHashMap;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.PerplexityService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * 困惑度（Perplexity）测试控制器。
 */
public class PerplexityController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(PerplexityController.class);

	private final PerplexityService perplexityService = new PerplexityService();
	private final ConcurrentHashMap<ChannelHandlerContext, Process> activeProcesses = new ConcurrentHashMap<>();

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if ("/api/perplexity/run".equals(uri)) {
			handleRun(ctx, request);
			return true;
		}
		return false;
	}

	private void handleRun(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		String content = request.content().toString(CharsetUtil.UTF_8);
		if (content == null || content.trim().isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
			return;
		}

		JsonObject json;
		try {
			json = JsonUtil.fromJson(content, JsonObject.class);
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败: " + e.getMessage()));
			return;
		}
		if (json == null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
			return;
		}

		String modelId = JsonUtil.getJsonString(json, "modelId", "").trim();
		String llamaBinPath = JsonUtil.getJsonString(json, "llamaBinPath", "").trim();

		if (modelId.isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的 modelId 参数"));
			return;
		}
		if (llamaBinPath.isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的 llamaBinPath 参数"));
			return;
		}

		// 发送流式响应头
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		LlamaServer.setCorsHeaders(response.headers());
		ctx.writeAndFlush(response);

		try {
			perplexityService.run(ctx, json, activeProcesses);
		} catch (Exception e) {
			logger.info("启动困惑度测试失败", e);
			if (ctx.channel().isActive()) {
				String errorLine = org.mark.llamacpp.server.tools.JsonUtil.toJson(
						java.util.Map.of("type", "error", "text", e.getMessage())) + System.lineSeparator();
				byte[] errorBytes = errorLine.getBytes(io.netty.util.CharsetUtil.UTF_8);
				ctx.writeAndFlush(new io.netty.handler.codec.http.DefaultHttpContent(
						io.netty.buffer.Unpooled.copiedBuffer(errorBytes)))
						.addListener(f -> ctx.writeAndFlush(
								io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT)
									.addListener(ChannelFutureListener.CLOSE));
			}
		}
	}

	@Override
	public void inactive(ChannelHandlerContext ctx) {
		Process process = activeProcesses.remove(ctx);
		if (process != null && process.isAlive()) {
			logger.info("客户端断开连接，终止困惑度测试进程");
			org.mark.llamacpp.server.tools.CommandLineRunner.destroyProcessTree(process);
		}
	}
}
