# Implementation Plan: AI流式思考过程显示 - 后端实现

## Overview

基于后端技术设计文档，按组件改造顺序实现 DeepSeek reasoning_content 解析、WebSocket 推送类型区分、思考过程持久化、并发安全、配置变更等后端改造。所有任务仅涉及后端 Java 代码，不包含前端改动。

## Tasks

- [x] 1. 数据模型改造
  - [x] 1.1 扩展 Message 实体新增 thinkingContent 字段
    - 在 `src/main/java/com/zyh/archivemind/entity/Message.java` 中新增 `thinkingContent` 字段
    - 由于使用 `@AllArgsConstructor`，需排查所有 `new Message(role, content)` 调用点并同步修改
    - _Requirements: 2.1, 2.5_

  - [x] 1.2 扩展 MessageDTO 新增 thinkingContent 字段并引入 Builder 模式
    - 在 `src/main/java/com/zyh/archivemind/dto/MessageDTO.java` 中新增 `thinkingContent` 字段
    - 添加 `@Builder` 注解，降低后续字段变更影响
    - 排查所有 `new MessageDTO(role, content, timestamp)` 调用点（如 `ConversationSessionServiceImpl.switchSession()`），改为 Builder 模式或新增字段的构造方式
    - _Requirements: 2.1, 2.5_

  - [x] 1.3 编写 Message/MessageDTO 数据模型单元测试
    - 验证 thinkingContent 字段的序列化/反序列化
    - 验证 null thinkingContent 时 JSON 输出不包含该字段（Jackson 默认行为）
    - _Requirements: 2.1_

- [x] 2. 配置变更 - AiProperties 新增 Thinking 配置
  - [x] 2.1 在 AiProperties 中新增 Thinking 内部类
    - 在 `src/main/java/com/zyh/archivemind/config/AiProperties.java` 中新增 `Thinking` 静态内部类
    - 包含 `enabled`（默认 true）和 `maxPersistLength`（默认 20000）两个字段
    - 在 `AiProperties` 中新增 `private Thinking thinking = new Thinking()` 成员
    - _Requirements: 1.4_

  - [x] 2.2 在 application.yml 中新增 ai.thinking 配置项
    - 新增 `ai.thinking.enabled: true` 和 `ai.thinking.max-persist-length: 20000`
    - _Requirements: 1.4_

- [x] 3. DeepSeekClient 改造 - 解析 reasoning_content
  - [x] 3.1 改造 streamResponse 方法签名为双回调模式
    - 将单个 `Consumer<String> onChunk` 拆分为 `Consumer<String> onThinkingChunk`、`Consumer<String> onAnswerChunk`、`Runnable onComplete`
    - 添加 `publishOn(Schedulers.boundedElastic())` 确保回调在弹性线程池执行
    - _Requirements: 1.1, 1.4, 1.5_

  - [x] 3.2 改造 processChunk 方法解析 reasoning_content 字段
    - 提取 `delta.reasoning_content` 字段调用 `onThinkingChunk`
    - 提取 `delta.content` 字段调用 `onAnswerChunk`
    - 处理 `[DONE]` 标记时调用 `onComplete`
    - 复用类级别 `ObjectMapper` 实例（替换现有每次 new 的方式），避免性能浪费
    - _Requirements: 1.2, 1.4, 1.5_

  - [x] 3.3 编写 DeepSeekClient.processChunk 属性测试
    - **Property 1: reasoning_content 和 content 路由正确性**
    - 对于任意非空 reasoning_content 和 content，processChunk 应分别调用 onThinkingChunk 和 onAnswerChunk
    - **Validates: Requirements 1.4**

  - [x] 3.4 编写 DeepSeekClient.processChunk 单元测试
    - 测试仅有 reasoning_content 的 chunk（思考阶段）
    - 测试仅有 content 的 chunk（回答阶段）
    - 测试 `[DONE]` 标记触发 onComplete
    - 测试无 reasoning_content 也无 content 的 chunk（空 delta）
    - 测试无效 JSON 的容错处理
    - _Requirements: 1.4, 5.2_

