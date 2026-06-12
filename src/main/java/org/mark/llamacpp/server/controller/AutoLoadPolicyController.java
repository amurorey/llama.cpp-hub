package org.mark.llamacpp.server.controller;

import com.google.gson.JsonObject;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.AutoLoadPolicyManager;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

public class AutoLoadPolicyController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(AutoLoadPolicyController.class);

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (uri.startsWith("/api/auto-load/policy")) {
			handlePolicy(ctx, request);
			return true;
		}
		return false;
	}

	private void handlePolicy(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}

		if (request.method() == HttpMethod.GET) {
			handleGetPolicies(ctx);
		} else if (request.method() == HttpMethod.PUT) {
			handleSetPolicy(ctx, request);
		} else if (request.method() == HttpMethod.DELETE) {
			handleResetPolicy(ctx, request);
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("不支持的请求方法"));
		}
	}

	private void handleGetPolicies(ChannelHandlerContext ctx) {
		try {
			AutoLoadPolicyManager manager = AutoLoadPolicyManager.getInstance();
			Map<String, Object> data = manager.getAllPolicies();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取自动加载策略失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取自动加载策略失败: " + e.getMessage()));
		}
	}

	private void handleSetPolicy(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "modelId");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的 modelId 参数"));
				return;
			}

			String mode = JsonUtil.getJsonString(obj, "mode");
			if (mode == null || !("allow".equalsIgnoreCase(mode) || "deny".equalsIgnoreCase(mode))) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("mode 参数无效，必须为 allow 或 deny"));
				return;
			}

			String error = AutoLoadPolicyManager.getInstance().setModelPolicy(modelId, mode);
			if (error != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
				return;
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(null));
		} catch (Exception e) {
			logger.info("设置自动加载策略失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置自动加载策略失败: " + e.getMessage()));
		}
	}

	private void handleResetPolicy(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String uri = request.uri();
			String modelId = null;

			// 尝试从 URL 路径获取 modelId
			if (uri != null && uri.length() > "/api/auto-load/policy/".length()) {
				modelId = uri.substring("/api/auto-load/policy/".length());
			}

			// 如果 URL 中没有，尝试从请求体获取
			if (modelId == null || modelId.trim().isEmpty()) {
				String content = request.content().toString(CharsetUtil.UTF_8);
				if (content != null && !content.trim().isEmpty()) {
					JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
					if (obj != null) {
						modelId = JsonUtil.getJsonString(obj, "modelId");
					}
				}
			}

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的 modelId 参数"));
				return;
			}

			String error = AutoLoadPolicyManager.getInstance().resetModelPolicy(modelId);
			if (error != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
				return;
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(null));
		} catch (Exception e) {
			logger.info("重置自动加载策略失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("重置自动加载策略失败: " + e.getMessage()));
		}
	}
}
