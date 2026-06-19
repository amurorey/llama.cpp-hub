package org.mark.llamacpp.server.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 	这个东西用来执行gpu-info，以交互模式运行持久进程。
 */
public class GPUInfoHelper {

	private static final Logger logger = LoggerFactory.getLogger(GPUInfoHelper.class);
	
	private static final GPUInfoHelper INSTANCE = new GPUInfoHelper();
	
	private static final String PROMPT = "gpu-info> ";
	private static final long READ_TIMEOUT_MS = 30000;

	private volatile boolean initialized = false;
	private volatile boolean closed = false;
	private String exePath;
	private boolean available = false;
	private String initError;

	private Process process;
	private BufferedWriter stdinWriter;
	private BufferedReader stdoutReader;
	private Thread stdoutThread;
	private final Object lock = new Object();
	private volatile boolean processRunning = false;
	private volatile boolean promptReady = false;
	private volatile boolean commandDone = false;
	private StringBuilder outputBuffer = new StringBuilder();

	private GPUInfoHelper() {
	}

	public static GPUInfoHelper getInstance() {
		return INSTANCE;
	}

	public synchronized String init() {
		// 初始化操作只执行一次，简单的验证
		if (this.closed) {
			this.initError = "helper closed for update";
			return this.initError;
		}
		if (this.initialized)
			return this.initError;
		this.initialized = true;

		try {
			String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
			String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

			if (!arch.equals("amd64") && !arch.equals("x86_64")) {
				throw new UnsupportedOperationException("Unsupported architecture: " + arch + " (only x64 supported)");
			}

			String platform;
			if (os.contains("win")) {
				platform = "windows";
			} else if (os.contains("linux")) {
				platform = "linux";
			} else {
				throw new UnsupportedOperationException("Unsupported OS: " + os);
			}

			String exeName = platform.equals("windows") ? "gpu-info-x64.exe" : "gpu-info-x64";
			String resourcePath = "/tools/easy-tools/" + platform + "/" + exeName;

			URL resource = GPUInfoHelper.class.getResource(resourcePath);
			if (resource == null) {
				throw new IOException("gpu-info binary not found in resources: " + resourcePath);
			}

			this.exePath = new File(resource.toURI()).getAbsolutePath();
			File exeFile = new File(this.exePath);
			if (!exeFile.exists()) {
				throw new IOException("gpu-info binary not found: " + exePath);
			}
			if (!exeFile.canExecute()) {
				if (platform.equals("linux")) {
					new ProcessBuilder("chmod", "+x", exePath).start().waitFor();
				}
				if (!exeFile.canExecute()) {
					throw new IOException("Failed to set executable permission: " + exePath);
				}
			}

			startProcess();
			this.available = true;
			return null;
		} catch (Exception e) {
			this.available = false;
			this.initError = e.getMessage();
			return this.initError;
		}
	}

	private void startProcess() throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(exePath);
		pb.redirectErrorStream(true);
		this.process = pb.start();

		this.stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		this.stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

		this.processRunning = true;
		this.promptReady = false;
		this.outputBuffer.setLength(0);

		this.stdoutThread = new Thread(this::readStdout, "gpu-info-stdout");
		this.stdoutThread.setDaemon(true);
		this.stdoutThread.start();

		long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
		synchronized (this.lock) {
			while (!this.promptReady) {
				long waitTime = deadline - System.currentTimeMillis();
				if (waitTime <= 0) {
					throw new IOException("gpu-info failed to show prompt within timeout");
				}
				this.lock.wait(waitTime);
			}
		}

