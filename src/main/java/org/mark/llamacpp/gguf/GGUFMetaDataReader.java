package org.mark.llamacpp.gguf;

public class GGUFMetaDataReader {

    /**
     * MTP (Multi-Token Prediction) layer detection result.
     */
    public record MtpInfo(
        boolean hasMtp,
        String architecture,
        int blockCount,
        int nextnPredictLayers,
        int trunkCount,
        java.util.List<String> mtpBlockPrefixes
    ) {
        public static MtpInfo noMtp() {
            return new MtpInfo(false, null, 0, 0, 0, java.util.Collections.emptyList());
        }
    }

    /**
     * Detect MTP layers in a GGUF file by reading KV metadata.
     * <p>
     * MTP exists when {@code {arch}.nextn_predict_layers > 0}. The MTP blocks
     * occupy indices {@code [block_count - nextn_predict_layers, block_count)}.
     */
    public static MtpInfo extractMtpInfo(java.io.File file) {
        java.util.Map<String, Object> meta = read(file);
        if (meta.isEmpty()) return MtpInfo.noMtp();

        String arch = (String) meta.get("general.architecture");
        if (arch == null) return MtpInfo.noMtp();

        Number blockCount = (Number) meta.get(arch + ".block_count");
        Number nextn = (Number) meta.get(arch + ".nextn_predict_layers");
        if (nextn == null || nextn.intValue() <= 0) return MtpInfo.noMtp();
        if (blockCount == null) return MtpInfo.noMtp();

        int bc = blockCount.intValue();
        int nn = nextn.intValue();
        int trunk = bc - nn;

        java.util.List<String> prefixes = new java.util.ArrayList<>(nn);
        for (int i = 0; i < nn; i++) {
            prefixes.add("blk." + (trunk + i) + ".");
        }
        return new MtpInfo(true, arch, bc, nn, trunk, prefixes);
    }

