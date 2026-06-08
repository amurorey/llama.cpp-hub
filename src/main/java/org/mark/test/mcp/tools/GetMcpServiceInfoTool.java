package org.mark.test.mcp.tools;

import java.util.Map;

import org.mark.llamacpp.server.service.ComputerService;
import org.mark.llamacpp.server.tools.FastFetchHelper;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;


/**
 * 	获取当前服务器的信息。
 */
public class GetMcpServiceInfoTool implements IMCPTool {

	// private static final Logger logger = LoggerFactory.getLogger(GetMcpServiceInfoTool.class);

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
		return "获取当前服务所在机器的 CPU、内存和 JVM 运行信息";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema();
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments, Map<String, String> headers) {
		// logger.info("MCP工具执行: name={}, serviceKey={}", this.getMcpName(), serviceKey);
		String cpuModel = ComputerService.getCPUModel();
		int cpuCoreCount = ComputerService.getCPUCoreCount();
		long ramKb = ComputerService.getPhysicalMemoryKB();
		double ramGb = ramKb > 0 ? ramKb / 1024.0 / 1024.0 : -1;
		String javaVersion = ComputerService.getJavaVersion();
		String javaVendor = ComputerService.getJavaVendor();
		String jvmName = ComputerService.getJvmName();
		String jvmVersion = ComputerService.getJvmVersion();
		String jvmVendor = ComputerService.getJvmVendor();
		String jvmInputArguments = ComputerService.getJvmInputArguments();
		long jvmStartTime = ComputerService.getJvmStartTime();
		long jvmMaxMemoryMb = ComputerService.getJvmMaxMemoryMB();
		long jvmTotalMemoryMb = ComputerService.getJvmTotalMemoryMB();
		long jvmFreeMemoryMb = ComputerService.getJvmFreeMemoryMB();
		long jvmUsedMemoryMb = ComputerService.getJvmUsedMemoryMB();
		int jvmAvailableProcessors = ComputerService.getJvmAvailableProcessors();
 
		StringBuilder info = new StringBuilder();
		info.append("服务信息如下：\n");
		info.append("CPU Model: ").append(cpuModel).append("\n");
		info.append("CPU Cores: ").append(cpuCoreCount).append("\n");
		info.append("RAM KB: ").append(ramKb).append("\n");
		info.append("RAM GB: ").append(ramGb > 0 ? String.format("%.2f", ramGb) : "无法获取").append("\n");
		info.append("Java Version: ").append(javaVersion).append("\n");
		info.append("Java Vendor: ").append(javaVendor).append("\n");
		info.append("JVM Name: ").append(jvmName).append("\n");
		info.append("JVM Version: ").append(jvmVersion).append("\n");
		info.append("JVM Vendor: ").append(jvmVendor).append("\n");
		info.append("JVM Input Arguments: ").append(jvmInputArguments).append("\n");
		info.append("JVM Start Time: ").append(jvmStartTime).append("\n");
		info.append("JVM Max Memory MB: ").append(jvmMaxMemoryMb).append("\n");
		info.append("JVM Total Memory MB: ").append(jvmTotalMemoryMb).append("\n");
		info.append("JVM Free Memory MB: ").append(jvmFreeMemoryMb).append("\n");
		info.append("JVM Used Memory MB: ").append(jvmUsedMemoryMb).append("\n");
		info.append("JVM Available Processors: ").append(jvmAvailableProcessors);

		FastFetchHelper.ComputerInfo ffInfo = FastFetchHelper.getInstance().getInfo();
		if (!ffInfo.hasError()) {
			info.append("\n\n--- 详细硬件信息 (FastFetch) ---\n");
			info.append("CPU 温度: ").append(ffInfo.getCpuTemperature()).append("°C\n");
			info.append("物理核心: ").append(ffInfo.getPhysicalCores()).append(", 逻辑核心: ").append(ffInfo.getLogicalCores()).append("\n");

			for (FastFetchHelper.GpuInfo gpu : ffInfo.getGpus()) {
				info.append("GPU [").append(gpu.getIndex()).append("]: ")
						.append(gpu.getVendor()).append(" ").append(gpu.getName())
						.append(" (Driver: ").append(gpu.getDriver()).append(", Temp: ").append(gpu.getTemperature()).append("°C)\n");
				info.append("  - 显存: Dedicated ").append(gpu.getDedicatedMemoryBytes() > 0 ? gpu.getDedicatedMemoryBytes() / 1024 / 1024 + "MB" : "未知")
						.append(", Shared ").append(gpu.getSharedMemoryBytes() > 0 ? gpu.getSharedMemoryBytes() / 1024 / 1024 + "MB" : "未知").append("\n");
			}

			for (FastFetchHelper.BatteryInfo bat : ffInfo.getBatteries()) {
				info.append("电池 [").append(bat.getName()).append("]: 温度 ").append(bat.getTemperature()).append("°C, 容量 ").append(bat.getCapacity()).append("%\n");
			}
		} else {
			info.append("\n\n(FastFetch 详细硬件信息获取失败: ").append(ffInfo.getError()).append(")");
		}

		return new McpMessage().addText(info.toString());
	}
}
