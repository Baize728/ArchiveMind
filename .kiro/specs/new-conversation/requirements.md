# 需求文档

## 简介

为 ArchiveMind 系统新增"开启新对话"功能，支持用户创建多个独立的对话会话，并在侧边栏中按时间分组展示历史对话列表。用户可以随时开启新对话、切换历史对话，实现类似 DeepSeek 的多会话交互体验。

当前系统中每个用户在 Redis 中仅维护一个 `current_conversation`，不支持多会话管理。本功能将引入会话（Session）的概念，使用户能够管理多个独立的对话上下文。

## 术语表

- **Conversation_Session**: 一次独立的对话会话，包含唯一标识、标题、创建时间等元数据，以及该会话下的所有消息历史
- **Conversation_List**: 侧边栏中按时间分组展示的历史对话列表
- **Active_Session**: 用户当前正在进行交互的对话会话
- **Session_Title**: 对话会话的标题，默认由第一条用户消息自动生成
- **Time_Group**: 对话列表中的时间分组标签（如"今天"、"7天内"、"30天内"、"更早"）
- **Welcome_View**: 开启新对话后、用户发送第一条消息前显示的欢迎界面
- **Conversation_API**: 提供对话会话管理功能的后端 REST API
- **Redis_Store**: 使用 Redis 存储对话会话元数据和消息历史的持久化层

## 需求

### 需求 1：创建新对话会话

**用户故事：** 作为用户，我希望能够开启一个全新的对话会话，以便在不同主题之间保持上下文独立。

#### 验收标准

1. WHEN 用户点击"开启新对话"按钮, THE Conversation_API SHALL 创建一个新的 Conversation_Session 并返回该会话的唯一标识（UUID）和创建时间
2. WHEN 一个新的 Conversation_Session 被创建, THE Redis_Store SHALL 将该会话的元数据（包括会话ID、用户ID、标题、创建时间）持久化存储，过期时间为 30 天
3. WHEN 一个新的 Conversation_Session 被创建, THE Conversation_API SHALL 将用户的 Active_Session 切换为新创建的会话
4. WHEN 用户尚未发送任何消息, THE Welcome_View SHALL 显示欢迎界面（"今天有什么可以帮到你？"）和消息输入框
5. IF 创建新对话会话时发生错误, THEN THE Conversation_API SHALL 返回包含错误码和错误描述的 JSON 响应，HTTP 状态码为 500

### 需求 2：对话会话列表查询

**用户故事：** 作为用户，我希望在侧边栏看到我的所有历史对话，按时间分组展示，以便快速找到并切换到之前的对话。

#### 验收标准

1. WHEN 用户请求对话列表, THE Conversation_API SHALL 返回当前用户的所有 Conversation_Session，按创建时间降序排列
2. THE Conversation_API SHALL 将对话列表按 Time_Group 分组返回，分组规则为：今天、7天内、30天内、更早（按年月标注，如"2026-03"）
3. THE Conversation_API SHALL 为每个 Conversation_Session 返回会话ID、Session_Title 和创建时间
4. WHEN 用户没有任何历史对话, THE Conversation_API SHALL 返回空列表，HTTP 状态码为 200
5. IF 查询对话列表时发生认证失败, THEN THE Conversation_API SHALL 返回 HTTP 状态码 401 和错误描述

### 需求 3：切换对话会话

**用户故事：** 作为用户，我希望能够点击侧边栏中的历史对话来切换到该对话，以便继续之前的讨论。

#### 验收标准

1. WHEN 用户选择一个历史 Conversation_Session, THE Conversation_API SHALL 将该会话设置为用户的 Active_Session
2. WHEN 用户切换到一个历史 Conversation_Session, THE Conversation_API SHALL 返回该会话的完整消息历史（包含每条消息的角色、内容和时间戳）
3. WHEN 用户在切换后的会话中发送新消息, THE ChatHandler SHALL 在该 Active_Session 的上下文中处理消息，保持对话连续性
4. IF 用户尝试切换到一个不存在或已过期的 Conversation_Session, THEN THE Conversation_API SHALL 返回 HTTP 状态码 404 和错误描述"对话不存在或已过期"

### 需求 4：对话会话标题管理

**用户故事：** 作为用户，我希望每个对话有一个有意义的标题，以便在对话列表中快速识别对话内容。

#### 验收标准

1. WHEN 用户在新对话中发送第一条消息, THE Conversation_API SHALL 自动将该消息内容的前 20 个字符设置为 Session_Title
2. WHEN Session_Title 由系统自动生成且原始消息超过 20 个字符, THE Conversation_API SHALL 在截断后的标题末尾追加省略号（"..."）
3. WHEN 新对话尚未收到任何消息, THE Conversation_API SHALL 将 Session_Title 设置为"新对话"
4. THE Redis_Store SHALL 在 Session_Title 更新后立即持久化新标题

### 需求 5：删除对话会话

**用户故事：** 作为用户，我希望能够删除不再需要的历史对话，以保持对话列表整洁。

#### 验收标准

1. WHEN 用户请求删除一个 Conversation_Session, THE Conversation_API SHALL 从 Redis_Store 中移除该会话的所有数据（元数据和消息历史）
2. WHEN 被删除的 Conversation_Session 是当前的 Active_Session, THE Conversation_API SHALL 清除用户的 Active_Session 引用，使用户回到 Welcome_View 状态
3. THE Conversation_API SHALL 在删除成功后返回 HTTP 状态码 200 和确认消息
4. IF 用户尝试删除不属于自己的 Conversation_Session, THEN THE Conversation_API SHALL 返回 HTTP 状态码 403 和错误描述"无权删除该对话"
5. IF 用户尝试删除不存在的 Conversation_Session, THEN THE Conversation_API SHALL 返回 HTTP 状态码 404 和错误描述"对话不存在"

### 需求 6：对话会话与 WebSocket 集成

**用户故事：** 作为用户，我希望在切换对话后，WebSocket 连接能够自动关联到正确的对话上下文，以便消息被正确存储。

#### 验收标准

1. WHEN 用户通过 WebSocket 发送消息, THE ChatHandler SHALL 使用用户当前的 Active_Session 作为对话上下文
2. WHEN 用户的 Active_Session 发生变更, THE ChatHandler SHALL 在处理下一条消息时使用新的 Active_Session 上下文
3. WHEN 用户没有 Active_Session（例如刚删除了当前对话）, THE ChatHandler SHALL 自动创建一个新的 Conversation_Session 并将其设置为 Active_Session
4. THE ChatHandler SHALL 将每条消息（包含角色、内容、时间戳）存储到当前 Active_Session 对应的 Redis 键中

### 需求 7：用户会话列表的 Redis 存储

**用户故事：** 作为开发者，我希望用户的会话列表在 Redis 中有清晰的数据结构，以便高效查询和管理。

#### 验收标准

1. THE Redis_Store SHALL 使用键 `user:{userId}:sessions` 存储用户的所有会话ID列表（使用 Redis Sorted Set，以创建时间戳作为分数）
2. THE Redis_Store SHALL 使用键 `session:{sessionId}:meta` 存储每个会话的元数据（JSON 格式，包含 sessionId、userId、title、createdAt 字段）
3. THE Redis_Store SHALL 使用键 `conversation:{sessionId}` 存储每个会话的消息历史（与现有格式兼容）
4. THE Redis_Store SHALL 为所有会话相关的键设置 30 天的过期时间
5. WHEN 用户在某个会话中发送新消息, THE Redis_Store SHALL 刷新该会话所有相关键的过期时间为 30 天
