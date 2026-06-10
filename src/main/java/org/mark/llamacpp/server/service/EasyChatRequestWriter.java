package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonObject;

final class EasyChatRequestWriter {

	private static final byte[] REQUEST_PREFIX = "{\"model\":\"".getBytes(StandardCharsets.UTF_8);
	private static final byte[] REQUEST_MIDDLE = "\",\"stream\":true,\"timings_per_token\":true,\"return_progress\":true,\"messages\":[".getBytes(StandardCharsets.UTF_8);
	private static final byte[] ARRAY_END = "]".getBytes(StandardCharsets.UTF_8);
	private static final byte[] OBJECT_END = "}".getBytes(StandardCharsets.UTF_8);
	private static final byte[] COMMA = ",".getBytes(StandardCharsets.UTF_8);
	private static final byte[] QUOTE_COLON = "\":".getBytes(StandardCharsets.UTF_8);

	private final EasyChatStorage storage;

	EasyChatRequestWriter(EasyChatStorage storage) {
		this.storage = storage;
	}

	void writeRequestBody(OutputStream output, RequestSpec spec) throws IOException {
		writeAscii(output, REQUEST_PREFIX);
		writeString(output, spec.modelId);
		writeAscii(output, REQUEST_MIDDLE);

		boolean wroteAnyMessage = false;
		if (spec.systemPrompt != null && !spec.systemPrompt.isBlank()) {
			JsonObject systemMessage = new JsonObject();
			systemMessage.addProperty("role", "system");
			systemMessage.addProperty("content", spec.systemPrompt);
			writeString(output, JsonUtil.toJson(systemMessage));
			wroteAnyMessage = true;
		}

		long seq = 0;
		while (true) {
			EasyChatStorage.FragmentHeader header = storage.readFragmentHeader(spec.conversationDir, seq);
			if (header == null) {
				break;
			}
			if (spec.regenerateSeq != null && seq == spec.regenerateSeq.longValue()) {
				break;
			}
			if (storage.isDeleted(header)) {
				seq++;
				continue;
			}
			Integer preferredVariant = spec.variants == null ? null : spec.variants.get(seq);
			int resolvedVariant = storage.resolveVariantIndex(header, preferredVariant);
			if (resolvedVariant < 0) {
				seq++;
				continue;
			}
			if (wroteAnyMessage) {
				writeAscii(output, COMMA);
			}
			storage.streamVariant(spec.conversationDir, seq, resolvedVariant, output);
			wroteAnyMessage = true;
			seq++;
		}

		writeAscii(output, ARRAY_END);
		writeExtraFields(output, spec);
		writeAscii(output, OBJECT_END);
		output.flush();
	}

	private void writeExtraFields(OutputStream output, RequestSpec spec) throws IOException {
		if (spec.toolsBytes != null && spec.toolsBytes.length > 0) {
			JsonObject toolsObj = JsonUtil.tryParseObject(new String(spec.toolsBytes, StandardCharsets.UTF_8));
			if (toolsObj != null) {
				writeObjectFields(output, toolsObj);
			}
		}

		JsonObject requestOptions = new JsonObject();
		requestOptions.addProperty("model", spec.modelId);
		ModelSamplingService.getInstance().handleOpenAI(requestOptions);
		if (spec.samplingParams != null) {
			for (String key : spec.samplingParams.keySet()) {
				requestOptions.add(key, spec.samplingParams.get(key));
			}
		}
		writeObjectFields(output, requestOptions, "model", "messages", "stream");
	}

	private void writeObjectFields(OutputStream output, JsonObject obj, String... ignoredKeys) throws IOException {
		if (obj == null) {
			return;
		}
		for (String key : obj.keySet()) {
			if (shouldIgnore(key, ignoredKeys)) {
				continue;
			}
			writeAscii(output, COMMA);
			writeAscii(output, "\"".getBytes(StandardCharsets.UTF_8));
			writeString(output, key);
			writeAscii(output, QUOTE_COLON);
			writeString(output, JsonUtil.toJson(obj.get(key)));
		}
	}

	private boolean shouldIgnore(String key, String... ignoredKeys) {
		if (ignoredKeys == null) {
			return false;
		}
		for (String ignored : ignoredKeys) {
			if (ignored != null && ignored.equals(key)) {
				return true;
			}
		}
		return false;
	}

	private void writeString(OutputStream output, String text) throws IOException {
		output.write(text.getBytes(StandardCharsets.UTF_8));
	}

	private void writeAscii(OutputStream output, byte[] bytes) throws IOException {
		output.write(bytes);
	}

	static final class RequestSpec {
		final String modelId;
		final String systemPrompt;
		final Path conversationDir;
		final byte[] toolsBytes;
		final JsonObject samplingParams;
		final Map<Long, Integer> variants;
		final Long regenerateSeq;

		RequestSpec(String modelId, String systemPrompt, Path conversationDir, byte[] toolsBytes,
			JsonObject samplingParams, Map<Long, Integer> variants, Long regenerateSeq) {
			this.modelId = modelId;
			this.systemPrompt = systemPrompt;
			this.conversationDir = conversationDir;
			this.toolsBytes = toolsBytes;
			this.samplingParams = samplingParams;
			this.variants = variants;
			this.regenerateSeq = regenerateSeq;
		}
	}
}
