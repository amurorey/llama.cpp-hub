# BUG：GET /api/chat/stream-chat 偶发 JSON 响应损坏

## 现象

访问 `GET /api/chat/stream-chat?conversationId=xxx` 时，偶发返回乱码 JSON。`totalSize`、`recordCount` 等统计字段正确，但 `data` 数组内的消息内容被二进制字节污染（如出现 `1`、`2`、`3a`、`b`、`0` 等乱码字符），导致前端 JSON.parse 失败。

示例损坏输出：

```json
{
    "message": "success",
    "totalSize": 6547,
    "recordCount": 2,
    "variantCount": 0,
    "data": [
        {
            "seq": 0,
            "role": "user",
            "activeVariant": 0,
            "variants": [
                {
                    "content": 1
                }
2
            ]
        }
1,
        3a
{
            "seq": 1,
            "role": "assistant",
            "activeVariant": 0,
            "variants": [
b
{
                    "content": 1
                }
2
            ]
        }
2
    ]
}
0
```

## 影响范围

- 仅 `GET /api/chat/stream-chat`（获取对话历史）受影响
- `POST /api/chat/stream-chat`（发送消息/SSE 流）不受影响
- 仅在`读（GET）`与`写（POST）`对同一对话并发执行时偶发
- 将损坏数据直接返回到前端，可能导致 UI 异常或白屏

## 相关文件

- `src/main/java/org/mark/llamacpp/server/service/EasyChatService.java`
  - `handleStreamChat()` (line ~121)：POST 写端
  - `handleStreamChatHistory()` (line ~496)：GET 读端
- `src/main/java/org/mark/llamacpp/server/service/EasyChatStorage.java`
  - `appendVariant()` (line ~148)：追加 variant（非原子操作）
  - `writeFragment()` (line ~136)：写新 fragment（原子操作，temp + move）
  - `readFragmentHeader()` (line ~315)：读 fragment 头部
  - `getVariantSlice()` (line ~253)：获取 variant 在文件中的 offset+length

## 根本原因

### 1. 按对话粒度锁不统一

系统有两把**全局锁**和一个**按对话粒度锁**：

| 方法 | 全局锁 | 按对话粒度锁 |
|---|---|---|
| `handleStreamChat()` (POST) | `chat.stream` | ✅ `synchronized(convLock)` (line 293/406/446) |
| `handleStreamChatHistory()` (GET) | `chat.history` | ❌ **不使用** |
| `deleteMessage()` | 不相关 | ✅ `synchronized(convLock)` (line 1190) |

全局锁 `chat.stream` 和 `chat.history` 是**不同的锁**，彼此不互斥。因此 GET 和 POST 可以对同一对话**完全并发执行**。

### 2. 非原子的写操作

`appendVariant()` 在文件上就地修改：

```java
void appendVariant(Path dir, long seq, byte[] payload) {
    // 1. 写入变体计数（2 字节）
    writeShort(raf, FRAG_COUNT_OFFSET, header.variantCount + 1);
    // 2. 写入新变体的长度（4 字节）
    writeInt(raf, FRAG_LENGTHS_OFFSET + header.variantCount * 4, payload.length);
    // 3. 写入活跃变体索引（2 字节）
    writeShort(raf, FRAG_ACTIVE_VARIANT_OFFSET, header.variantCount);
    // 4. 定位到文件末尾写入 payload
    raf.seek(payloadEndOffset(header));
    raf.write(payload);
}
```

这 4 步不是原子的。如果 GET 在步骤 1~3 之后、步骤 4 之前读取了文件，会看到一个 variantCount=2 但 payload 尚未写入的不一致状态。

### 3. 零拷贝（DefaultFileRegion）加剧了竞态

`handleStreamChatHistory()` 使用 `DefaultFileRegion` 进行零拷贝文件传输：

```java
EasyChatStorage.FragmentSlice slice = storage.getVariantSlice(convDir, seq, v);
ctx.writeAndFlush(new DefaultFileRegion(slice.file.toFile(), slice.offset, slice.length));
```

`DefaultFileRegion` 的**实际文件读取发生在 EventLoop 线程上**，远在 `writeAndFlush` 返回之后。此时：

- 按对话粒度锁早已释放（实际上压根没持有）
- 文件可能在读取过程中被 POST 端的 `appendVariant()` 修改
- 读取到的字节与 header 中描述的 offset/length 不匹配 → 发送错误的数据

### 4. TOCTOU（Time-of-check to time-of-use）

Phase 1（预扫描）先读 header 统计数据（如 `totalSize`、`recordCount`），Phase 3（流式输出）再重新读 header 和 payload。两阶段之间文件可能已被修改，导致：

- Phase 1 读到的 `lengths[0]` 是旧值（如 3273）
- Phase 3 实际通过 `DefaultFileRegion` 发出去的字节数可能是另一个值（被并发修改后的值）
- 结果：JSON 结构错位，二进制 header 字节以文本形式暴露（如 `0x31`=`'1'`、`0x32`=`'2'` 等）

## 复现条件

高并发下对同一对话同时发起：
1. `POST /api/chat/stream-chat`（发送消息，触发 `appendVariant`）
2. `GET /api/chat/stream-chat?conversationId=xxx`（读取对话历史）

在 llama.cpp SSE 流式返回期间（可能长达数十秒），如果前端或调试工具同时刷新历史页面，几乎必然触发。

## 修复方向

### 核心策略

**按对话粒度锁**必须覆盖 GET 的读操作，且不能使用 `DefaultFileRegion`（零拷贝延迟执行导致锁无法保护文件内容）。

### 推荐方案：逐消息粒度锁 + 内存缓冲写

1. **Phase 1**（锁内）：仅扫描 header 计算 `totalSize`、`recordCount`、`variantCount`，不读 payload
2. **Phase 2**（无锁）：发送 HTTP response header 和 JSON 前缀
3. **Phase 3**（逐消息循环）：
   ```
   for 每条消息:
       synchronized(convLock):
           读取 header
           提取 role
           读取所有 variant payload
       释放锁
       写入 msgPrefix + variant content + msgSuffix
   ```

优点：
- 每时每刻只有**一条消息**的 payload 在内存中（通常几十 KB）
- 锁粒度适中（只阻塞同一对话的 POST，不影响其他对话）
- 非零拷贝写入（直接写 byte[]），避免异步文件读取竞态

### 注意事项

- `totalSize` 和 `recordCount` 在 Phase 1 和 Phase 3 之间可能有微小偏差（并发新增/删除消息），但对历史快照可接受
- `DefaultFileRegion` 必须替换为 `storage.readPayload()` + `Unpooled.wrappedBuffer()`
- 如果对话中某条消息的 variant payload 极大（如数 MB），仍会短时占用内存，但总量有限（一个消息的 payload）
