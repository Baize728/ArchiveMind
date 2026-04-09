# 实现计划：开启新对话（new-conversation）

## 概述

基于现有 Spring Boot + Redis 架构，为 ArchiveMind 系统新增多会话管理能力。实现路径：DTO 定义 → RedisRepository 扩展 → Service 层 → Controller 层 → ChatHandler 重构 → 数据迁移 → 集成测试。

## 任务

- [x] 1. 定义 DTO 和添加测试依赖
  - [x] 1.1 创建 DTO 类（SessionDTO、SessionDetailDTO、MessageDTO、GroupedSessionListDTO）
    - 在 `com.zyh.archivemind.dto` 包下创建四个 DTO 类
    - 使用 Lombok `@Data`、`@AllArgsConstructor`、`@NoArgsConstructor` 注解
    - _Requirements: 2.3, 3.2_
  - [x] 1.2 在 pom.xml 中添加 jqwik 测试依赖
    - 添加 `net.jqwik:jqwik:1.8.5` 依赖（scope=test）
    - _Requirements: 测试策略_

- [x] 2. 扩展 RedisRepository 支持多会话数据结构
  - [x] 2.1 实现会话列表管理方法（Sorted Set 操作）
    - 实现 `addSessionToUserList`、`getUserSessionIds`、`removeSessionFromUserList`
    - 使用 `user:{userId}:sessions` 键，以创建时间戳作为分数
    - _Requirements: 7.1_
  - [x] 2.2 实现会话元数据管理方法
    - 实现 `saveSessionMeta`、`getSessionMeta`、`deleteSessionMeta`
    - 使用 `session:{sessionId}:meta` 键，JSON 格式存储
    - _Requirements: 7.2_
  - [x] 2.3 实现活跃会话管理方法
    - 实现 `setActiveSession`、`getActiveSession`、`clearActiveSession`
    - 使用 `user:{userId}:active_session` 键
    - _Requirements: 1.3, 7.4_
  - [x] 2.4 实现 Lua 脚本原子操作（创建和删除）
    - 实现 `createSessionAtomic`：原子性执行 ZADD + SET meta + SET active_session + EXPIRE
    - 实现 `deleteSessionAtomic`：原子性执行 ZREM + DEL meta + DEL conversation + 条件清除 active_session
    - _Requirements: 1.2, 5.1_
  - [x] 2.5 实现 TTL 刷新方法
    - 实现 `refreshSessionKeys`：刷新会话相关所有键的过期时间为 30 天
    - _Requirements: 7.4, 7.5_
  - [x] 2.6 编写 RedisRepository 扩展方法的单元测试
    - 使用 Mockito mock RedisTemplate 验证各方法的调用逻辑
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [x] 3. 实现 ConversationSessionService
  - [x] 3.1 创建 ConversationSessionService 接口和实现类
    - 在 `com.zyh.archivemind.service` 包下创建接口和 `ConversationSessionServiceImpl`
    - 实现 `createSession`：生成 UUID、构建元数据 JSON、调用 `createSessionAtomic`、处理容量限制（200 上限）
    - _Requirements: 1.1, 1.2, 1.3_
  - [x] 3.2 实现会话列表查询和时间分组逻辑
    - 实现 `listSessions`：从 Sorted Set 获取所有会话ID，批量读取元数据，按时间分组
    - 分组规则：今天、7天内、30天内、更早（按年月分组）
    - _Requirements: 2.1, 2.2, 2.3, 2.4_
  - [x] 3.3 实现会话切换逻辑
    - 实现 `switchSession`：验证会话存在性和归属权、设置活跃会话、返回消息历史
    - _Requirements: 3.1, 3.2, 3.4_
  - [x] 3.4 实现会话标题管理
    - 实现 `autoGenerateTitle`：截取前 20 字符，超出追加 "..."
    - 实现 `updateTitle`：更新元数据中的标题字段
    - 新会话默认标题为"新对话"
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
  - [x] 3.5 实现会话删除逻辑
    - 实现 `deleteSession`：验证归属权、调用 `deleteSessionAtomic`、若为活跃会话则清除引用
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_
  - [x] 3.6 实现数据迁移逻辑（旧键兼容）
    - 在 `getActiveSessionId` 中实现迁移：检查新键 → 回退旧键 → Lua 脚本原子迁移 → 返回
    - _Requirements: 设计文档-数据迁移_
  - [x] 3.7 实现 TTL 刷新方法
    - 实现 `refreshSessionTTL`：调用 RedisRepository 刷新所有相关键
    - _Requirements: 7.4, 7.5_
  - [x] 3.8 编写属性测试：会话创建数据持久化往返
    - **Property 1: 会话创建数据持久化往返**
    - **Validates: Requirements 1.2, 7.1, 7.2, 7.3**
  - [x] 3.9 编写属性测试：创建会话后自动设置活跃会话
    - **Property 2: 创建会话后自动设置活跃会话**
    - **Validates: Requirements 1.3**
  - [x] 3.10 编写属性测试：自动标题生成截断规则
    - **Property 8: 自动标题生成截断规则**
    - **Validates: Requirements 4.1, 4.2**
  - [x] 3.11 编写属性测试：标题更新持久化往返
    - **Property 9: 标题更新持久化往返**
    - **Validates: Requirements 4.4**
  - [x] 3.12 编写属性测试：删除会话移除所有数据
    - **Property 10: 删除会话移除所有数据**
    - **Validates: Requirements 5.1**
  - [x] 3.13 编写属性测试：删除活跃会话清除引用
    - **Property 11: 删除活跃会话清除引用**
    - **Validates: Requirements 5.2**

