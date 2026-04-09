# 实现计划：前端多会话管理

## 概述

基于已完成的后端 REST API（`/api/v1/sessions`），在前端聊天页面中实现多会话管理功能。按照自底向上的顺序：先定义类型和 API 层，再构建 Store 状态管理，然后实现 UI 组件，最后集成联调。

## 任务

- [x] 1. 定义 TypeScript 类型和注册 Store 枚举
  - [x] 1.1 在 `frontend/src/typings/api.d.ts` 的 `Api.Chat` 命名空间下新增 `Session`、`SessionDetail`、`GroupedSessionList` 接口
    - `Session`: `sessionId`(string)、`title`(string)、`createdAt`(string)
    - `SessionDetail`: `sessionId`(string)、`title`(string)、`createdAt`(string)、`messages`(Message[])
    - `GroupedSessionList`: `today`(Session[])、`week`(Session[])、`month`(Session[])、`earlier`(Record<string, Session[]>)
    - _需求：6.1, 6.2, 6.3, 6.4_

  - [x] 1.2 在 `frontend/src/enum/index.ts` 的 `SetupStoreId` 枚举中新增 `Session = 'session-store'`
    - _需求：2.1_

- [x] 2. 实现 Session API 服务层
  - [x] 2.1 创建 `frontend/src/service/api/session.ts`，实现 5 个 API 函数
    - `fetchCreateSession()`: POST `/sessions`，返回 `Api.Chat.Session`
    - `fetchSessionList()`: GET `/sessions`，返回 `Api.Chat.GroupedSessionList`
    - `fetchSwitchSession(sessionId)`: PUT `/sessions/{sessionId}/active`，返回 `Api.Chat.SessionDetail`
    - `fetchDeleteSession(sessionId)`: DELETE `/sessions/{sessionId}`
    - `fetchUpdateSessionTitle(sessionId, title)`: PUT `/sessions/{sessionId}/title`
    - 使用项目现有的 `request` 函数，baseURL 使用默认值
    - _需求：1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [x] 2.2 在 `frontend/src/service/api/index.ts` 中导出 `export * from './session'`
    - _需求：1.6_

- [x] 3. 改造 Chat Store 并实现 Session Store
  - [x] 3.1 在 `frontend/src/store/modules/chat/index.ts` 中新增 `setMessages` 和 `clearMessages` 方法
    - `setMessages(messages: Api.Chat.Message[])`: 替换 `list`
    - `clearMessages()`: 清空 `list` 为空数组
    - _需求：5.1, 5.2, 5.3_

  - [x] 3.2 创建 `frontend/src/store/modules/session/index.ts`，实现 Session Store
    - 响应式状态：`sessionList`、`activeSessionId`、`loading`、`creating`
    - `loadSessionList()`: 调用 API 获取列表，更新 `sessionList`
    - `createSession()`: 调用 API 创建会话，刷新列表，设置 `activeSessionId`，调用 Chat Store `clearMessages()`
    - `switchSession(sessionId)`: 调用 API 切换会话，更新 `activeSessionId`，调用 Chat Store `setMessages()`
    - `deleteSession(sessionId)`: 调用 API 删除会话，刷新列表；若删除的是活跃会话则清空 `activeSessionId` 和消息
    - 错误处理：各方法失败时通过 `window.$message?.error()` 提示，重置 loading 状态
    - _需求：2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [x] 3.3 编写 Session Store 属性测试（fast-check）
    - **属性 1：加载会话列表同步到 Store 状态**
    - **验证需求：2.4**

  - [x] 3.4 编写 Session Store 属性测试（fast-check）
    - **属性 2：创建会话设置活跃 ID 并清空消息**
    - **验证需求：2.5, 5.2**

  - [x] 3.5 编写 Session Store 属性测试（fast-check）
    - **属性 3：切换会话同步活跃 ID 和消息历史**
    - **验证需求：2.6, 5.1**

  - [x] 3.6 编写 Session Store 属性测试（fast-check）
    - **属性 4：删除会话后列表不再包含该会话；若删除活跃会话则清空状态**
    - **验证需求：2.7, 2.8, 5.3**

- [x] 4. 检查点 - 确保类型、API 层和 Store 层无编译错误
  - 确保所有测试通过，如有问题请向用户确认。

- [x] 5. 实现 SessionPanel 组件
  - [x] 5.1 创建 `frontend/src/views/chat/modules/session-panel.vue`
    - 顶部"开启新对话"按钮，绑定 `creating` 状态实现 loading/disabled 防抖
    - 按时间分组渲染会话列表（今天、7天内、30天内、更早按年月细分）
    - 每个 Session_Item 显示 `title`，点击调用 `switchSession`
    - 当前 `activeSessionId` 对应的 Item 高亮显示
    - 悬浮显示删除图标，点击弹出 Naive UI 确认对话框后调用 `deleteSession`
    - 空状态提示文案
    - 加载中显示骨架屏或 NSpin
    - 加载失败显示错误提示和重试按钮
    - 组件 `onMounted` 时调用 `loadSessionList()`
    - _需求：3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 7.1, 7.2, 7.3, 8.1, 8.2_

  - [x] 5.2 编写 SessionPanel 属性测试（fast-check）
    - **属性 5：会话列表渲染包含所有分组和标题**
    - **验证需求：3.3, 3.4**

  - [x] 5.3 编写 SessionPanel 属性测试（fast-check）
    - **属性 6：活跃会话高亮唯一性**
    - **验证需求：3.6**

  - [x] 5.4 编写 SessionPanel 属性测试（fast-check）
    - **属性 8：创建按钮防抖状态**
    - **验证需求：7.1, 7.2**

  - [x] 5.5 编写 SessionPanel 属性测试（fast-check）
    - **属性 9：列表加载指示器**
    - **验证需求：8.1**

- [x] 6. 实现 WelcomeView 组件和聊天页面布局重构
  - [x] 6.1 创建 `frontend/src/views/chat/modules/welcome-view.vue`
    - 显示欢迎文案"今天有什么可以帮到你？"
    - 包含"开启新对话"按钮，点击调用 Session Store 的 `createSession`
    - _需求：4.3_

  - [x] 6.2 重构 `frontend/src/views/chat/index.vue` 为左右双栏布局
    - 左侧 SessionPanel 固定宽度 260px
    - 右侧根据 `activeSessionId` 条件渲染：为空显示 WelcomeView，非空显示 ChatList + InputBox
    - _需求：4.1, 4.2, 4.3, 4.4_

  - [x] 6.3 改造 `frontend/src/views/chat/modules/chat-list.vue`
    - 移除组件内的 `getList()` 自动加载逻辑和日期范围查询
    - 消息数据完全由 Chat Store 的 `list` 驱动（通过 Session Store 切换会话时注入）
    - _需求：5.1_

  - [x] 6.4 编写聊天页面属性测试（fast-check）
    - **属性 7：右侧面板条件渲染**
    - **验证需求：4.3, 4.4**

- [x] 7. 最终检查点 - 确保所有代码编译通过，功能完整
  - 确保所有测试通过，如有问题请向用户确认。

## 备注

- 标记 `*` 的任务为可选，可跳过以加速 MVP 交付
- 每个任务引用了具体的需求编号，确保可追溯性
- 属性测试使用 fast-check 库，验证设计文档中定义的正确性属性
- 检查点任务用于阶段性验证，确保增量开发的稳定性
