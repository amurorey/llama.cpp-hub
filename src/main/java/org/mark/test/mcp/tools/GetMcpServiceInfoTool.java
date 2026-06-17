package org.mark.test.mcp.tools;

import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.service.ComputerService;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonObject;


/**
 * 	获取当前服务器的信息。
 */
public class GetMcpServiceInfoTool implements IMCPTool {

	public GetMcpServiceInfoTool() {
	}

	@Override
	public String getMcpName() {
		return "get_mcp_service_info";
	}

	@Override
	public String getMcpTitle() {
		return "获取mcp服务信息";
	}

	@Override
	public String getMcpDescription() {
		StringBuilder sb = new StringBuilder();
		sb.append("获取指定服务器的 CPU、内存和 JVM 运行信息。可用服务器: local");
		try {
			List<LlamaHubNode> nodes = NodeManager.getInstance().listEnabledNodes();
			if (nodes != null) {
				for (LlamaHubNode node : nodes) {
					sb.append(", ").append(node.getNodeId());
				}
			}
		} catch (Exception e) {
			// ignore
		}
		return sb.toString();
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema()
				.addProperty("nodeId", "string", "服务器节点名称，为空时默认为 local", false);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments, Map<String, String> headers) {
		String nodeId = JsonUtil.getJsonString(arguments, "nodeId", null);

		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			JsonObject info = ComputerService.getFullInfo();
			return new McpMessage().addText(JsonUtil.toJson(info));
		}

		try {
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
					nodeId, "GET", "api/sys/sysinfo", null);
			if (result.isSuccess()) {
				JsonObject remoteResp = JsonUtil.fromJson(result.getBody(), JsonObject.class);
				if (remoteResp != null && remoteResp.has("data") && !remoteResp.get("data").isJsonNull()) {
					return new McpMessage().addText(JsonUtil.toJson(remoteResp.get("data")));
				}
				return new McpMessage().addText("远程节点响应格式错误");
			}
			return new McpMessage().addText("远程节点调用失败: code=" + result.getStatusCode());
		} catch (Exception e) {
			return new McpMessage().addText("远程节点调用异常: " + e.getMessage());
		}
	}
}
