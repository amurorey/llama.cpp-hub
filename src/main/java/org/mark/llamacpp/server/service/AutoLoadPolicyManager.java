package org.mark.llamacpp.server.service;

import com.google.gson.JsonObject;

import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.ConfigManager;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoLoadPolicyManager {

	private static final Logger logger = LoggerFactory.getLogger(AutoLoadPolicyManager.class);

	private static final AutoLoadPolicyManager INSTANCE = new AutoLoadPolicyManager();

	private final ConfigManager configManager;
	private long autoLoadTimeoutMs = 120_000;

	private AutoLoadPolicyManager() {
		this.configManager = ConfigManager.getInstance();
	}

	public static AutoLoadPolicyManager getInstance() {
		return INSTANCE;
	}

	/**
	 * 启动时加载配置
	 */
	public void loadConfig() {
		JsonObject appConfig = LlamaServer.readApplicationConfig();
		if (appConfig != null && appConfig.has("autoLoadTimeoutMs")) {
			try {
				autoLoadTimeoutMs = appConfig.get("autoLoadTimeoutMs").getAsLong();
			} catch (Exception e) {
				logger.info("读取 autoLoadTimeoutMs 失败，使用默认值: {}", e.getMessage());
			}
		}
		logger.info("AutoLoadPolicyManager 已初始化, autoLoadTimeoutMs={}", autoLoadTimeoutMs);
	}

	/**
	 * 获取自动加载超时时间（毫秒）
	 */
	public long getAutoLoadTimeoutMs() {
		return autoLoadTimeoutMs;
	}

	/**
	 * 解析模型名称，支持别名
	 * @param name 模型 ID 或别名
	 * @return 真实 modelId，不存在返回 null
	 */
	private String resolveModelId(String name) {
		if (name == null || name.trim().isEmpty()) {
			return null;
		}
		LlamaServerManager manager = LlamaServerManager.getInstance();

		// 1. 直接查找 modelId
		if (manager.findModelById(name) != null) {
			return name;
		}

		// 2. 通过别名查找
		String resolved = manager.findModelIdByAlias(name);
		if (resolved != null) {
			return resolved;
		}

		// 3. 都不存在
		return null;
	}

	/**
	 * 检查模型是否允许自动加载
	 * @param modelId 模型 ID 或别名
	 * @return true 如果允许自动加载
	 */
	public boolean canAutoLoad(String modelId) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) {
			return false;
		}
		String mode = configManager.getAutoLoadPolicy(resolved);
		if (mode == null) {
			return false;
		}
		return "allow".equalsIgnoreCase(mode);
	}

	/**
	 * 设置模型的自动加载策略
	 * @param modelId 模型 ID 或别名
	 * @param mode "allow" 或 "deny"
	 * @return null 表示成功，非 null 为错误信息
	 */
	public String setModelPolicy(String modelId, String mode) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) {
			return "Model not found: " + modelId;
		}
		configManager.setAutoLoadPolicy(resolved, mode);
		return null;
	}

	/**
	 * 重置模型的自动加载策略（删除 autoLoad 字段）
	 * @param modelId 模型 ID 或别名
	 * @return null 表示成功，非 null 为错误信息
	 */
	public String resetModelPolicy(String modelId) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) {
			return "Model not found: " + modelId;
		}
		configManager.resetAutoLoadPolicy(resolved);
		return null;
	}

	/**
	 * 获取所有模型的自动加载策略
	 * @return 包含 policies 和 models 的 Map
	 */
	public Map<String, Object> getAllPolicies() {
		Map<String, Object> result = new HashMap<>();

		// 获取所有已知模型
		List<GGUFModel> models = LlamaServerManager.getInstance().listModel();
		Map<String, Object> modelsMap = new HashMap<>();

		for (GGUFModel model : models) {
			String modelId = model.getModelId();
			String policy = configManager.getAutoLoadPolicy(modelId);
			Map<String, Object> modelInfo = new HashMap<>();
			modelInfo.put("modelId", modelId);
			modelInfo.put("modelName", model.getName());
			modelInfo.put("policy", policy != null ? policy : "deny");
			modelsMap.put(modelId, modelInfo);
		}

		// 获取所有显式设置的策略
		Map<String, Object> policiesMap = new HashMap<>();
		for (String modelId : modelsMap.keySet()) {
			String policy = configManager.getAutoLoadPolicy(modelId);
			if (policy != null) {
				policiesMap.put(modelId, policy);
			}
		}

		result.put("policies", policiesMap);
		result.put("models", modelsMap);

		return result;
	}
}