    /**
     * Extract MTP tensors from a GGUF file into a lightweight donor GGUF file.
     * <p>
     * The donor contains only MTP tensors plus essential KV metadata
     * (architecture, block_count, nextn_predict_layers, alignment, name, description).
     */
    public static void extractMtpDonor(java.io.File source, java.io.File output) throws java.io.IOException {
        MtpInfo mtpInfo = extractMtpInfo(source);
        if (!mtpInfo.hasMtp()) {
            throw new IllegalArgumentException("No MTP layers found in " + source);
        }

        java.util.Map<String, Object> meta = read(source);
        int alignment = meta.containsKey("general.alignment")
            ? ((Number) meta.get("general.alignment")).intValue()
            : 32;
        String arch = mtpInfo.architecture();

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(source, "r");
             java.nio.channels.FileChannel channel = raf.getChannel();
             java.io.RandomAccessFile outRaf = new java.io.RandomAccessFile(output, "rw")) {

            long fileSize = channel.size();
            int bufSize = (int) Math.min(fileSize, 64L * 1024 * 1024);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(bufSize);
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int totalRead = 0;
            while (totalRead < bufSize) {
                int n = channel.read(buffer);
                if (n == -1) break;
                totalRead += n;
            }
            buffer.flip();

            byte[] magic = new byte[4];
            buffer.get(magic);
            int version = buffer.getInt();
            long tensorCount = buffer.getLong();
            long kvCount = buffer.getLong();

            for (long i = 0; i < kvCount; i++) {
                readString(buffer);
                skipValue(buffer, buffer.getInt());
            }

            java.util.List<TensorInfo> allTensors = new java.util.ArrayList<>((int) tensorCount);
            for (long i = 0; i < tensorCount; i++) {
                String tname = readString(buffer);
                int nDims = buffer.getInt();
                java.util.List<Long> shape = new java.util.ArrayList<>(nDims);
                for (int j = 0; j < nDims; j++) shape.add(buffer.getLong());
                int ttype = buffer.getInt();
                long off = buffer.getLong();  // offset relative to data section start
                allTensors.add(new TensorInfo(tname, shape, ttype, off, 0));
            }

            // GGUF stores tensor offsets relative to the data section start.
            // The data section begins after tensor infos + padding to alignment.
            long posAfterTi = buffer.position();
            long padToAlign = (alignment - (posAfterTi % alignment)) % alignment;
            long dataSectionStart = posAfterTi + padToAlign;

            // Convert to absolute offsets and calculate on-disk sizes
            for (int i = 0; i < allTensors.size(); i++) {
                TensorInfo t = allTensors.get(i);
                long absOff = dataSectionStart + t.dataOffset;
                long sz = (i < allTensors.size() - 1)
                    ? (dataSectionStart + allTensors.get(i + 1).dataOffset) - absOff
                    : fileSize - absOff;
                allTensors.set(i, new TensorInfo(t.name, t.shape, t.tensorType, absOff, sz));
            }

            java.util.List<String> mtpPrefixes = mtpInfo.mtpBlockPrefixes();
            java.util.List<TensorInfo> mtpTensors = new java.util.ArrayList<>();
            for (TensorInfo t : allTensors) {
                for (String prefix : mtpPrefixes) {
                    if (t.name.startsWith(prefix)) { mtpTensors.add(t); break; }
                }
            }
            if (mtpTensors.isEmpty()) {
                throw new IllegalArgumentException(
                    "No MTP tensors (nextn_predict_layers=" + mtpInfo.nextnPredictLayers() + ")");
            }

            // Write header
            outRaf.write("GGUF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            writeLE(outRaf, 3);
            writeLE(outRaf, (long) mtpTensors.size());

            // Restore donor KVs from serialized JSON, or fall back to standard KVs
            String donorKvJson = (String) meta.get("general.mtp_donor_kv_json");
            if (donorKvJson != null) {
                com.google.gson.JsonObject donorKvData =
                    com.google.gson.JsonParser.parseString(donorKvJson).getAsJsonObject();
                writeLE(outRaf, (long) donorKvData.size());
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : donorKvData.entrySet()) {
                    writeJsonBackedKV(outRaf, e.getKey(), e.getValue().getAsJsonObject());
                }
            } else {
                java.util.List<String> kvKeys = new java.util.ArrayList<>();
                kvKeys.add("general.architecture");
                kvKeys.add("general.name");
                kvKeys.add("general.description");
                kvKeys.add("general.alignment");
                kvKeys.add(arch + ".block_count");
                kvKeys.add(arch + ".nextn_predict_layers");
                kvKeys.removeIf(k -> !meta.containsKey(k));

                writeLE(outRaf, (long) kvKeys.size());
                for (String key : kvKeys) {
                    Object value = meta.get(key);
                    if (value instanceof String s) {
                        writeStringKV(outRaf, key, s);
                    } else {
                        writeUint32KV(outRaf, key, ((Number) value).intValue());
                    }
                }
            }

            long currentDataOffset = 0;
            long[] dataOffsets = new long[mtpTensors.size()];
            for (int i = 0; i < mtpTensors.size(); i++) {
                dataOffsets[i] = currentDataOffset;
                currentDataOffset += mtpTensors.get(i).dataSize;
            }
            for (int i = 0; i < mtpTensors.size(); i++) {
                TensorInfo t = mtpTensors.get(i);
                byte[] nb = t.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writeLE(outRaf, (long) nb.length);
                outRaf.write(nb);
                writeLE(outRaf, t.shape.size());
                for (long dim : t.shape) writeLE(outRaf, dim);
                writeLE(outRaf, t.tensorType);
                writeLE(outRaf, dataOffsets[i]);
            }

            long pos = outRaf.getFilePointer();
            long padding = (alignment - (pos % alignment)) % alignment;
            for (int i = 0; i < padding; i++) outRaf.write(0);

            java.nio.ByteBuffer chunk = java.nio.ByteBuffer.allocate(8192);
            for (TensorInfo t : mtpTensors) {
                channel.position(t.dataOffset);
                long remaining = t.dataSize;
                while (remaining > 0) {
                    int toRead = (int) Math.min(remaining, 8192);
                    chunk.clear();
                    chunk.limit(toRead);
                    int bytesRead = channel.read(chunk);
                    if (bytesRead == -1) throw new java.io.IOException("Unexpected EOF");
                    chunk.flip();
                    while (chunk.hasRemaining()) outRaf.getChannel().write(chunk);
                    remaining -= bytesRead;
                }
            }
        }
    }

    public static java.util.Map<String, Object> read(java.io.File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return java.util.Collections.emptyMap();
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
             java.nio.channels.FileChannel channel = raf.getChannel()) {
            long size = channel.size();
            int bufSize = (int) Math.min(size, 64L * 1024 * 1024);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(bufSize);
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int totalRead = 0;
            while (totalRead < bufSize) {
                int n = channel.read(buffer);
                if (n == -1) break;
                totalRead += n;
            }
            buffer.flip();
            byte[] magic = new byte[4];
            buffer.get(magic);
            String m = new String(magic, java.nio.charset.StandardCharsets.US_ASCII);
            if (!"GGUF".equals(m)) {
                return java.util.Collections.emptyMap();
            }
            buffer.getInt();
            buffer.getLong();
            long kvCount = buffer.getLong();
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            for (long i = 0; i < kvCount; i++) {
                String key = readString(buffer);
                int type = buffer.getInt();
                if ("tokenizer.ggml.tokens".equals(key) && type == 9) {
                    int elemType = buffer.getInt();
                    long len = buffer.getLong();
                    for (long j = 0; j < len; j++) {
                        skipValue(buffer, elemType);
                    }
                    metadata.put(key + ".size", len);
                } else {
                    Object value = readValue(buffer, type);
                    metadata.put(key, value);
                }
            }
            metadata.put("file.name", file.getName());
            metadata.put("file.path", file.getAbsolutePath());
            return metadata;
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }

    private static String readString(java.nio.ByteBuffer buffer) {
        long len = buffer.getLong();
        byte[] bytes = new byte[(int) len];
        buffer.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Object readValue(java.nio.ByteBuffer buffer, int type) {
        switch (type) {
            case 0:
                return buffer.get() & 0xFF;
            case 1:
                return buffer.get();
            case 2:
                return buffer.getShort() & 0xFFFF;
            case 3:
                return buffer.getShort();
            case 4:
                return buffer.getInt() & 0xFFFFFFFFL;
            case 5:
                return buffer.getInt();
            case 6:
                return buffer.getFloat();
            case 7:
                return buffer.get() != 0;
            case 8:
                return readString(buffer);
            case 9:
                return readArray(buffer);
            case 10:
                return buffer.getLong();
            case 11:
                return buffer.getLong();
            case 12:
                return buffer.getDouble();
            default:
                throw new IllegalArgumentException("Unknown GGUF value type: " + type);
        }
    }

    private static java.util.List<Object> readArray(java.nio.ByteBuffer buffer) {
        int t = buffer.getInt();
        long len = buffer.getLong();
        java.util.List<Object> list = new java.util.ArrayList<>((int) len);
        for (int i = 0; i < len; i++) {
            list.add(readValue(buffer, t));
        }
        return list;
    }

    private static void skipValue(java.nio.ByteBuffer buffer, int type) {
        switch (type) {
            case 0:
                buffer.get();
                return;
            case 1:
                buffer.get();
                return;
            case 2:
                buffer.getShort();
                return;
            case 3:
                buffer.getShort();
                return;
            case 4:
                buffer.getInt();
                return;
            case 5:
                buffer.getInt();
                return;
            case 6:
                buffer.getFloat();
                return;
            case 7:
                buffer.get();
                return;
            case 8: {
                long len = buffer.getLong();
                int n = (int) len;
                buffer.position(buffer.position() + n);
                return;
            }
            case 9: {
                int t = buffer.getInt();
                long len = buffer.getLong();
                for (long i = 0; i < len; i++) {
                    skipValue(buffer, t);
                }
                return;
            }
            case 10:
                buffer.getLong();
                return;
            case 11:
                buffer.getLong();
                return;
            case 12:
                buffer.getDouble();
                return;
            default:
                return;
        }
    }

    private record TensorInfo(String name, java.util.List<Long> shape, int tensorType, long dataOffset, long dataSize) {}

    private static void writeLE(java.io.RandomAccessFile raf, int v) throws java.io.IOException {
        raf.writeByte(v & 0xFF);
        raf.writeByte((v >> 8) & 0xFF);
        raf.writeByte((v >> 16) & 0xFF);
        raf.writeByte((v >> 24) & 0xFF);
    }

    private static void writeLE(java.io.RandomAccessFile raf, long v) throws java.io.IOException {
        raf.writeByte((int) (v & 0xFF));
        raf.writeByte((int) ((v >> 8) & 0xFF));
        raf.writeByte((int) ((v >> 16) & 0xFF));
        raf.writeByte((int) ((v >> 24) & 0xFF));
        raf.writeByte((int) ((v >> 32) & 0xFF));
        raf.writeByte((int) ((v >> 40) & 0xFF));
        raf.writeByte((int) ((v >> 48) & 0xFF));
        raf.writeByte((int) ((v >> 56) & 0xFF));
    }

    private static void writeStringKV(java.io.RandomAccessFile raf, String key, String value) throws java.io.IOException {
        byte[] kb = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeLE(raf, (long) kb.length);
        raf.write(kb);
        writeLE(raf, 8); // STRING
        byte[] vb = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeLE(raf, (long) vb.length);
        raf.write(vb);
    }

    private static void writeUint32KV(java.io.RandomAccessFile raf, String key, int value) throws java.io.IOException {
        byte[] kb = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeLE(raf, (long) kb.length);
        raf.write(kb);
        writeLE(raf, 4); // UINT32
        writeLE(raf, value);
    }

    private static void writeJsonBackedKV(
        java.io.RandomAccessFile raf,
        String key,
        com.google.gson.JsonObject kv
    ) throws java.io.IOException {
        com.google.gson.JsonArray types = kv.getAsJsonArray("types");
        int type = types.get(0).getAsInt();
        int subType = types.size() > 1 ? types.get(1).getAsInt() : -1;
        byte[] kb = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeLE(raf, (long) kb.length);
        raf.write(kb);
        writeLE(raf, type);
        writeJsonValue(raf, type, kv.get("value"), subType);
    }

    private static void writeKV(java.io.RandomAccessFile raf, String key, int type, Object value, int subType) throws java.io.IOException {
        byte[] kb = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeLE(raf, (long) kb.length);
        raf.write(kb);
        writeLE(raf, type);
        writeValue(raf, type, value, subType);
    }

    private static void writeJsonValue(
        java.io.RandomAccessFile raf,
        int type,
        com.google.gson.JsonElement value,
        int subType
    ) throws java.io.IOException {
        switch (type) {
            case 0: // UINT8
            case 1: // INT8
                raf.write(jsonAsInt(value));
                break;
            case 2: // UINT16
            case 3: { // INT16
                int v = jsonAsInt(value);
                raf.writeByte(v & 0xFF);
                raf.writeByte((v >> 8) & 0xFF);
                break;
            }
            case 4: // UINT32
            case 5: // INT32
                writeLE(raf, jsonAsInt(value));
                break;
            case 6: // FLOAT32
                writeLE(raf, Float.floatToIntBits(value.getAsFloat()));
                break;
            case 7: // BOOL
                raf.write(jsonAsBoolean(value) ? 1 : 0);
                break;
            case 8: { // STRING
                String s = value.getAsString();
                byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writeLE(raf, (long) bytes.length);
                raf.write(bytes);
                break;
            }
            case 9: // ARRAY
                writeJsonArrayValue(raf, subType, value.getAsJsonArray());
                break;
            case 10: // UINT64
            case 11: // INT64
                writeLE(raf, jsonAsLong(value));
                break;
            case 12: // FLOAT64
                writeLE(raf, Double.doubleToLongBits(value.getAsDouble()));
                break;
            default:
                throw new IllegalArgumentException("Unknown GGUF value type: " + type);
        }
    }

    private static void writeJsonArrayValue(
        java.io.RandomAccessFile raf,
        int subType,
        com.google.gson.JsonArray list
    ) throws java.io.IOException {
        writeLE(raf, subType);
        writeLE(raf, (long) list.size());
        for (com.google.gson.JsonElement elem : list) {
            writeJsonValue(raf, subType, elem, -1);
        }
    }

    private static int jsonAsInt(com.google.gson.JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean() ? 1 : 0;
        }
        return value.getAsInt();
    }

    private static long jsonAsLong(com.google.gson.JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean() ? 1L : 0L;
        }
        return value.getAsLong();
    }

    private static boolean jsonAsBoolean(com.google.gson.JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        return value.getAsInt() != 0;
    }

    private static void writeValue(java.io.RandomAccessFile raf, int type, Object value, int subType) throws java.io.IOException {
        switch (type) {
            case 0: case 1: // UINT8, INT8
                raf.write(((Number) value).byteValue());
                break;
            case 2: case 3: { // UINT16, INT16
                int v = ((Number) value).intValue();
                raf.writeByte(v & 0xFF);
                raf.writeByte((v >> 8) & 0xFF);
                break;
            }
            case 4: case 5: // UINT32, INT32
                writeLE(raf, ((Number) value).intValue());
                break;
            case 6: // FLOAT32
                writeLE(raf, Float.floatToIntBits(((Number) value).floatValue()));
                break;
            case 7: // BOOL
                raf.write(value instanceof Boolean ? (((Boolean) value) ? 1 : 0) : ((Number) value).byteValue());
                break;
            case 8: { // STRING
                String s = (String) value;
                byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writeLE(raf, (long) bytes.length);
                raf.write(bytes);
                break;
            }
            case 9: // ARRAY
                writeArrayValue(raf, subType, (java.util.List<?>) value);
                break;
            case 10: case 11: // UINT64, INT64
                writeLE(raf, ((Number) value).longValue());
                break;
            case 12: // FLOAT64
                writeLE(raf, Double.doubleToLongBits(((Number) value).doubleValue()));
                break;
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeArrayValue(java.io.RandomAccessFile raf, int subType, java.util.List<?> list) throws java.io.IOException {
        writeLE(raf, subType);
        writeLE(raf, (long) list.size());
        for (Object elem : list) {
            writeValue(raf, subType, elem, -1);
        }
    }
}