- [x] 4. 检查点 - 确保 Service 层所有测试通过
  - 确保所有测试通过，如有问题请向用户确认。

- [x] 5. 实现 ConversationSessionController
  - [x] 5.1 创建 ConversationSessionController
    - 路径前缀 `/api/v1/sessions`，注入 ConversationSessionService 和 JwtUtils
    - 实现 POST `/` 创建新会话接口
    - 实现 GET `/` 获取会话列表接口（按时间分组）
    - _Requirements: 1.1, 1.5, 2.1, 2.2, 2.3, 2.4, 2.5_
  - [x] 5.2 实现会话切换和删除接口
    - 实现 PUT `/{sessionId}/active` 切换活跃会话接口
    - 实现 DELETE `/{sessionId}` 删除会话接口
    - 统一错误响应格式 `{code, message, data}`
    - _Requirements: 3.1, 3.2, 3.4, 5.1, 5.2, 5.3, 5.4, 5.5_
  - [x] 5.3 编写 Controller 层单元测试
    - 使用 MockMvc 测试各接口的请求路由、响应格式和错误场景
    - 测试认证失败返回 401、切换不存在会话返回 404、删除他人会话返回 403
    - _Requirements: 1.5, 2.5, 3.4, 5.4, 5.5_

- [x] 6. 重构 ChatHandler 集成多会话
  - [x] 6.1 重构 ChatHandler 的会话获取逻辑
    - 将 `getOrCreateConversationId` 改为调用 `ConversationSessionService.getActiveSessionId`
    - 无活跃会话时自动调用 `createSession` 创建新会话
    - _Requirements: 6.1, 6.2, 6.3_
  - [x] 6.2 集成自动标题生成和 TTL 刷新
    - 在用户发送第一条消息时调用 `autoGenerateTitle`
    - 每次消息处理后调用 `refreshSessionTTL`
    - 消息存储键保持 `conversation:{sessionId}` 格式不变
    - _Requirements: 4.1, 6.4, 7.5_
  - [x] 6.3 编写属性测试：消息始终存储在活跃会话下
    - **Property 7: 消息始终存储在活跃会话下**
    - **Validates: Requirements 3.3, 6.1, 6.2, 6.4**
  - [x] 6.4 编写属性测试：无活跃会话时自动创建
    - **Property 12: 无活跃会话时自动创建**
    - **Validates: Requirements 6.3**

- [x] 7. 检查点 - 确保 ChatHandler 重构后所有测试通过
  - 确保所有测试通过，如有问题请向用户确认。

- [x] 8. 实现会话列表分组和排序的属性测试
  - [x] 8.1 编写属性测试：会话列表按时间降序排列
    - **Property 3: 会话列表按时间降序排列**
    - **Validates: Requirements 2.1**
  - [x] 8.2 编写属性测试：会话时间分组正确性
    - **Property 4: 会话时间分组正确性**
    - **Validates: Requirements 2.2, 2.3**
  - [x] 8.3 编写属性测试：切换会话设置活跃会话
    - **Property 5: 切换会话设置活跃会话**
    - **Validates: Requirements 3.1**
  - [x] 8.4 编写属性测试：切换会话返回消息历史往返
    - **Property 6: 切换会话返回消息历史往返**
    - **Validates: Requirements 3.2**
  - [x] 8.5 编写属性测试：TTL 管理
    - **Property 13: TTL 管理**
    - **Validates: Requirements 7.4, 7.5**

- [x] 9. 集成与收尾
  - [x] 9.1 配置 SecurityConfig 放行新接口
    - 在 SecurityConfig 中为 `/api/v1/sessions/**` 路径配置认证规则
    - _Requirements: 2.5_
  - [x] 9.2 清理旧 ConversationController 中的冗余逻辑
    - 评估现有 `ConversationController` 是否需要保留或标记为废弃
    - 确保新旧接口不冲突
    - _Requirements: 设计文档-数据迁移_

- [x] 10. 最终检查点 - 确保所有测试通过
  - 确保所有测试通过，如有问题请向用户确认。

## 备注

- 标记 `*` 的任务为可选任务，可跳过以加速 MVP 交付
- 每个任务引用了具体的需求编号，确保可追溯性
- 检查点用于增量验证，确保每个阶段的代码质量
- 属性测试验证通用正确性属性，单元测试验证具体示例和边界情况
- 所有 Redis 操作使用 Lua 脚本保证原子性，避免并发问题
