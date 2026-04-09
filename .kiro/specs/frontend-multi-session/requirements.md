# 需求文档

## 简介

为 ArchiveMind 前端系统新增多会话管理功能。后端 REST API（`/api/v1/sessions`）已完成，前端需要对接这些 API，在聊天页面中实现会话列表展示、新建会话、切换会话、删除会话等交互能力。

当前聊天页面（`frontend/src/views/chat/index.vue`）仅支持单一对话，没有会话列表和切换功能。Chat Store 中只维护一个消息列表和 WebSocket 连接。本功能将在前端引入完整的多会话管理 UI 和状态管理。

## 术语表

- **Session_Panel**: 聊天页面左侧的会话列表面板组件，展示用户所有历史对话
- **Session_Item**: 会话列表中的单个会话条目，显示会话标题，支持点击切换和删除操作
- **Active_Session**: 当前用户正在交互的会话，在 Session_Panel 中高亮显示
- **Time_Group**: 会话列表中的时间分组标签（今天、7天内、30天内、更早）
- **Session_Store**: Pinia Store，管理会话列表数据、活跃会话ID和会话相关的 API 调用
- **Chat_Store**: 现有的 Pinia Store，管理 WebSocket 连接和当前会话的消息列表
- **Session_API**: 封装后端 `/api/v1/sessions` 接口调用的前端 API 服务模块
- **Welcome_View**: 新建会话后、用户发送第一条消息前显示的欢迎界面

## 需求

### 需求 1：会话 API 服务层

**用户故事：** 作为前端开发者，我希望有一个统一的 API 服务模块封装后端会话接口，以便在组件和 Store 中复用。

#### 验收标准

1. THE Session_API SHALL 提供 `fetchCreateSession` 函数，调用 `POST /api/v1/sessions` 创建新会话，返回类型包含 `sessionId`、`title`、`createdAt` 字段
2. THE Session_API SHALL 提供 `fetchSessionList` 函数，调用 `GET /api/v1/sessions` 获取按时间分组的会话列表，返回类型包含 `today`、`week`、`month`、`earlier` 分组
3. THE Session_API SHALL 提供 `fetchSwitchSession` 函数，调用 `PUT /api/v1/sessions/{sessionId}/active` 切换活跃会话，返回类型包含 `sessionId`、`title`、`messages` 数组
4. THE Session_API SHALL 提供 `fetchDeleteSession` 函数，调用 `DELETE /api/v1/sessions/{sessionId}` 删除指定会话
5. THE Session_API SHALL 提供 `fetchUpdateSessionTitle` 函数，调用 `PUT /api/v1/sessions/{sessionId}/title` 更新会话标题
6. THE Session_API SHALL 使用项目现有的 `request` 函数（来自 `@/service/request`）发起所有 HTTP 请求，确保自动携带 JWT Token

### 需求 2：会话状态管理（Session Store）

**用户故事：** 作为前端开发者，我希望有一个专门的 Pinia Store 管理会话状态，以便多个组件共享会话数据。

#### 验收标准

1. THE Session_Store SHALL 在 `SetupStoreId` 枚举中注册为 `'session-store'`，使用 Pinia `defineStore` 的 setup 语法定义
2. THE Session_Store SHALL 维护 `sessionList` 响应式状态，类型为按时间分组的会话列表（包含 `today`、`week`、`month`、`earlier` 分组）
3. THE Session_Store SHALL 维护 `activeSessionId` 响应式状态，记录当前活跃会话的 ID
4. THE Session_Store SHALL 提供 `loadSessionList` 方法，调用 Session_API 获取会话列表并更新 `sessionList` 状态
5. THE Session_Store SHALL 提供 `createSession` 方法，调用 Session_API 创建新会话，创建成功后刷新会话列表并将新会话设置为 Active_Session
6. THE Session_Store SHALL 提供 `switchSession` 方法，调用 Session_API 切换会话，更新 `activeSessionId` 并将返回的消息历史同步到 Chat_Store 的消息列表
7. THE Session_Store SHALL 提供 `deleteSession` 方法，调用 Session_API 删除会话，删除成功后刷新会话列表
8. WHEN 被删除的会话是当前 Active_Session, THE Session_Store SHALL 清空 `activeSessionId` 并清空 Chat_Store 的消息列表

### 需求 3：会话列表面板组件

**用户故事：** 作为用户，我希望在聊天页面左侧看到我的所有历史对话，按时间分组展示，以便快速找到并切换到之前的对话。

#### 验收标准