- [x] 4. Checkpoint - 确保 DeepSeekClient 改造编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. ChatHandler 改造 - WebSocket 推送与流程重构
  - [x] 5.1 新增 sendThinkingChunk 和 sendAnswerChunk 方法
    - 新增 `sendThinkingChunk(WebSocketSession, String)` 方法，推送 `{"type":"thinking","chunk":"..."}`
    - 新增 `sendAnswerChunk(WebSocketSession, String)` 方法，推送 `{"type":"answer","chunk":"..."}`
    - 在 `sendThinkingChunk` 中检查 `aiProperties.getThinking().isEnabled()` 开关
    - 两个方法均检查 `stopFlags` 停止标志
    - _Requirements: 1.4, 1.1_

  - [x] 5.2 新增 thinkingBuilders 和 session 关联 Map
    - 新增 `Map<String, StringBuilder> thinkingBuilders` 成员变量（ConcurrentHashMap）
    - 新增 `Map<String, String> sessionConversationIds` 和 `sessionUserIds`（ConcurrentHashMap），避免闭包变量作用域问题（S2 修复）
    - 新增 `Map<String, Long> sessionStartTimes`（ConcurrentHashMap），用于定时清理
    - 新增 `cleanupSession(String sessionId)` 方法统一清理所有 session 关联数据
    - _Requirements: 2.5, 5.3_

  - [x] 5.3 重构 processMessage 方法 - 替换后台线程轮询为 onComplete 回调
    - 调用改造后的 `deepSeekClient.streamResponse()` 双回调 + onComplete 模式
    - 在 `onThinkingChunk` 回调中累积思考内容到 `thinkingBuilders` 并调用 `sendThinkingChunk`
    - 在 `onAnswerChunk` 回调中累积回答内容到 `responseBuilders` 并调用 `sendAnswerChunk`
    - 在 `onComplete` 回调中：发送 completion 通知、调用 `updateConversationHistory`（含 thinkingContent）、刷新 TTL、调用 `cleanupSession`
    - 在 `onError` 回调中：调用 `handleError`、发送 completion 通知、调用 `cleanupSession`
    - 移除现有的 `new Thread(() -> { ... }).start()` 后台线程轮询逻辑
    - 移除 `CompletableFuture<String> responseFuture` 相关逻辑（不再需要）
    - _Requirements: 1.1, 1.4, 1.5, 2.5, 5.3_

  - [x] 5.4 编写 ChatHandler 推送格式属性测试
    - **Property 2: WebSocket 推送消息格式正确性**
    - 对于任意 thinking/answer chunk 内容，推送的 JSON 消息应包含正确的 type 和 chunk 字段
    - **Validates: Requirements 1.4**

  - [x] 5.5 编写 ChatHandler processMessage 单元测试
    - 验证 onThinkingChunk 回调正确累积并推送 thinking 类型消息
    - 验证 onAnswerChunk 回调正确累积并推送 answer 类型消息
    - 验证 onComplete 回调触发 completion 通知和历史持久化
    - 验证 stopFlags 生效时不推送消息
    - _Requirements: 1.4, 1.5, 2.5_

- [x] 6. Checkpoint - 确保 ChatHandler 改造编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. WebSocket 并发安全改造
  - [x] 7.1 在 ChatWebSocketHandler 中使用 ConcurrentWebSocketSessionDecorator
    - 在 `afterConnectionEstablished()` 中用 `ConcurrentWebSocketSessionDecorator` 包装 session
    - 参数：`sendTimeLimit=5000ms`, `bufferSizeLimit=65536 bytes`
    - _Requirements: 1.1, 5.3_

  - [x] 7.2 在 afterConnectionClosed 中增加 ChatHandler 清理调用
    - 调用 `chatHandler.cleanupSession(session.getId())` 清理可能残留的 builder 数据
    - 需要将 `cleanupSession` 方法改为 public 访问级别
    - _Requirements: 5.3_

