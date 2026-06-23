package org.mark.llamacpp.server.service;

import org.mark.llamacpp.gguf.GGUFModel;
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

	private final LlamaServerManager manager;
	private long autoLoadTimeoutMs = 120_000;

	private AutoLoadPolicyManager() {
		this.manager = LlamaServerManager.getInstance();
	}

	public static AutoLoadPolicyManager getInstance() {
		return INSTANCE;
	}

	/**
	 * 启动时加载配置
	 */
	public void loadConfig() {
		com.google.gson.JsonObject appConfig = LlamaServer.readApplicationConfig();
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
	 * 检查模型是否允许自动加载（委托给 LlamaServerManager）
	 */
	public boolean canAutoLoad(String modelId) {
		return manager.canAutoLoad(modelId);
	}

	/**
	 * 设置模型的自动加载策略（委托给 LlamaServerManager）
	 */
	public String setModelPolicy(String modelId, String mode) {
		return manager.setAutoLoadPolicy(modelId, mode);
	}

	/**
	 * 重置模型的自动加载策略（委托给 LlamaServerManager）
	 */
	public String resetModelPolicy(String modelId) {
		return manager.resetAutoLoadPolicy(modelId);
	}

	/**
	 * 检查模型是否允许自动卸载（委托给 LlamaServerManager）
	 */
	public boolean canAutoUnload(String modelId) {
		return manager.canAutoUnload(modelId);
	}

	/**
	 * 设置模型的自动卸载策略（委托给 LlamaServerManager）
	 */
	public String setAutoUnloadPolicy(String modelId, String mode) {
		return manager.setAutoUnloadPolicy(modelId, mode);
	}

	/**
	 * 重置模型的自动卸载策略（委托给 LlamaServerManager）
	 */
	public String resetAutoUnloadPolicy(String modelId) {
		return manager.resetAutoUnloadPolicy(modelId);
	}

	/**
	 * 获取模型的自动卸载超时时间（毫秒）
	 */
	public Long getAutoUnloadTimeoutMs(String modelId) {
		return manager.getAutoUnloadTimeoutMs(modelId);
	}

	/**
	 * 设置模型的自动卸载超时时间（毫秒）
	 */
	public String setAutoUnloadTimeoutMs(String modelId, long timeoutMs) {
		return manager.setAutoUnloadTimeoutMs(modelId, timeoutMs);
	}

	/**
	 * 重置模型的自动卸载超时时间
	 */
	public String resetAutoUnloadTimeoutMs(String modelId) {
		return manager.resetAutoUnloadTimeoutMs(modelId);
	}

	/**
	 * 获取单个模型的自动加载策略
	 */
	public Map<String, Object> getPolicyForModel(String modelId) {
		Map<String, Object> result = new HashMap<>();
		String policy = manager.getAutoLoadPolicy(modelId);
		Map<String, Object> policiesMap = new HashMap<>();
		policiesMap.put(modelId, policy != null ? policy : "deny");
		result.put("policies", policiesMap);

		String unloadPolicy = manager.getAutoUnloadPolicy(modelId);
		Map<String, Object> unloadPoliciesMap = new HashMap<>();
		unloadPoliciesMap.put(modelId, unloadPolicy != null ? unloadPolicy : "deny");
		result.put("autoUnload", unloadPoliciesMap);

		Map<String, Object> modelsMap = new HashMap<>();
		Map<String, Object> modelInfo = new HashMap<>();
		modelInfo.put("modelId", modelId);
		Long unloadTimeout = manager.getAutoUnloadTimeoutMs(modelId);
		if (unloadTimeout != null) {
			modelInfo.put("autoUnloadTimeoutMs", unloadTimeout);
		}
		modelsMap.put(modelId, modelInfo);
		result.put("models", modelsMap);

		return result;
	}

	/**
	 * 获取所有模型的自动加载策略（批量读取，只读一次配置文件）
	 */
	public Map<String, Object> getAllPolicies() {
		Map<String, Object> result = new HashMap<>();

		List<GGUFModel> models = manager.listModel();
		Map<String, String> allPolicies = manager.getAllAutoLoadPolicies();
		Map<String, String> allUnloadPolicies = manager.getAllAutoUnloadPolicies();
		Map<String, Long> allUnloadTimeouts = manager.getAllAutoUnloadTimeouts();

		Map<String, Object> modelsMap = new HashMap<>();
		for (GGUFModel model : models) {
			String modelId = model.getModelId();
			String policy = allPolicies.get(modelId);
			String unloadPolicy = allUnloadPolicies.get(modelId);
			Long unloadTimeout = allUnloadTimeouts.get(modelId);
			Map<String, Object> modelInfo = new HashMap<>();
			modelInfo.put("modelId", modelId);
			modelInfo.put("modelName", model.getName());
			modelInfo.put("policy", policy != null ? policy : "deny");
			modelInfo.put("autoUnload", unloadPolicy != null ? unloadPolicy : "deny");
			if (unloadTimeout != null) {
				modelInfo.put("autoUnloadTimeoutMs", unloadTimeout);
			}
			modelsMap.put(modelId, modelInfo);
		}

		Map<String, Object> policiesMap = new HashMap<>();
		for (Map.Entry<String, String> entry : allPolicies.entrySet()) {
			policiesMap.put(entry.getKey(), entry.getValue());
		}

		Map<String, Object> unloadPoliciesMap = new HashMap<>();
		for (Map.Entry<String, String> entry : allUnloadPolicies.entrySet()) {
			unloadPoliciesMap.put(entry.getKey(), entry.getValue());
		}

		result.put("policies", policiesMap);
		result.put("autoUnload", unloadPoliciesMap);
		result.put("models", modelsMap);

		return result;
	}
}
