package org.mark.llamacpp.ollama;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Ollama API 兼容层的通用工具方法集合：
 * - 时间格式（modified_at / created_at）
 * - 参数量猜测（parameter_size）
 * - OpenAI <-> Ollama 的 tool calls/消息字段适配
 * - OpenAI error 抽取与 HTTP body 读取
 * - GGUF tensor 结构读取（用于 /api/show）
 */
public final class OllamaApiTool {
	private OllamaApiTool() {
	}
	
	private static final DateTimeFormatter OLLAMA_TIME_FORMATTER = new DateTimeFormatterBuilder()
			.appendPattern("yyyy-MM-dd'T'HH:mm:ss")
			.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
			.appendLiteral('Z')
			.toFormatter();
	
	private static final Pattern PARAM_SIZE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)([bBmMkK])");

	public static String formatOllamaTime(Instant instant) {
		Instant safe = instant == null ? Instant.now() : instant;
		return OLLAMA_TIME_FORMATTER.format(OffsetDateTime.ofInstant(safe, ZoneOffset.UTC));
	}

	public static String guessParameterSize(String modelId, long sizeBytes) {
		String source = modelId == null ? "" : modelId.trim();
		if (source.contains(":")) {
			String[] parts = source.split(":", 2);
			if (parts.length == 2 && parts[1] != null && !parts[1].isBlank()) {
				source = parts[1].trim();
			}
		}

		Matcher m = PARAM_SIZE_PATTERN.matcher(source);
		if (m.find()) {
			try {
				double value = Double.parseDouble(m.group(1));
				String unit = m.group(2);
				if (unit != null && !unit.isBlank()) {
					char u = Character.toUpperCase(unit.charAt(0));
					double params;
					if (u == 'B') {
						params = value * 1_000_000_000d;
					} else if (u == 'M') {
						params = value * 1_000_000d;
					} else if (u == 'K') {
						params = value * 1_000d;
					} else {
						params = 0d;
					}

					if (params >= 1_000_000_000d) {
						return String.format(java.util.Locale.ROOT, "%.2fB", params / 1_000_000_000d);
					}
					if (params >= 1_000_000d) {
						return String.format(java.util.Locale.ROOT, "%.2fM", params / 1_000_000d);
					}
					if (params >= 1_000d) {
						return String.format(java.util.Locale.ROOT, "%.2fK", params / 1_000d);
					}
				}
			} catch (Exception ignore) {
			}
		}

		double mBytes = sizeBytes <= 0 ? 0d : (sizeBytes / 1024d / 1024d);
		return String.format(java.util.Locale.ROOT, "%.2fM", mBytes);
	}

	public static String sha256Hex(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit((b & 0xF), 16));
			}
			return sb.toString();
		} catch (Exception e) {
			return UUID.randomUUID().toString().replace("-", "");
		}
	}



	public static JsonArray normalizeOllamaMessagesForOpenAI(JsonArray messages) {
		if (messages == null) {
			return new JsonArray();
		}
		JsonArray out = new JsonArray();
		Map<Integer, String> toolCallIndexToId = new HashMap<>();
		for (int i = 0; i < messages.size(); i++) {
			JsonElement el = messages.get(i);
			if (el == null || el.isJsonNull() || !el.isJsonObject()) {
				continue;
			}
			JsonObject msg = el.getAsJsonObject().deepCopy();
			normalizeOneMessageForOpenAI(msg, toolCallIndexToId);
			out.add(msg);
		}
		return out;
	}
	
	public static void applyOllamaToolsToOpenAI(JsonObject openAiReq, JsonObject ollamaReq) {
		if (openAiReq == null || ollamaReq == null) {
			return;
		}
		JsonElement tools = ollamaReq.get("tools");
		if (tools != null && !tools.isJsonNull() && tools.isJsonArray()) {
			openAiReq.add("tools", tools.deepCopy());
		}
		JsonElement toolChoice = ollamaReq.get("tool_choice");
		if (toolChoice != null && !toolChoice.isJsonNull()) {
			openAiReq.add("tool_choice", toolChoice.deepCopy());
		}
	}

	public static JsonObject toOpenAIEmbeddingsRequest(JsonObject ollamaReq) {
		if (ollamaReq == null) {
			return null;
		}
		JsonElement input = ollamaReq.get("input");
		if (input == null || input.isJsonNull()) {
			return null;
		}
		JsonObject out = new JsonObject();
		if (input.isJsonArray() || input.isJsonPrimitive()) {
			out.add("input", input.deepCopy());
		} else {
			out.addProperty("input", JsonUtil.jsonValueToString(input));
		}
		return out;
	}

	public static Map<String, Object> toOllamaEmbedResponse(String modelName, JsonObject openAiResp) {
		return toOllamaEmbedResponse(modelName, openAiResp, 0L);
	}
	
	public static Map<String, Object> toOllamaEmbedResponse(String modelName, JsonObject openAiResp, long totalDurationNs) {
		Map<String, Object> out = new HashMap<>();
		String resolvedModel = modelName;
		if ((resolvedModel == null || resolvedModel.isBlank()) && openAiResp != null) {
			resolvedModel = JsonUtil.getJsonString(openAiResp, "model", null);
		}
		out.put("model", resolvedModel == null ? "" : resolvedModel);

		List<List<Double>> embeddings = new ArrayList<>();
		long promptEvalCount = 0L;
		if (openAiResp != null) {
			try {
				JsonObject usage = openAiResp.has("usage") && openAiResp.get("usage").isJsonObject() ? openAiResp.getAsJsonObject("usage") : null;
				if (usage != null) {
					promptEvalCount = JsonUtil.getJsonLong(usage, "prompt_tokens", 0L);
				}
			} catch (Exception ignore) {
			}
			try {
				JsonElement dataEl = openAiResp.get("data");
				if (dataEl != null && !dataEl.isJsonNull() && dataEl.isJsonArray()) {
					JsonArray data = dataEl.getAsJsonArray();
					for (int i = 0; i < data.size(); i++) {
						JsonElement itemEl = data.get(i);
						if (itemEl == null || itemEl.isJsonNull() || !itemEl.isJsonObject()) {
							continue;
						}
						JsonObject item = itemEl.getAsJsonObject();
						JsonElement embEl = item.get("embedding");
						if (embEl == null || embEl.isJsonNull() || !embEl.isJsonArray()) {
							continue;
						}
						JsonArray arr = embEl.getAsJsonArray();
						List<Double> vec = new ArrayList<>(arr.size());
						for (int j = 0; j < arr.size(); j++) {
							JsonElement v = arr.get(j);
							if (v == null || v.isJsonNull()) {
								vec.add(Double.valueOf(0d));
								continue;
							}
							try {
								vec.add(Double.valueOf(v.getAsDouble()));
							} catch (Exception e) {
								vec.add(Double.valueOf(0d));
							}
						}
						embeddings.add(vec);
					}
				}
			} catch (Exception ignore) {
			}
		}

		out.put("embeddings", embeddings);
		out.put("total_duration", Long.valueOf(Math.max(0L, totalDurationNs)));
		out.put("load_duration", Long.valueOf(0L));
		out.put("prompt_eval_count", Long.valueOf(Math.max(0L, promptEvalCount)));
		return out;
	}
	
	/**
	 * 	构建最后的消息。
	 * @param timings
	 * @return
	 */
	public static Map<String, Object> buildOllamaTimingFields(JsonObject timings) {
		Map<String, Object> out = new HashMap<>();
		long promptEvalCount = 0L;
		long evalCount = 0L;
		long promptEvalDuration = 0L;
		long evalDuration = 0L;
		if (timings != null) {
			// 输入的token数
			int promptN = JsonUtil.getJsonInt(timings, "prompt_n", 0).intValue();
			int cacheN = JsonUtil.getJsonInt(timings, "cache_n", 0).intValue();
			// 生成的token数
			int predictedN = JsonUtil.getJsonInt(timings, "predicted_n", 0).intValue();

			promptEvalCount = (long) promptN + (long) cacheN;
			evalCount = (long) predictedN;
			// 计算耗时
			double promptTokens = (double) promptEvalCount;
			Double promptPerSecond = safeDouble(timings, "prompt_per_second");
			if (promptTokens > 0d && promptPerSecond != null && Double.isFinite(promptPerSecond.doubleValue()) && promptPerSecond.doubleValue() > 0d) {
				double seconds = promptTokens / promptPerSecond.doubleValue();
				promptEvalDuration = Math.max(0L, Math.round(seconds * 1_000_000_000d));
			} else {
				Double promptPerTokenMs = safeDouble(timings, "prompt_per_token_ms");
				if (promptTokens > 0d && promptPerTokenMs != null && Double.isFinite(promptPerTokenMs.doubleValue()) && promptPerTokenMs.doubleValue() > 0d) {
					promptEvalDuration = msToNs(Double.valueOf(promptPerTokenMs.doubleValue() * promptTokens));
				}
			}
			// 计算耗时
			double predictedTokens = (double) evalCount;
			Double predictedPerSecond = safeDouble(timings, "predicted_per_second");
			if (predictedTokens > 0d && predictedPerSecond != null && Double.isFinite(predictedPerSecond.doubleValue()) && predictedPerSecond.doubleValue() > 0d) {
				double seconds = predictedTokens / predictedPerSecond.doubleValue();
				evalDuration = Math.max(0L, Math.round(seconds * 1_000_000_000d));
			} else {
				Double predictedPerTokenMs = safeDouble(timings, "predicted_per_token_ms");
				if (predictedTokens > 0d && predictedPerTokenMs != null && Double.isFinite(predictedPerTokenMs.doubleValue()) && predictedPerTokenMs.doubleValue() > 0d) {
					evalDuration = msToNs(Double.valueOf(predictedPerTokenMs.doubleValue() * predictedTokens));
				}
			}
		}

		out.put("done", true);
		// 生成响应所花费的时间
		out.put("total_duration", Long.valueOf(promptEvalDuration + evalDuration));
		// 加载模型耗时，这玩意只能是0
		out.put("load_duration", Long.valueOf(0L));
		// 输入提示词中的token
		out.put("prompt_eval_count", Long.valueOf(promptEvalCount));
		// 评估提示所花费的时间（纳秒）
		out.put("prompt_eval_duration", Long.valueOf(promptEvalDuration));
		// eval_count：响应中的令牌数
		out.put("eval_count", Long.valueOf(evalCount));
		// 生成响应所花费的时间（纳秒）
		out.put("eval_duration", Long.valueOf(evalDuration));
		return out;
	}

	public static JsonElement extractToolCallsFromOpenAIMessage(JsonObject msg, Map<Integer, String> indexToId,
			boolean includeLegacyFunctionCall) {
		if (msg == null) {
			return null;
		}
		JsonElement tcs = msg.get("tool_calls");
		if (tcs != null && !tcs.isJsonNull() && tcs.isJsonArray()) {
			JsonArray copy = tcs.getAsJsonArray().deepCopy();
			ensureToolCallIdsInArray(copy, indexToId);
			return copy;
		}
		if (includeLegacyFunctionCall) {
			JsonObject fc = (msg.has("function_call") && msg.get("function_call").isJsonObject())
					? msg.getAsJsonObject("function_call")
					: null;
			if (fc != null) {
				return toolCallsFromFunctionCall(fc, null);
			}
		}
		return null;
	}

	public static JsonElement toOllamaToolCalls(JsonElement openAiToolCalls) {
		if (openAiToolCalls == null || openAiToolCalls.isJsonNull()) {
			return null;
		}
		if (openAiToolCalls.isJsonArray()) {
			JsonArray arr = openAiToolCalls.getAsJsonArray();
			if (arr.size() == 0) {
				return null;
			}
			JsonArray out = new JsonArray();
			for (int i = 0; i < arr.size(); i++) {
				JsonElement el = arr.get(i);
				if (el == null || el.isJsonNull() || !el.isJsonObject()) {
					continue;
				}
				JsonObject tc = el.getAsJsonObject();
				JsonObject fn = (tc.has("function") && tc.get("function").isJsonObject()) ? tc.getAsJsonObject("function")
						: null;
				if (fn == null) {
					continue;
				}
				String name = JsonUtil.getJsonString(fn, "name", null);
				if (name == null || name.isBlank()) {
					continue;
				}
				JsonElement argsEl = fn.get("arguments");
				JsonElement args = null;
				if (argsEl == null || argsEl.isJsonNull()) {
					args = new JsonObject();
				} else if (argsEl.isJsonPrimitive() && argsEl.getAsJsonPrimitive().isString()) {
					args = tryParseJson(argsEl.getAsString());
					if (args == null) {
						String s = argsEl.getAsString();
						args = (s == null || s.isBlank()) ? new JsonObject() : argsEl.deepCopy();
					}
				} else if (argsEl.isJsonObject() || argsEl.isJsonArray()) {
					args = argsEl.deepCopy();
				} else {
					args = argsEl.deepCopy();
				}

				JsonObject outFn = new JsonObject();
				outFn.addProperty("name", name);
				outFn.add("arguments", args == null ? new JsonObject() : args);

				JsonObject outTc = new JsonObject();
				outTc.add("function", outFn);
				out.add(outTc);
			}
			return out.size() == 0 ? null : out;
		}
		if (openAiToolCalls.isJsonObject()) {
			JsonObject obj = openAiToolCalls.getAsJsonObject();
			if (obj.has("function_call") && obj.get("function_call").isJsonObject()) {
				JsonArray arr = toolCallsFromFunctionCall(obj.getAsJsonObject("function_call"), null);
				return arr == null ? null : toOllamaToolCalls(arr);
			}
		}
		return null;
	}

	public static String extractOpenAIErrorMessage(String responseBody) {
		if (responseBody == null || responseBody.isBlank()) {
			return null;
		}
		try {
			JsonObject parsed = JsonUtil.fromJson(responseBody, JsonObject.class);
			if (parsed == null) {
				return null;
			}
			JsonObject err = parsed.has("error") && parsed.get("error").isJsonObject() ? parsed.getAsJsonObject("error")
					: null;
			if (err == null) {
				return null;
			}
			String msg = JsonUtil.getJsonString(err, "message", null);
			return msg == null || msg.isBlank() ? null : msg.trim();
		} catch (Exception e) {
			return null;
		}
	}

	public static String readBody(HttpURLConnection connection, boolean successStream) throws IOException {
		if (connection == null) {
			return "";
		}
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(successStream ? connection.getInputStream() : connection.getErrorStream(),
						StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		}
	}

	public static List<Map<String, Object>> readGgufTensors(File ggufFile) throws IOException {
		if (ggufFile == null || !ggufFile.exists() || !ggufFile.isFile()) {
			return new ArrayList<>();
		}
		try (RandomAccessFile raf = new RandomAccessFile(ggufFile, "r"); FileChannel ignore = raf.getChannel()) {
			byte[] magicBytes = new byte[4];
			raf.readFully(magicBytes);
			String magic = new String(magicBytes, StandardCharsets.US_ASCII);
			if (!"GGUF".equals(magic)) {
				return new ArrayList<>();
			}

			readI32Le(raf);
			long tensorCount = readU64Le(raf);
			long kvCount = readU64Le(raf);

			for (long i = 0; i < kvCount; i++) {
				readGgufString(raf);
				int type = readI32Le(raf);
				skipGgufValue(raf, type);
			}

			List<Map<String, Object>> tensors = new ArrayList<>((int) Math.min(Math.max(tensorCount, 0), 4096));
			for (long i = 0; i < tensorCount; i++) {
				String name = readGgufString(raf);
				int nDims = readI32Le(raf);
				List<Long> shape = new ArrayList<>(Math.max(nDims, 0));
				for (int d = 0; d < nDims; d++) {
					shape.add(Long.valueOf(readU64Le(raf)));
				}
				int tensorType = readI32Le(raf);
				readU64Le(raf);

				Map<String, Object> item = new HashMap<>();
				item.put("name", name);
				item.put("type", ggmlTypeName(tensorType));
				item.put("shape", shape);
				tensors.add(item);
			}

			return tensors;
		} catch (EOFException eof) {
			return new ArrayList<>();
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	private static void normalizeOneMessageForOpenAI(JsonObject msg, Map<Integer, String> toolCallIndexToId) {
		if (msg == null) {
			return;
		}

		JsonElement imagesEl = msg.get("images");
		if (imagesEl != null && !imagesEl.isJsonNull() && imagesEl.isJsonArray() && imagesEl.getAsJsonArray().size() > 0) {
			JsonArray images = imagesEl.getAsJsonArray();
			JsonArray contentArr = null;
			JsonElement originalContent = msg.get("content");
			if (originalContent != null && !originalContent.isJsonNull() && originalContent.isJsonArray()) {
				contentArr = originalContent.getAsJsonArray().deepCopy();
			} else {
				contentArr = new JsonArray();
				String text = originalContent == null || originalContent.isJsonNull() ? null : JsonUtil.jsonValueToString(originalContent);
				if (text != null && !text.isBlank()) {
					JsonObject textPart = new JsonObject();
					textPart.addProperty("type", "text");
					textPart.addProperty("text", text);
					contentArr.add(textPart);
				}
			}

			for (int i = 0; i < images.size(); i++) {
				JsonElement img = images.get(i);
				if (img == null || img.isJsonNull()) {
					continue;
				}
				String raw = null;
				try {
					if (img.isJsonPrimitive()) {
						raw = img.getAsString();
					} else {
						raw = JsonUtil.jsonValueToString(img);
					}
				} catch (Exception ignore) {
				}
				if (raw == null || raw.isBlank()) {
					continue;
				}

				String url = toImageUrl(raw);
				if (url == null || url.isBlank()) {
					continue;
				}
				JsonObject imagePart = new JsonObject();
				imagePart.addProperty("type", "image_url");
				JsonObject imageUrl = new JsonObject();
				imageUrl.addProperty("url", url);
				imagePart.add("image_url", imageUrl);
				contentArr.add(imagePart);
			}

			msg.add("content", contentArr);
			msg.remove("images");
		}

		JsonElement contentEl = msg.get("content");
		if (contentEl != null && !contentEl.isJsonNull() && contentEl.isJsonObject()) {
			msg.addProperty("content", JsonUtil.jsonValueToString(contentEl));
		}

		JsonElement toolCallsEl = msg.get("tool_calls");
		if (toolCallsEl != null && !toolCallsEl.isJsonNull() && toolCallsEl.isJsonArray()) {
			JsonArray toolCalls = toolCallsEl.getAsJsonArray();
			normalizeToolCallsForOpenAI(toolCalls, toolCallIndexToId);
		}

		JsonObject fc = (msg.has("function_call") && msg.get("function_call").isJsonObject()) ? msg.getAsJsonObject("function_call")
				: null;
		if (fc != null && (toolCallsEl == null || toolCallsEl.isJsonNull())) {
			JsonArray arr = toolCallsFromFunctionCall(fc, null);
			if (arr != null) {
				msg.add("tool_calls", arr);
			}
			msg.remove("function_call");
		}

		String role = JsonUtil.getJsonString(msg, "role", null);
		if (role != null && role.equals("tool")) {
			JsonElement toolContent = msg.get("content");
			if (toolContent != null && !toolContent.isJsonNull() && toolContent.isJsonObject()) {
				msg.addProperty("content", JsonUtil.jsonValueToString(toolContent));
			}
		}
	}

	private static String toImageUrl(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim();
		if (s.isEmpty()) {
			return null;
		}
		if (s.startsWith("data:")) {
			return s;
		}
		String lower = s.toLowerCase();
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			return s;
		}

		String b64 = stripWhitespace(s);
		if (b64.isEmpty()) {
			return null;
		}
		String mime = guessImageMimeFromBase64(b64);
		return "data:" + mime + ";base64," + b64;
	}

	private static String stripWhitespace(String s) {
		if (s == null || s.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f') {
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}

	private static String guessImageMimeFromBase64(String b64) {
		if (b64 == null || b64.isEmpty()) {
			return "image/png";
		}
		try {
			int maxChars = Math.min(b64.length(), 256);
			String head = b64.substring(0, maxChars);
			int mod = head.length() % 4;
			if (mod != 0) {
				head = head.substring(0, head.length() - mod);
			}
			if (head.isEmpty()) {
				return "image/png";
			}
			byte[] bytes = Base64.getDecoder().decode(head);
			if (bytes == null || bytes.length < 4) {
				return "image/png";
			}
			if (bytes.length >= 8
					&& (bytes[0] & 0xFF) == 0x89
					&& (bytes[1] & 0xFF) == 0x50
					&& (bytes[2] & 0xFF) == 0x4E
					&& (bytes[3] & 0xFF) == 0x47
					&& (bytes[4] & 0xFF) == 0x0D
					&& (bytes[5] & 0xFF) == 0x0A
					&& (bytes[6] & 0xFF) == 0x1A
					&& (bytes[7] & 0xFF) == 0x0A) {
				return "image/png";
			}
			if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
				return "image/jpeg";
			}
			if ((bytes[0] & 0xFF) == 0x47 && (bytes[1] & 0xFF) == 0x49 && (bytes[2] & 0xFF) == 0x46 && (bytes[3] & 0xFF) == 0x38) {
				return "image/gif";
			}
			if ((bytes[0] & 0xFF) == 0x42 && (bytes[1] & 0xFF) == 0x4D) {
				return "image/bmp";
			}
			if (bytes.length >= 12
					&& (bytes[0] & 0xFF) == 0x52
					&& (bytes[1] & 0xFF) == 0x49
					&& (bytes[2] & 0xFF) == 0x46
					&& (bytes[3] & 0xFF) == 0x46
					&& (bytes[8] & 0xFF) == 0x57
					&& (bytes[9] & 0xFF) == 0x45
					&& (bytes[10] & 0xFF) == 0x42
					&& (bytes[11] & 0xFF) == 0x50) {
				return "image/webp";
			}
			return "image/png";
		} catch (Exception e) {
			return "image/png";
		}
	}

	private static void normalizeToolCallsForOpenAI(JsonArray toolCalls, Map<Integer, String> toolCallIndexToId) {
		if (toolCalls == null) {
			return;
		}
		for (int i = 0; i < toolCalls.size(); i++) {
			JsonElement el = toolCalls.get(i);
			if (el == null || el.isJsonNull() || !el.isJsonObject()) {
				continue;
			}
			JsonObject tc = el.getAsJsonObject();

			JsonObject fn = (tc.has("function") && tc.get("function").isJsonObject()) ? tc.getAsJsonObject("function") : null;
			if (fn != null) {
				String type = JsonUtil.getJsonString(tc, "type", null);
				if (type == null || type.isBlank()) {
					tc.addProperty("type", "function");
				}
				JsonElement argsEl = fn.get("arguments");
				if (argsEl != null && !argsEl.isJsonNull() && !argsEl.isJsonPrimitive()) {
					fn.addProperty("arguments", argsEl.toString());
				} else if (argsEl != null && !argsEl.isJsonNull() && argsEl.isJsonPrimitive()
						&& !argsEl.getAsJsonPrimitive().isString()) {
					fn.addProperty("arguments", JsonUtil.jsonValueToString(argsEl));
				} else if (argsEl == null || argsEl.isJsonNull()) {
					fn.addProperty("arguments", "");
				}
			}
		}
		ensureToolCallIdsInArray(toolCalls, toolCallIndexToId);
	}

	/**
	 * 	复制数字
	 * @param src
	 * @param srcKey
	 * @param dst
	 * @param dstKey
	 */
	public static void copyNumber(JsonObject src, String srcKey, JsonObject dst, String dstKey) {
		if (src == null || dst == null || srcKey == null || dstKey == null || !src.has(srcKey)) {
			return;
		}
		try {
			if (!src.get(srcKey).isJsonPrimitive() || !src.get(srcKey).getAsJsonPrimitive().isNumber()) {
				return;
			}
			dst.add(dstKey, src.get(srcKey).deepCopy());
		} catch (Exception ignore) {
		}
	}
	
	
	public static JsonArray toolCallsFromFunctionCall(JsonObject functionCall, String id) {
		if (functionCall == null) {
			return null;
		}
		String name = JsonUtil.getJsonString(functionCall, "name", null);
		if (name == null || name.isBlank()) {
			return null;
		}
		if (id == null || id.isBlank()) {
			id = "call_" + UUID.randomUUID().toString().replace("-", "");
		}
		JsonElement argsEl = functionCall.get("arguments");
		String args = argsEl == null || argsEl.isJsonNull() ? "" : JsonUtil.jsonValueToString(argsEl);

		JsonObject fn = new JsonObject();
		fn.addProperty("name", name);
		fn.addProperty("arguments", args);

		JsonObject tc = new JsonObject();
		tc.addProperty("id", id);
		tc.addProperty("type", "function");
		tc.add("function", fn);

		JsonArray arr = new JsonArray();
		arr.add(tc);
		return arr;
	}

	private static JsonElement tryParseJson(String s) {
		try {
			if (s == null || s.isBlank()) {
				return null;
			}
			return JsonUtil.fromJson(s, JsonElement.class);
		} catch (Exception e) {
			return null;
		}
	}

	private static Double safeDouble(JsonObject obj, String key) {
		if (obj == null || key == null || key.isBlank() || !obj.has(key) || obj.get(key) == null || obj.get(key).isJsonNull()) {
			return null;
		}
		try {
			JsonElement el = obj.get(key);
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
				return el.getAsDouble();
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
				String s = el.getAsString();
				if (s == null || s.isBlank()) {
					return null;
				}
				return Double.parseDouble(s.trim());
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private static long msToNs(Double ms) {
		if (ms == null) {
			return 0L;
		}
		double v = ms.doubleValue();
		if (!Double.isFinite(v) || v <= 0d) {
			return 0L;
		}
		return Math.max(0L, Math.round(v * 1_000_000d));
	}

	private static boolean ensureToolCallIdsInArray(JsonArray arr, Map<Integer, String> indexToId) {
		if (arr == null) {
			return false;
		}
		boolean changed = false;
		for (int i = 0; i < arr.size(); i++) {
			JsonElement el = arr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			JsonObject tc = el.getAsJsonObject();
			Integer idx = readToolCallIndex(tc, i);
			String existingId = JsonUtil.getJsonString(tc, "id", null);
			if (existingId == null || existingId.isBlank()) {
				String assigned = (indexToId == null || idx == null) ? null : indexToId.get(idx);
				if (assigned == null || assigned.isBlank()) {
					assigned = "call_" + UUID.randomUUID().toString().replace("-", "");
					if (indexToId != null && idx != null) {
						indexToId.put(idx, assigned);
					}
				}
				tc.addProperty("id", assigned);
				changed = true;
			} else if (indexToId != null && idx != null) {
				indexToId.putIfAbsent(idx, existingId);
			}
		}
		return changed;
	}

	private static Integer readToolCallIndex(JsonObject tc, int fallback) {
		if (tc == null) {
			return fallback;
		}
		JsonElement idxEl = tc.get("index");
		if (idxEl == null || idxEl.isJsonNull()) {
			return fallback;
		}
		try {
			if (idxEl.isJsonPrimitive() && idxEl.getAsJsonPrimitive().isNumber()) {
				return idxEl.getAsInt();
			}
			if (idxEl.isJsonPrimitive() && idxEl.getAsJsonPrimitive().isString()) {
				String s = idxEl.getAsString();
				if (s != null && !s.isBlank()) {
					return Integer.parseInt(s.trim());
				}
			}
		} catch (Exception ignore) {
		}
		return fallback;
	}

	private static String ggmlTypeName(int id) {
		return switch (id) {
		case 0 -> "F32";
		case 1 -> "F16";
		case 2 -> "Q4_0";
		case 3 -> "Q4_1";
		case 4 -> "Q4_2";
		case 5 -> "Q4_3";
		case 6 -> "Q5_0";
		case 7 -> "Q5_1";
		case 8 -> "Q8_0";
		case 9 -> "Q8_1";
		case 10 -> "Q2_K";
		case 11 -> "Q3_K";
		case 12 -> "Q4_K";
		case 13 -> "Q5_K";
		case 14 -> "Q6_K";
		case 15 -> "Q8_K";
		case 16 -> "IQ2_XXS";
		case 17 -> "IQ2_XS";
		case 18 -> "IQ3_XXS";
		case 19 -> "IQ1_S";
		case 20 -> "IQ4_NL";
		case 21 -> "IQ3_S";
		case 22 -> "IQ2_S";
		case 23 -> "IQ4_XS";
		case 24 -> "I8";
		case 25 -> "I16";
		case 26 -> "I32";
		case 27 -> "I64";
		case 28 -> "F64";
		case 29 -> "IQ1_M";
		case 30 -> "BF16";
		case 105 -> "ROCmFP4";
		default -> "UNKNOWN(" + id + ")";
		};
	}

	private static int readI32Le(RandomAccessFile raf) throws IOException {
		return Integer.reverseBytes(raf.readInt());
	}

	private static long readU64Le(RandomAccessFile raf) throws IOException {
		return Long.reverseBytes(raf.readLong());
	}

	private static String readGgufString(RandomAccessFile raf) throws IOException {
		long len = readU64Le(raf);
		if (len <= 0) {
			return "";
		}
		if (len > Integer.MAX_VALUE) {
			skipFully(raf, len);
			return "";
		}
		byte[] bytes = new byte[(int) len];
		raf.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void skipGgufValue(RandomAccessFile raf, int type) throws IOException {
		switch (type) {
		case 0, 1, 7 -> skipFully(raf, 1);
		case 2, 3 -> skipFully(raf, 2);
		case 4, 5, 6 -> skipFully(raf, 4);
		case 8 -> {
			long len = readU64Le(raf);
			if (len > 0) {
				skipFully(raf, len);
			}
		}
		case 9 -> {
			int elemType = readI32Le(raf);
			long len = readU64Le(raf);
			for (long i = 0; i < len; i++) {
				skipGgufValue(raf, elemType);
			}
		}
		case 10, 11, 12 -> skipFully(raf, 8);
		default -> {
		}
		}
	}

	private static void skipFully(RandomAccessFile raf, long n) throws IOException {
		if (n <= 0) {
			return;
		}
		long pos = raf.getFilePointer();
		raf.seek(pos + n);
	}
	
	
	public static Instant resolveModifiedAt(GGUFModel model) {
		if (model == null) {
			return Instant.now();
		}
		GGUFMetaData primary = model.getPrimaryModel();
		if (primary != null) {
			Instant lm = safeModifiedAt(primary.getFilePath());
			if (lm != null) {
				return lm;
			}
		}
		Instant lm = safeModifiedAt(model.getPath());
		return lm == null ? Instant.now() : lm;
	}
	
	public static Instant safeModifiedAt(String path) {
		if (path == null || path.isBlank()) {
			return null;
		}
		try {
			File f = new File(path);
			if (!f.exists() || !f.isFile()) {
				return null;
			}
			long lm = f.lastModified();
			return lm > 0 ? Instant.ofEpochMilli(lm) : null;
		} catch (Exception ignore) {
			return null;
		}
	}

	/**
	 * 	
	 * @param map
	 * @param key
	 * @return
	 */
	public static long readLong(Map<String, Object> map, String key) {
		if (map == null || key == null || key.isBlank()) {
			return 0L;
		}
		Object v = map.get(key);
		if (v == null) {
			return 0L;
		}
		if (v instanceof Number) {
			return ((Number) v).longValue();
		}
		try {
			return Long.parseLong(String.valueOf(v).trim());
		} catch (Exception ignore) {
			return 0L;
		}
	}
}