- [x] 8. 思考过程持久化改造
  - [x] 8.1 改造 updateConversationHistory 支持 thinkingContent
    - 新增 `thinkingContent` 参数
    - 在 assistant 消息 Map 中添加 `thinkingContent` 字段（非空时）
    - 增加 `ai.thinking.max-persist-length` 截断逻辑，超长内容截断并追加 `\n\n[思考过程内容过长，已截断]`
    - _Requirements: 2.5, 5.3_

  - [x] 8.2 改造 ConversationSessionServiceImpl.switchSession 返回 thinkingContent
    - 在构建 `MessageDTO` 列表时，从 `Message` 实体中提取 `thinkingContent` 字段
    - 使用 Builder 模式或新构造方式创建 `MessageDTO`
    - _Requirements: 2.1, 2.5_

  - [x] 8.3 编写 updateConversationHistory 属性测试
    - **Property 3: thinkingContent 持久化 round-trip 一致性**
    - 对于任意 thinkingContent 字符串（长度在 maxPersistLength 内），写入 Redis 后读取应得到相同值
    - **Validates: Requirements 2.5**

  - [x] 8.4 编写 thinkingContent 截断逻辑单元测试
    - 验证超过 maxPersistLength 的内容被正确截断
    - 验证截断后追加了 `[思考过程内容过长，已截断]` 提示
    - 验证未超长的内容不被截断
    - _Requirements: 2.5_

- [x] 9. 内存泄漏防护 - 定时清理
  - [x] 9.1 新增 @Scheduled 定时清理 stale session 数据
    - 在 ChatHandler 中新增 `sessionStartTimes` Map 记录每个 session 的开始时间
    - 在 `processMessage` 中记录 `sessionStartTimes.put(sessionId, System.currentTimeMillis())`
    - 新增 `@Scheduled(fixedRate = 300000)` 的 `cleanupStaleBuilders()` 方法
    - 清理超过 10 分钟未完成的 session 数据
    - 确保 Spring Boot 主类或配置类上有 `@EnableScheduling` 注解
    - _Requirements: 5.3_

  - [x] 9.2 编写定时清理逻辑单元测试
    - 验证超时 session 数据被正确清理
    - 验证未超时 session 数据不被清理
    - _Requirements: 5.3_

- [x] 10. 更新现有测试适配新接口
  - [x] 10.1 更新 ChatHandlerTest 适配新的 streamResponse 签名
    - 更新所有 `verify(deepSeekClient).streamResponse(...)` 调用，匹配新的 6 参数签名
    - 更新 `doAnswer` mock 逻辑，使用 `onAnswerChunk`（参数索引 4）和 `onComplete`（参数索引 5）
    - 移除与后台线程 `Thread.sleep` 相关的等待逻辑（改为 onComplete 同步触发）
    - _Requirements: 1.4_

  - [x] 10.2 更新 ChatHandlerPropertyTest 适配新的 streamResponse 签名
    - 更新 `doAnswer` mock 逻辑，使用新的参数索引
    - 在 mock 中同时调用 `onAnswerChunk` 和 `onComplete`，模拟完整流程
    - 移除 `Thread.sleep(6500)` 等待逻辑
    - _Requirements: 1.4_

- [x] 11. Final checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 核心改动链路：DeepSeekClient（解析） → ChatHandler（推送+累积） → 持久化（Redis） → 会话切换（返回）
- 移除后台线程轮询是本次改造的重要改进，消除了轮询等待的不确定性和竞态条件风险
- `ConcurrentWebSocketSessionDecorator` 解决了 Reactor 回调多线程并发调用 `sendMessage` 的线程安全问题
- 配置项 `ai.thinking.enabled` 作为功能开关和应急预案，支持不重启服务关闭思考过程推送
