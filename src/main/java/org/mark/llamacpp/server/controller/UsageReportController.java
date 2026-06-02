package org.mark.llamacpp.server.controller;

import com.google.gson.JsonObject;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.LlamaRecordService;
import org.mark.llamacpp.server.service.UsageReportService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

public class UsageReportController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(UsageReportController.class);

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (uri.startsWith("/api/report/token-summary")) {
			this.handleTokenSummary(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/report/daily-tokens")) {
			this.handleDailyTokens(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/report/available-years")) {
			this.handleAvailableYears(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/report/request-logs")) {
			this.handleRequestLogs(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/report/records")) {
			this.handleDeleteRecords(ctx, request);
			return true;
		}
		return false;
	}

	private void handleTokenSummary(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Object data = UsageReportService.getInstance().getTokenSummary();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取Token用量概览时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取Token用量概览失败: " + e.getMessage()));
		}
	}

	private void handleDailyTokens(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			int year = Integer.parseInt(params.getOrDefault("year", String.valueOf(LocalDate.now().getYear())));
			int month = Integer.parseInt(params.getOrDefault("month", String.valueOf(LocalDate.now().getMonthValue())));
			String modelId = params.get("modelId");
			Object data;
			if (modelId != null && !modelId.isEmpty()) {
				data = UsageReportService.getInstance().getDailyTokenUsage(year, month, modelId);
			} else {
				data = UsageReportService.getInstance().getDailyTokenUsage(year, month);
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取每日Token用量时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取每日Token用量失败: " + e.getMessage()));
		}
	}

		private void handleRequestLogs(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			int page = parseInt(params.get("page"), 1);
			int pageSize = parseInt(params.get("pageSize"), 30);
			String modelId = params.get("modelId");
			Object data = UsageReportService.getInstance().getRequestLogs(modelId, page, pageSize);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取请求记录时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取请求记录失败: " + e.getMessage()));
		}
	}

	private int parseInt(String value, int defaultValue) {
		if (value == null || value.trim().isEmpty()) return defaultValue;
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private void handleAvailableYears(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Object data = UsageReportService.getInstance().getAvailableYears();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取可用年份时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取可用年份失败: " + e.getMessage()));
		}
	}

	private void handleDeleteRecords(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
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
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			long deletedCount = LlamaRecordService.getInstance().deleteModelRecords(modelId);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("deletedCount", deletedCount);
			data.put("message", "已删除 " + deletedCount + " 条记录");
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("删除模型记录时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("删除模型记录失败: " + e.getMessage()));
		}
	}
}
