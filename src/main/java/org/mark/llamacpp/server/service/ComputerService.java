package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

import org.mark.llamacpp.server.tools.GPUInfoHelper;

import com.google.gson.JsonObject;

/**
 * 用来获取一些硬件信息，和GPUInfoHelper是重叠的，而且不如GPUInfoHelper。但是后者属于外部工具，如果被安全软件误杀或者用户删除，就没法用了，这里算是一个简单的兜底。
 */
public class ComputerService {
	
	
	public static ComputerService getInstance() {
		return INSTANCE;
	}
	
	private static final ComputerService INSTANCE = new ComputerService();
	

	/**
	 * 获取 CPU 型号
	 */
	public static String getCPUModel() {
		// 1. GPUInfoHelper
		try {
			JsonObject cpuInfo = GPUInfoHelper.getInstance().getCpuInfo();
			if (cpuInfo != null && cpuInfo.has("name")) {
				String name = cpuInfo.get("name").getAsString();
				if (name != null && !name.trim().isEmpty()) return name.trim();
			}
		} catch (Exception e) { /* fallback */ }

		// 2. Fallback
		try {
			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("win")) {
				String cpuFromRegistry = parseWindowsCpuModelFromRegistry(
						tryExecAndRead("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString"));
				if (!cpuFromRegistry.isEmpty()) return cpuFromRegistry;
				String cpuFromWmic = parseWindowsCpuModelFromWmic(tryExecAndRead("wmic", "cpu", "get", "name"));
				if (!cpuFromWmic.isEmpty()) return cpuFromWmic;
				String cpuFromEnv = normalizeCpuModel(System.getenv("PROCESSOR_IDENTIFIER"));
				if (!cpuFromEnv.isEmpty()) return cpuFromEnv;
				return "无法解析CPU信息";
			} else if (os.contains("linux")) {
				String cpuFromProc = parseLinuxCpuModel(execAndRead("cat", "/proc/cpuinfo"));
				if (!cpuFromProc.isEmpty()) return cpuFromProc;
				String cpuFromLscpu = parseLinuxCpuModel(execAndRead("lscpu"));
				if (!cpuFromLscpu.isEmpty()) return cpuFromLscpu;
				return "无法解析CPU信息";
			} else {
				return "不支持的操作系统";
			}
		} catch (Exception e) {
			e.printStackTrace();
			return "获取CPU信息失败: " + e.getMessage();
		}
	}

	private static String parseLinuxCpuModel(String rawOutput) {
		if (rawOutput == null || rawOutput.trim().isEmpty()) return "";
		for (String l : rawOutput.split("\n")) {
			if (l == null) continue;
			int idx = l.indexOf(':');
			if (idx <= 0) continue;
			String key = l.substring(0, idx).trim().toLowerCase();
			if ("model name".equals(key)) {
				String value = l.substring(idx + 1).trim();
				if (!value.isEmpty()) return value;
			}
		}
		return "";
	}

	private static String parseWindowsCpuModelFromRegistry(String rawOutput) {
		if (rawOutput == null || rawOutput.trim().isEmpty()) return "";
		for (String l : rawOutput.split("\n")) {
			String line = normalizeCpuModel(l);
			if (line.isEmpty()) continue;
			String lowerLine = line.toLowerCase();
			if (!lowerLine.contains("processornamestring")) continue;
			int idx = lowerLine.indexOf("processornamestring");
			String value = line.substring(idx + "processornamestring".length()).trim();
			value = value.replaceFirst("^REG_\\S+\\s*", "").trim();
			if (!value.isEmpty()) return value;
		}
		return "";
	}

	private static String parseWindowsCpuModelFromWmic(String rawOutput) {
		if (rawOutput == null || rawOutput.trim().isEmpty()) return "";
		for (String l : rawOutput.split("\n")) {
			String line = normalizeCpuModel(l);
			if (line.isEmpty()) continue;
			if ("name".equalsIgnoreCase(line)) continue;
			return line;
		}
		return "";
	}

	private static String normalizeCpuModel(String value) {
		if (value == null) return "";
		return value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
	}

	private static String execAndRead(String... command) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(command);
		Process process = pb.start();
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		StringBuilder output = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			output.append(line).append("\n");
		}
		process.waitFor();
		return output.toString().trim();
	}

	private static String tryExecAndRead(String... command) {
		try {
			return execAndRead(command);
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * 获取物理内存大小（单位：GB）
	 */
	public static long getPhysicalMemoryKB() {
		// 1. GPUInfoHelper
		try {
			JsonObject ramInfo = GPUInfoHelper.getInstance().getRamInfo();
			if (ramInfo != null && ramInfo.has("total_bytes")) {
				long bytes = ramInfo.get("total_bytes").getAsLong();
				if (bytes > 0) return bytes / 1024;
			}
		} catch (Exception e) { /* fallback */ }
		// 2. JMX
		try {
			com.sun.management.OperatingSystemMXBean osBean =
					(com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
			return osBean.getTotalMemorySize() / 1024;
		} catch (Exception e) {
			return -1;
		}
	}

	/**
	 * 获取 CPU 核心数
	 */
	public static int getCPUCoreCount() {
		// 1. GPUInfoHelper (physical cores)
		try {
			JsonObject cpuInfo = GPUInfoHelper.getInstance().getCpuInfo();
			if (cpuInfo != null && cpuInfo.has("cores")) {
				return cpuInfo.get("cores").getAsInt();
			}
		} catch (Exception e) { /* fallback */ }
		// 2. Java API
		try {
			return Runtime.getRuntime().availableProcessors();
		} catch (Exception e) {
			return -1;
		}
	}

	public static String getJavaVersion() {
		return System.getProperty("java.version", "");
	}

	public static String getJavaVendor() {
		return System.getProperty("java.vendor", "");
	}

	public static String getJvmName() {
		return System.getProperty("java.vm.name", "");
	}

	public static String getJvmVersion() {
		return System.getProperty("java.vm.version", "");
	}

	public static String getJvmVendor() {
		return System.getProperty("java.vm.vendor", "");
	}

	public static String getJvmInputArguments() {
		try {
			RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
			return String.join(" ", runtimeMxBean.getInputArguments());
		} catch (Exception e) {
			return "";
		}
	}

	public static long getJvmStartTime() {
		try {
			return ManagementFactory.getRuntimeMXBean().getStartTime();
		} catch (Exception e) {
			return -1;
		}
	}

	public static long getJvmMaxMemoryMB() {
		return toMb(Runtime.getRuntime().maxMemory());
	}

	public static long getJvmTotalMemoryMB() {
		return toMb(Runtime.getRuntime().totalMemory());
	}

	public static long getJvmFreeMemoryMB() {
		return toMb(Runtime.getRuntime().freeMemory());
	}

	public static long getJvmUsedMemoryMB() {
		Runtime runtime = Runtime.getRuntime();
		return toMb(runtime.totalMemory() - runtime.freeMemory());
	}

	public static int getJvmAvailableProcessors() {
		return Runtime.getRuntime().availableProcessors();
	}

	public static JsonObject getFullInfo() {
		JsonObject root = new JsonObject();

		// CPU
		JsonObject cpu = new JsonObject();
		cpu.addProperty("model", getCPUModel());
		cpu.addProperty("physicalCores", getCPUCoreCount());
		cpu.addProperty("logicalProcessors", getJvmAvailableProcessors());
		root.add("cpu", cpu);

		// Memory
		JsonObject memory = new JsonObject();
		long ramKb = getPhysicalMemoryKB();
		memory.addProperty("totalKB", ramKb);
		memory.addProperty("totalGB", ramKb > 0 ? ramKb / 1024.0 / 1024.0 : -1);
		root.add("memory", memory);

		// Java
		JsonObject javaInfo = new JsonObject();
		javaInfo.addProperty("version", getJavaVersion());
		javaInfo.addProperty("vendor", getJavaVendor());
		root.add("java", javaInfo);

		// JVM
		JsonObject jvm = new JsonObject();
		jvm.addProperty("name", getJvmName());
		jvm.addProperty("version", getJvmVersion());
		jvm.addProperty("vendor", getJvmVendor());
		jvm.addProperty("inputArguments", getJvmInputArguments());
		jvm.addProperty("startTime", getJvmStartTime());
		jvm.addProperty("maxMemoryMB", getJvmMaxMemoryMB());
		jvm.addProperty("totalMemoryMB", getJvmTotalMemoryMB());
		jvm.addProperty("freeMemoryMB", getJvmFreeMemoryMB());
		jvm.addProperty("usedMemoryMB", getJvmUsedMemoryMB());
		jvm.addProperty("availableProcessors", getJvmAvailableProcessors());
		root.add("jvm", jvm);

		// GPU
		JsonObject gpuData = GPUInfoHelper.getInstance().getInfo();
		if (gpuData != null) {
			root.add("gpu", gpuData);
		} else {
			JsonObject gpuErr = new JsonObject();
			gpuErr.addProperty("error", "gpu-info 调用失败，无法获取信息");
			root.add("gpu", gpuErr);
		}

		return root;
	}

	private static long toMb(long bytes) {
		if (bytes < 0) return -1;
		return bytes / 1024 / 1024;
	}
}
