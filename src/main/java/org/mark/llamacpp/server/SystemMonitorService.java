package org.mark.llamacpp.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.websocket.WebSocketManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 系统监控服务类
 * 负责定时执行系统监控脚本，获取系统性能数据，并通过WebSocket推送给前端
 */
public class SystemMonitorService {
    
    private static final Logger logger = LoggerFactory.getLogger(SystemMonitorService.class);
    
    // 单例实例
    private static volatile SystemMonitorService instance;
    
    // 脚本路径
    private final String scriptPath;
    
    // WebSocket管理器
    private final WebSocketManager webSocketManager;
    
    // 定时任务执行器
    private final ScheduledExecutorService scheduler;
    
    // JSON解析器
    private final Gson gson;
    
    // 是否已启动
    private volatile boolean started = false;
    
    /**
     * 私有构造函数
     */
    private SystemMonitorService() {
        this.scriptPath = System.getProperty("user.dir") + File.separator + "system_monitor_json.sh";
        this.webSocketManager = WebSocketManager.getInstance();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.gson = JsonUtil.gson();
        
        // 检查脚本文件是否存在
        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists() || !scriptFile.canExecute()) {
            logger.info("系统监控脚本不存在或不可执行: {}", scriptPath);
            // 尝试设置执行权限
            if (scriptFile.exists()) {
                boolean success = scriptFile.setExecutable(true);
                if (success) {
                    logger.info("成功设置脚本执行权限: {}", scriptPath);
                } else {
                    logger.info("无法设置脚本执行权限: {}", scriptPath);
                }
            }
        }
    }
    
    /**
     * 获取单例实例
     */
    public static SystemMonitorService getInstance() {
        if (instance == null) {
            synchronized (SystemMonitorService.class) {
                if (instance == null) {
                    instance = new SystemMonitorService();
                }
            }
        }
        return instance;
    }
    
    /**
     * 启动系统监控服务
     * @param intervalSeconds 监控间隔（秒）
     */
    public void start(int intervalSeconds) {
        if (started) {
            logger.info("系统监控服务已经启动");
            return;
        }
        
        if (intervalSeconds <= 0) {
            intervalSeconds = 30; // 默认30秒
        }
        
        logger.info("启动系统监控服务，监控间隔: {} 秒", intervalSeconds);
        
        // 立即执行一次
        scheduler.submit(this::executeMonitorScript);
        
        // 启动定时任务
        scheduler.scheduleAtFixedRate(
            this::executeMonitorScript, 
            intervalSeconds, 
            intervalSeconds, 
            TimeUnit.SECONDS
        );
        
        started = true;
    }
    
    /**
     * 停止系统监控服务
     */
    public void stop() {
        if (!started) {
            logger.info("系统监控服务未启动");
            return;
        }
        
        logger.info("停止系统监控服务");
        
        started = false;
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 执行监控脚本并发送数据
     */
    private void executeMonitorScript() {
        try {
            logger.info("执行系统监控脚本: {}", scriptPath);
            
            // 执行脚本
            ProcessBuilder processBuilder = new ProcessBuilder("bash", scriptPath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            // 读取脚本输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            
            // 等待进程完成
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.info("监控脚本执行失败，退出码: {}", exitCode);
                return;
            }
            
            // 解析JSON数据
            String jsonOutput = output.toString().trim();
            if (jsonOutput.isEmpty()) {
                logger.info("监控脚本输出为空");
                return;
            }
            
            JsonObject systemData = gson.fromJson(jsonOutput, JsonObject.class);
            
            // 构建WebSocket消息
            String message = buildWebSocketMessage(systemData);
            
            // 通过WebSocket广播数据
            webSocketManager.broadcast(message);
            
            logger.info("系统监控数据已通过WebSocket推送");
            
        } catch (IOException e) {
            logger.info("执行监控脚本时发生IO异常", e);
        } catch (InterruptedException e) {
            logger.info("执行监控脚本时被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.info("执行监控脚本时发生未知错误", e);
        }
    }
    
    /**
     * 构建WebSocket消息
     */
    private String buildWebSocketMessage(JsonObject systemData) {
        try {
            // 构建包含系统监控数据的JSON消息
            StringBuilder messageBuilder = new StringBuilder();
            messageBuilder.append("{");
            messageBuilder.append("\"type\":\"systemMonitor\",");
            messageBuilder.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");
            
            // 添加系统数据
            if (systemData.has("system")) {
                JsonObject system = systemData.getAsJsonObject("system");
                
                // CPU信息
                if (system.has("cpu")) {
                    JsonObject cpu = system.getAsJsonObject("cpu");
                    messageBuilder.append("\"cpu\":{");
                    messageBuilder.append("\"usage_percent\":").append(cpu.get("usage_percent").getAsDouble());
                    messageBuilder.append("},");
                }
                
                // 内存信息
                if (system.has("memory")) {
                    JsonObject memory = system.getAsJsonObject("memory");
                    messageBuilder.append("\"memory\":{");
                    messageBuilder.append("\"used\":\"").append(memory.get("used").getAsString()).append("\",");
                    messageBuilder.append("\"total\":\"").append(memory.get("total").getAsString()).append("\",");
                    messageBuilder.append("\"usage_percent\":").append(memory.get("usage_percent").getAsDouble());
                    messageBuilder.append("},");
                }
                
                // GPU信息
                if (system.has("gpu")) {
                    JsonObject gpu = system.getAsJsonObject("gpu");
                    messageBuilder.append("\"gpu\":{");
                    messageBuilder.append("\"usage_percent\":");
                    if (gpu.get("usage_percent").isJsonNull()) {
                        messageBuilder.append("null");
                    } else {
                        messageBuilder.append(gpu.get("usage_percent").getAsDouble());
                    }
                    messageBuilder.append(",\"memory_used\":\"").append(gpu.get("memory_used").getAsString()).append("\",");
                    messageBuilder.append("\"memory_total\":\"").append(gpu.get("memory_total").getAsString()).append("\",");
                    messageBuilder.append("\"memory_usage_percent\":");
                    if (gpu.get("memory_usage_percent").isJsonNull()) {
                        messageBuilder.append("null");
                    } else {
                        messageBuilder.append(gpu.get("memory_usage_percent").getAsDouble());
                    }
                    messageBuilder.append("},");
                }
                
                
                // 系统负载
                if (system.has("load")) {
                    JsonObject load = system.getAsJsonObject("load");
                    messageBuilder.append("\"load\":{");
                    messageBuilder.append("\"1min\":").append(load.get("1min").getAsDouble()).append(",");
                    messageBuilder.append("\"5min\":").append(load.get("5min").getAsDouble()).append(",");
                    messageBuilder.append("\"15min\":").append(load.get("15min").getAsDouble());
                    messageBuilder.append("},");
                }
                
                // 进程数
                if (system.has("processes")) {
                    JsonObject processes = system.getAsJsonObject("processes");
                    messageBuilder.append("\"processes\":{");
                    messageBuilder.append("\"count\":").append(processes.get("count").getAsInt());
                    messageBuilder.append("},");
                }
                
                // 网络连接
                if (system.has("network")) {
                    JsonObject network = system.getAsJsonObject("network");
                    messageBuilder.append("\"network\":{");
                    messageBuilder.append("\"tcp_connections\":").append(network.get("tcp_connections").getAsInt());
                    messageBuilder.append("},");
                }
            }
            
            // 移除最后的逗号
            if (messageBuilder.charAt(messageBuilder.length() - 1) == ',') {
                messageBuilder.deleteCharAt(messageBuilder.length() - 1);
            }
            
            messageBuilder.append("}");
            
            return messageBuilder.toString();
            
        } catch (Exception e) {
            logger.info("构建WebSocket消息时发生错误", e);
            // 返回简单的错误消息
            return "{\"type\":\"systemMonitor\",\"error\":\"Failed to parse system data\",\"timestamp\":" + System.currentTimeMillis() + "}";
        }
    }
    
    /**
     * 手动触发一次监控
     */
    public void triggerMonitor() {
        if (started) {
            scheduler.submit(this::executeMonitorScript);
            logger.info("手动触发系统监控");
        } else {
            logger.info("系统监控服务未启动，无法手动触发");
        }
    }
    
    /**
     * 检查服务是否已启动
     */
    public boolean isStarted() {
        return started;
    }
}