1. THE Session_Panel SHALL 作为 Vue 3 组件渲染在聊天页面左侧，使用 Naive UI 组件库构建
2. THE Session_Panel SHALL 在顶部显示一个"开启新对话"按钮，点击后调用 Session_Store 的 `createSession` 方法
3. THE Session_Panel SHALL 按 Time_Group 分组展示会话列表，分组标签为"今天"、"7天内"、"30天内"、"更早"（更早按年月细分，如"2025-06"）
4. THE Session_Panel SHALL 为每个 Session_Item 显示会话标题（`title` 字段）
5. WHEN 用户点击某个 Session_Item, THE Session_Panel SHALL 调用 Session_Store 的 `switchSession` 方法切换到该会话
6. THE Session_Panel SHALL 将当前 Active_Session 对应的 Session_Item 以视觉高亮样式区分显示
7. THE Session_Panel SHALL 为每个 Session_Item 提供删除操作入口（如悬浮显示删除图标），点击后调用 Session_Store 的 `deleteSession` 方法
8. WHEN 删除操作触发时, THE Session_Panel SHALL 显示确认对话框，用户确认后执行删除
9. WHEN 会话列表为空, THE Session_Panel SHALL 显示空状态提示文案
10. THE Session_Panel SHALL 在组件挂载时自动调用 Session_Store 的 `loadSessionList` 方法加载会话列表

### 需求 4：聊天页面布局重构

**用户故事：** 作为用户，我希望聊天页面包含左侧会话列表和右侧聊天区域的双栏布局，以便同时查看会话列表和当前对话。

#### 验收标准

1. THE 聊天页面 SHALL 采用左右双栏布局，左侧为 Session_Panel，右侧为现有的消息列表和输入框
2. THE 聊天页面 SHALL 为 Session_Panel 分配固定宽度（约 260px），右侧聊天区域占据剩余空间
3. WHEN 当前没有 Active_Session（`activeSessionId` 为空）, THE 聊天页面右侧 SHALL 显示 Welcome_View，包含欢迎文案"今天有什么可以帮到你？"和"开启新对话"按钮
4. WHEN 存在 Active_Session, THE 聊天页面右侧 SHALL 显示该会话的消息列表和输入框

### 需求 5：Chat Store 与 Session Store 集成

**用户故事：** 作为前端开发者，我希望 Chat Store 能与 Session Store 协同工作，确保消息始终关联到正确的会话。

#### 验收标准

1. WHEN Session_Store 的 `switchSession` 方法被调用, THE Chat_Store SHALL 用返回的消息历史替换当前的消息列表
2. WHEN Session_Store 的 `createSession` 方法被调用, THE Chat_Store SHALL 清空当前消息列表，准备接收新会话的消息
3. WHEN Session_Store 的 `deleteSession` 删除了当前 Active_Session, THE Chat_Store SHALL 清空消息列表
4. THE Chat_Store SHALL 保持现有的 WebSocket 连接管理逻辑不变，WebSocket 消息由后端根据 Active_Session 自动路由到正确的会话

### 需求 6：TypeScript 类型定义

**用户故事：** 作为前端开发者，我希望会话相关的数据类型在 `api.d.ts` 中有完整定义，以便获得类型安全和 IDE 提示。

#### 验收标准

1. THE 类型定义 SHALL 在 `Api.Chat` 命名空间下新增 `Session` 接口，包含 `sessionId`（string）、`title`（string）、`createdAt`（string）字段
2. THE 类型定义 SHALL 在 `Api.Chat` 命名空间下新增 `SessionDetail` 接口，包含 `sessionId`（string）、`title`（string）、`messages`（Message 数组）字段
3. THE 类型定义 SHALL 在 `Api.Chat` 命名空间下新增 `GroupedSessionList` 接口，包含 `today`（Session 数组）、`week`（Session 数组）、`month`（Session 数组）、`earlier`（Record<string, Session[]>）字段
4. THE 类型定义 SHALL 与后端 DTO（`SessionDTO`、`SessionDetailDTO`、`GroupedSessionListDTO`、`MessageDTO`）的字段名和类型保持一致

### 需求 7："开启新对话"按钮防抖

**用户故事：** 作为用户，我希望快速连续点击"开启新对话"按钮时不会创建多个重复会话。

#### 验收标准

1. WHEN 用户点击"开启新对话"按钮, THE Session_Panel SHALL 在请求完成前禁用该按钮，防止重复点击
2. WHEN 创建会话的 API 请求正在进行中, THE Session_Panel SHALL 在按钮上显示加载状态指示
3. IF 创建会话的 API 请求失败, THEN THE Session_Panel SHALL 显示错误提示消息并重新启用按钮

### 需求 8：会话列表加载状态

**用户故事：** 作为用户，我希望在会话列表加载过程中看到加载指示，以便了解数据正在获取中。

#### 验收标准

1. WHILE 会话列表数据正在从后端加载, THE Session_Panel SHALL 显示加载骨架屏或旋转加载指示器
2. IF 会话列表加载失败, THEN THE Session_Panel SHALL 显示错误提示并提供重试按钮