		synchronized (this.lock) {
			this.outputBuffer.setLength(0);
		}
	}

	private void readStdout() {
		try {
			char[] buf = new char[8192];
			int n;
			while ((n = this.stdoutReader.read(buf)) != -1) {
				synchronized (lock) {
					this.outputBuffer.append(buf, 0, n);
					String content = this.outputBuffer.toString();
					int idx = content.indexOf(PROMPT);
					if (idx != -1) {
						if (!this.promptReady) {
							this.promptReady = true;
							this.lock.notifyAll();
						} else {
							this.commandDone = true;
							this.lock.notifyAll();
						}
					}
				}
			}
		} catch (IOException e) {
			// process exited or stream closed
		} finally {
			this.processRunning = false;
			synchronized (this.lock) {
				this.lock.notifyAll();
			}
		}
	}

	public boolean isAvailable() {
		if (this.closed)
			return false;
		if (!this.initialized)
			init();
		return this.available;
	}

	public String getInitError() {
		return this.initError;
	}

	private String sendCommand(String... flags) throws IOException, InterruptedException {
		if (!isAvailable() || !this.processRunning) {
			return null;
		}

		StringBuilder cmd = new StringBuilder();
		for (int i = 0; i < flags.length; i++) {
			if (i > 0) cmd.append(' ');
			cmd.append(flags[i]);
		}

		synchronized (this.lock) {
			this.outputBuffer.setLength(0);
			this.commandDone = false;
		}

		this.stdinWriter.write(cmd.toString());
		this.stdinWriter.write('\n');
		this.stdinWriter.flush();

		long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
		synchronized (this.lock) {
			while (!this.commandDone) {
				long waitTime = deadline - System.currentTimeMillis();
				if (waitTime <= 0) {
					throw new IOException("gpu-info command timed out: " + cmd);
				}
				this.lock.wait(waitTime);
			}
			String content = this.outputBuffer.toString();
			int promptIdx = content.indexOf(PROMPT);
			if (promptIdx != -1) {
				return content.substring(0, promptIdx).trim();
			}
			return content.trim();
		}
	}

	private synchronized void ensureProcess() {
		if (!this.processRunning) {
			try {
				startProcess();
			} catch (Exception e) {
				logger.info("[自动加载] gpu-info 重启失败: {}", e.getMessage());
			}
		}
	}

	public JsonObject getInfo() {
		try {
			if (!isAvailable()) {
				return null;
			}
			ensureProcess();
			String output = sendCommand("--json");
			if (output == null) return null;

			JsonObject root = JsonUtil.fromJson(output, JsonObject.class);
			if (root == null) {
				return null;
			}

			filterDevices(root);

			return root;
		} catch (Exception e) {
			logger.info("[自动加载] gpu-info --json 执行异常: {}", e.getMessage());
			return null;
		}
	}

	private static void filterDevices(JsonObject root) {
		JsonArray devicesArr = root.getAsJsonArray("devices");
		if (devicesArr == null)
			return;

		JsonArray filtered = new JsonArray();
		for (int i = 0; i < devicesArr.size(); i++) {
			JsonObject dev = devicesArr.get(i).getAsJsonObject();
			String name = JsonUtil.getJsonString(dev, "name", "");
			if (name.contains("llvmpipe"))
				continue;
			String type = JsonUtil.getJsonString(dev, "type", "");
			if ("CPU".equals(type))
				continue;

			removeField(dev, "vendor_id");
			removeField(dev, "device_id");

			JsonObject mem = dev.getAsJsonObject("memory");
			if (mem != null) {
				removeField(mem, "shared_ram_bytes");
				removeField(mem, "heaps");
			}

			removeField(dev, "vulkan");

			filtered.add(dev);
		}

		root.remove("devices");
		root.add("devices", filtered);
		root.addProperty("device_count", filtered.size());
	}

	private static void removeField(JsonObject obj, String key) {
		if (obj != null) {
			obj.remove(key);
		}
	}

	public JsonObject getCpuInfo() {
		try {
			if (!isAvailable()) return null;
			ensureProcess();
			String output = sendCommand("--cpu", "--json");
			if (output == null) return null;
			JsonObject root = JsonUtil.fromJson(output, JsonObject.class);
			if (root != null && root.has("system")) {
				return root.getAsJsonObject("system").getAsJsonObject("cpu");
			}
		} catch (Exception e) {
			logger.info("[自动加载] gpu-info --cpu 执行异常: {}", e.getMessage());
		}
		return null;
	}

	public JsonObject getRamInfo() {
		try {
			if (!isAvailable()) return null;
			ensureProcess();
			String output = sendCommand("--ram", "--json");
			if (output == null) return null;
			JsonObject root = JsonUtil.fromJson(output, JsonObject.class);
			if (root != null && root.has("system")) {
				return root.getAsJsonObject("system").getAsJsonObject("memory");
			}
		} catch (Exception e) {
			logger.info("[自动加载] gpu-info --ram 执行异常: {}", e.getMessage());
		}
		return null;
	}

	/**
	 * 获取系统可用内存和GPU显存信息。
	 * @return Map 包含:
	 *   - "availableRam": 可用 RAM (bytes)
	 *   - "availableVram": 可用 VRAM (bytes)
	 *   失败返回 null
	 */
	public Map<String, Long> getMemoryInfo() {
		try {
			if (!isAvailable()) {
				logger.info("[自动加载] gpu-info 不可用: {}", getInitError());
				return null;
			}
			ensureProcess();
			String output = sendCommand("--memory", "--json");
			if (output == null) return null;
			JsonObject root = JsonUtil.fromJson(output, JsonObject.class);
			if (root == null) {
				logger.info("[自动加载] gpu-info JSON 解析失败");
				return null;
			}
			Map<String, Long> result = new HashMap<>();

			// 解析 system.memory
			JsonObject system = root.getAsJsonObject("system");
			if (system == null) return null;
			JsonObject mem = system.getAsJsonObject("memory");
			if (mem == null) return null;

			long totalRam = mem.has("total_bytes") ? mem.get("total_bytes").getAsLong() : 0;
			long usedRam = mem.has("used_bytes") ? mem.get("used_bytes").getAsLong() : 0;
			result.put("availableRam", totalRam - usedRam);

			// 解析 devices[].sensors 计算可用显存
			long totalAvailableVram = 0;
			JsonArray devices = root.getAsJsonArray("devices");
			if (devices != null) {
				for (JsonElement dev : devices) {
					JsonObject sensorsObj = dev.getAsJsonObject().getAsJsonObject("sensors");
					if (sensorsObj != null && sensorsObj.has("memory_total_bytes") && sensorsObj.has("memory_used_bytes")) {
						JsonElement totalEl = sensorsObj.get("memory_total_bytes");
						JsonElement usedEl = sensorsObj.get("memory_used_bytes");
						if (!totalEl.isJsonNull() && !usedEl.isJsonNull()) {
							long total = totalEl.getAsLong();
							long used = usedEl.getAsLong();
							totalAvailableVram += (total - used);
						}
					} else {
						// fallback: 使用 dedicated_vram_bytes 总容量
						JsonObject devMem = dev.getAsJsonObject().getAsJsonObject("memory");
						if (devMem != null && devMem.has("dedicated_vram_bytes")) {
							totalAvailableVram += devMem.get("dedicated_vram_bytes").getAsLong();
						}
					}
				}
			}
			result.put("availableVram", totalAvailableVram);

			logger.info("[自动加载] 系统内存: availableRam={} GiB, availableVram={} GiB",
				Math.round(result.get("availableRam") / 1024.0 / 1024.0 / 1024.0 * 100.0) / 100.0,
				Math.round(result.get("availableVram") / 1024.0 / 1024.0 / 1024.0 * 100.0) / 100.0);
			return result;
		} catch (Exception e) {
			logger.info("[自动加载] gpu-info 执行异常: {}", e.getMessage());
			return null;
		}
	}

	public synchronized void close() {
		if (this.process != null) {
			try {
				this.stdinWriter.write("--exit\n");
				this.stdinWriter.flush();
			} catch (IOException e) {
				// ignore
			}
			try {
				if (!this.process.waitFor(5000, TimeUnit.MILLISECONDS)) {
					this.process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				this.process.destroyForcibly();
				Thread.currentThread().interrupt();
			}
			this.process = null;
		}
		this.processRunning = false;
		this.available = false;
		this.initialized = false;
		this.closed = true;
	}

	/**
	 * 更新流程结束后重新打开 helper：清除 closed 守卫并以新版本 exe 重新初始化。
	 */
	public synchronized void reopen() {
		this.closed = false;
		this.init();
	}
}
