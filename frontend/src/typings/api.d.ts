/**
 * Namespace Api
 *
 * All backend api type
 */
declare namespace Api {
  namespace Common {
    /** common params of paginating */
    interface PaginatingCommonParams {
      /** current page number */
      page?: number;
      number: number;
      /** page size */
      size?: number;
      /** total count */
      totalElements: number;
    }

    /** common params of paginating query list data */
    interface PaginatingQueryRecord<T = any> extends PaginatingCommonParams {
      data: T[];
      content: T[];
    }

    /** common search params of table */
    type CommonSearchParams = Pick<Common.PaginatingCommonParams, 'page' | 'size'>;
  }

  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
    }

    interface UserInfo {
      id: number;
      username: string;
      role: 'USER' | 'ADMIN';
      orgTags: string[];
      primaryOrg: string;
    }
  }

  /**
   * namespace Route
   *
   * backend api module: "route"
   */
  namespace Route {
    type ElegantConstRoute = import('@elegant-router/types').ElegantConstRoute;

    interface MenuRoute extends ElegantConstRoute {
      id: string;
    }

    interface UserRoute {
      routes: MenuRoute[];
      home: import('@elegant-router/types').LastLevelRouteKey;
    }
  }

  namespace OrgTag {
    interface Item {
      tagId: string;
      name: string;
      description: string;
      parentTag: string | null;
      children?: Item[];
    }

    type List = Common.PaginatingQueryRecord<Item>;

    type Details = Pick<Item, 'tagId' | 'name' | 'description'>;
    type Mine = {
      orgTags: string[];
      primaryOrg: string;
      orgTagDetails: Details[];
    };
  }

  namespace User {
    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        keyword: string;
        orgTag: string;
        status: number;
      }
    >;

    type Item = {
      userId: string;
      username: string;
      email: string;
      status: number;
      orgTags: Pick<OrgTag.Item, 'tagId' | 'name'>[];
      primaryOrg: string;
      createTime: string;
      lastLoginTime: string;
    };

    type List = Common.PaginatingQueryRecord<Item>;
  }

  namespace KnowledgeBase {
    interface SearchParams {
      userId: string;
      query: string;
      topK: number;
    }

    interface SearchResult {
      fileMd5: string;
      chunkId: number;
      textContent: string;
      score: number;
      fileName: string;
    }

    interface UploadState {
      tasks: UploadTask[];
      activeUploads: Set<string>; // 当前正在上传的任务ID
    }

    interface Form {
      orgTag: string | null;
      orgTagName: string | null;
      isPublic: boolean;
      fileList: import('naive-ui').UploadFileInfo[];
    }

    interface UploadTask {
      file: File;
      chunk: Blob | null;
      fileMd5: string;
      chunkIndex: number;
      totalSize: number;
      fileName: string;
      orgTag: string | null;
      orgTagName?: string | null;
      public: boolean;
      isPublic: boolean;
      uploadedChunks: number[];
      progress: number;
      status: UploadStatus;
      createdAt?: string;
      mergedAt?: string;
      requestIds?: string[]; // 请求ID，用于取消上传
    }
    type List = Common.PaginatingQueryRecord<UploadTask>;

    type Merge = Pick<UploadTask, 'fileMd5' | 'fileName'>;

    interface Progress {
      uploaded: number[];
      progress: number;
      totalChunks: number;
    }

    interface Result {
      objectUrl: string;
      fileSize: number;
    }
  }

  namespace Chat {
    interface Input {
      message: string;
      conversationId?: string;
    }

    interface Output {
      chunk: string;
    }

    interface Conversation {
      conversationId: string;
    }

    interface ToolCallStatus {
      function: string;
      status: 'executing' | 'done';
    }

    interface Message {
      role: 'user' | 'assistant';
      content: string;
      thinkingContent?: string;
      status?: 'pending' | 'loading' | 'finished' | 'error';
      timestamp?: string;
      /** 工具调用状态列表（Agent 模式下 LLM 调用工具时填充） */
      toolCalls?: ToolCallStatus[];
      /** Trace ID（T1-4 feedback 精准归因用，completion 帧回传） */
      traceId?: string;
    }

    interface Token {
      cmdToken: string;
    }

    /** 会话基本信息，对应后端 SessionDTO */
    interface Session {
      sessionId: string;
      title: string;
      createdAt: string;
    }

    /** 会话详情（含消息历史），对应后端 SessionDetailDTO */
    interface SessionDetail {
      sessionId: string;
      title: string;
      createdAt: string;
      messages: Message[];
    }

    /** 按时间分组的会话列表，对应后端 GroupedSessionListDTO */
    interface GroupedSessionList {
      today: Session[];
      week: Session[];
      month: Session[];
      earlier: Record<string, Session[]>;
    }
  }

  namespace Llm {
    interface Provider {
      id: string;
      supportsToolCalling: boolean;
      current: boolean;
    }

    interface ProvidersResponse {
      currentProvider: string;
      providers: Provider[];
    }

    interface PreferenceResponse {
      message: string;
      currentProvider: string;
    }
  }

  namespace Trace {
    /** Trace 列表查询参数 */
    type ListParams = CommonType.RecordNullable<{
      startDate: string;
      endDate: string;
      userId: string;
      sessionId: string;
      keyword: string;
      status: 'OK' | 'ERROR' | '';
      page: number;
      size: number;
    }>;

    /** 单条 Trace 摘要（列表行） */
    interface TraceSummary {
      traceId: string;
      conversationId: string;
      userId: string;
      sessionId: string;
      createdAt: string;
      eventCount: number;
      totalInputTokens: number;
      totalOutputTokens: number;
      totalLatencyMs: number;
      llmCallCount: number;
      hasError: boolean;
      errorMessage: string;
      firstUserInput: string;
    }

    /** 列表聚合统计 */
    interface ListStats {
      totalCount: number;
      errorCount: number;
      p50Latency: string;
      p99Latency: string;
      totalTokens: string;
    }

    /** 列表响应 */
    interface ListResponse {
      list: TraceSummary[];
      total: number;
      stats: ListStats;
    }

    /** 单个事件（对应后端 TraceEvent） */
    interface EventItem {
      traceId: string;
      conversationId: string;
      userId: string;
      sessionId: string;
      stepOrder: number;
      eventType: 'USER_INPUT' | 'AGENT_START' | 'LLM_CALL' | 'TOOL_CALL' | 'AGENT_COMPLETE' | 'ERROR';
      phase: 'INPUT' | 'AGENT' | 'LLM' | 'TOOL' | 'ERROR';
      model: string;
      inputPayload: string;
      outputPayload: string;
      inputTokens: number;
      outputTokens: number;
      totalTokens: number;
      latencyMs: number;
      success: boolean;
      createdAt: string;
    }

    /** 详情头部摘要 */
    interface DetailSummary {
      traceId: string;
      conversationId: string;
      userId: string;
      sessionId: string;
      status: 'SUCCESS' | 'ERROR';
      startTime: string;
      endTime: string;
      durationMs: number;
      eventCount: number;
      llmCallCount: number;
      inputTokens: number;
      outputTokens: number;
      totalTokens: number;
    }

    /** 详情响应 */
    interface DetailResponse {
      summary: DetailSummary;
      events: EventItem[];
    }
  }

  namespace Document {
    interface DownloadResponse {
      fileName: string;
      downloadUrl: string;
      fileSize: number;
    }
  }

  namespace Feedback {
    /** 反馈动作类型 */
    type Action = 'LIKE' | 'DISLIKE' | 'PARTIAL_CORRECT' | 'OUTDATED';

    /** 提交反馈请求 */
    interface Request {
      conversationId: string;
      traceId: string;
      action: Action;
      rating?: number;
      comment?: string;
    }
  }

  namespace Eval {
    /** 评测模式 */
    type Mode = 'TIME_RANGE' | 'GOLD_SET' | 'BADCASE_ONLY';

    /** 评测请求 */
    interface Request {
      mode: Mode;
      startAt?: string;
      endAt?: string;
      userId?: string;
      includeJudge?: boolean;
      limit?: number;
    }

    /** 任务启动响应（data 字段内容） */
    interface TaskStartResponse {
      taskId: string;
      status: string;
    }

    /** 指标统计 */
    interface MetricStat {
      value: number | null;
      n: number;
      scope?: string;
    }

    /** 单条 trace 评估结果 */
    interface TraceEvalResult {
      traceId: string;
      conversationId: string;
      createdAt: string;
      score: number | null;
      ruleScore: number | null;
      judgeScore: number | null;
      feedbackScore: number | null;
      metrics: Record<string, number | null>;
      detail: Record<string, unknown>;
    }

    /** 失败 trace */
    interface FailedTrace {
      traceId: string;
      score: number;
      failReason: string;
    }

    /** 评测报告 */
    interface EvalReport {
      startAt: string;
      endAt: string;
      totalTraces: number;
      labeledTraces: number;
      labelCoverage: number;
      averageScore: number | null;
      pass: boolean;
      passReason: string | null;
      metricAverages: Record<string, MetricStat>;
      details: TraceEvalResult[];
      failedTraces: FailedTrace[];
    }

    /** 任务状态响应（data 字段内容） */
    interface TaskStatusResponse {
      taskId: string;
      status: 'RUNNING' | 'COMPLETED' | 'FAILED';
      progress: { total: number; done: number };
      result?: EvalReport;
      errorMessage?: string;
    }

    /** 标注行 */
    interface SampleRow {
      traceId: string;
      conversationId?: string;
      expectedIntent?: string;
      expectedSlots?: string;
      expectedClarifyAction?: string;
      expectedAnswer?: string;
      labelNote?: string;
      labeledBy?: string;
      labeledAt?: string;
      source?: string;
    }

    /** 标注列表查询参数 */
    interface SampleListParams {
      source: string;
      status: 'pending' | 'labeled';
      page?: number;
      size?: number;
    }

    /** 标注列表响应（data 字段内容） */
    interface SampleListResponse {
      list: SampleRow[];
      total: number;
      page: number;
      size: number;
    }
  }
}
