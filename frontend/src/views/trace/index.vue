<script setup lang="ts">
import { fetchTraceList, fetchTraceDetail } from '@/service/api/trace';
import { NButton, NTag, NSelect } from 'naive-ui';
import { useAuthStore } from '@/store/modules/auth';
import dayjs from 'dayjs';
import type { PropType, VNode } from 'vue';

defineOptions({ name: 'Trace' });

// 当前登录用户信息（用户 ID 输入框默认值）
const authStore = useAuthStore();
const currentUserId = computed(() => authStore.userInfo?.username || '');

// ── 模式切换：list / detail ──────────────────────────────
const mode = ref<'list' | 'detail'>('list');
const selectedTraceId = ref('');

// ── 列表模式状态 ──────────────────────────────────────────
const loading = ref(false);
const listData = ref<Api.Trace.TraceSummary[]>([]);
const total = ref(0);
const stats = ref<Api.Trace.ListStats | null>(null);

// 筛选条件
const filters = reactive({
  traceId: '',
  startDate: '',
  endDate: '',
  userId: '',
  sessionId: '',
  keyword: '',
  minLatency: '',
  maxLatency: '',
  customTags: [] as Array<{ key: string; op: string; value: string }>
});

// 状态过滤（默认 OK + ERROR 都勾选，即不过滤）
const statusFilters = reactive({
  ok: true,
  error: true
});

// 自定义标签操作符选项
const operatorOptions = [
  { label: '等于', value: '=' },
  { label: '不等于', value: '!=' },
  { label: '包含', value: 'contains' }
];

function addCustomTag() {
  filters.customTags.push({ key: '', op: '=', value: '' });
}

function clearCustomTags() {
  filters.customTags.splice(0, filters.customTags.length);
}
const pagination = reactive({
  page: 1,
  pageSize: 20
});

// 时间范围（NDatePicker）
const dateRange = ref<[number, number] | null>(null);

watchEffect(() => {
  if (dateRange.value) {
    filters.startDate = dayjs(dateRange.value[0]).format('YYYY-MM-DD');
    filters.endDate = dayjs(dateRange.value[1]).format('YYYY-MM-DD');
  } else {
    filters.startDate = '';
    filters.endDate = '';
  }
});

/** 加载列表 */
async function loadList() {
  loading.value = true;

  // 由 statusFilters 推导 status（兼容后端单值参数）
  let statusParam: '' | 'OK' | 'ERROR' = '';
  if (statusFilters.ok && !statusFilters.error) statusParam = 'OK';
  else if (!statusFilters.ok && statusFilters.error) statusParam = 'ERROR';

  // 把 minLatency/maxLatency 字符串转为数字（空字符串不传）
  const minLatencyNum = filters.minLatency ? Number(filters.minLatency) : undefined;
  const maxLatencyNum = filters.maxLatency ? Number(filters.maxLatency) : undefined;

  const { error, data } = await fetchTraceList({
    ...filters,
    status: statusParam,
    minLatency: minLatencyNum,
    maxLatency: maxLatencyNum,
    page: pagination.page,
    size: pagination.pageSize
  });
  if (!error && data) {
    listData.value = data.list;
    total.value = data.total;
    stats.value = data.stats;
  }
  loading.value = false;
}

/** 重置筛选 */
function resetFilters() {
  filters.traceId = '';
  filters.userId = '';
  filters.sessionId = '';
  filters.keyword = '';
  filters.minLatency = '';
  filters.maxLatency = '';
  filters.customTags.splice(0, filters.customTags.length);
  statusFilters.ok = true;
  statusFilters.error = true;
  dateRange.value = null;
  pagination.page = 1;
  loadList();
}

/** 搜索 */
function handleSearch() {
  pagination.page = 1;
  loadList();
}

/** 翻页 */
function onPageChange(page: number) {
  pagination.page = page;
  loadList();
}

// ── 详情模式状态 ──────────────────────────────────────────
const detailLoading = ref(false);
const detailSummary = ref<Api.Trace.DetailSummary | null>(null);
const detailEvents = ref<Api.Trace.EventItem[]>([]);
const selectedEventIndex = ref<number>(-1);

/** 进入详情 */
async function openDetail(traceId: string) {
  selectedTraceId.value = traceId;
  mode.value = 'detail';
  detailLoading.value = true;
  selectedEventIndex.value = -1;

  const { error, data } = await fetchTraceDetail(traceId);
  if (!error && data) {
    detailSummary.value = data.summary;
    detailEvents.value = data.events;
    // 默认选中第一个事件
    if (data.events.length > 0) selectedEventIndex.value = 0;
  }
  detailLoading.value = false;
}

/** 返回列表 */
function backToList() {
  mode.value = 'list';
  selectedTraceId.value = '';
  detailSummary.value = null;
  detailEvents.value = [];
  selectedEventIndex.value = -1;
}

/** 当前选中事件 */
const currentEvent = computed(() => {
  if (selectedEventIndex.value < 0 || !detailEvents.value.length) return null;
  return detailEvents.value[selectedEventIndex.value];
});

// ── 格式化工具 ────────────────────────────────────────────
function formatLatency(ms: number): string {
  if (ms < 1000) return `${ms.toFixed(2)}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(2)}s`;
  return `${(ms / 60000).toFixed(2)}m`;
}

function formatTokens(n: number): string {
  if (n < 1024) return `${n}`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(2)}K`;
  return `${(n / (1024 * 1024)).toFixed(2)}M`;
}

/** 事件类型标签颜色与文本 */
function eventTypeTag(eventType: string): { type: 'success' | 'info' | 'warning' | 'error' | 'default'; label: string } {
  try {
    const map: Record<string, { type: 'success' | 'info' | 'warning' | 'error' | 'default'; label: string }> = {
    USER_INPUT: { type: 'success', label: '输入' },
    LLM_CALL: { type: 'info', label: 'LLM' },
    TOOL_CALL: { type: 'warning', label: 'tool' },
    AGENT_DURATION: { type: 'success', label: '完成' },
    INTENT_RECOGNIZED: { type: 'info', label: '意图识别' },
    LEGACY: { type: 'default', label: '历史' },
    ERROR: { type: 'error', label: '错误' }
    };
    // null/undefined 兜底（兼容 DB 中已废弃枚举值被后端降级为 null 的情况）
    if (!eventType) return { type: 'default', label: '未知' };
    return map[eventType] || { type: 'default', label: eventType };
  } catch {
    return { type: 'default', label: '未知' };
  }
}

/** 复制到剪贴板 */
function copyText(text: string) {
  navigator.clipboard.writeText(text).then(() => {
    window.$message?.success('已复制');
  });
}

// ── 初始化加载 ────────────────────────────────────────────
// 当 authStore.userInfo 异步加载完成时，自动填入用户 ID 输入框
watch(currentUserId, (newVal) => {
  if (newVal && !filters.userId) {
    filters.userId = newVal;
  }
}, { immediate: true });

onMounted(() => {
  loadList();
});

// ── 列表表格列定义 ──────────────────────────────────────────
/** 尝试解析 JSON 字符串，失败返回原字符串 */
function tryParseJson(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/** 格式化大小 */
function formatSize(len: number): string {
  if (len < 1024) return `${len}B`;
  if (len < 1024 * 1024) return `${(len / 1024).toFixed(1)}KB`;
  return `${(len / (1024 * 1024)).toFixed(1)}MB`;
}

/** 列表表格列 */
const listColumns = [
  {
    title: '',
    key: 'status',
    width: 60,
    render(row: Api.Trace.TraceSummary) {
      return row.hasError
        ? h(NTag, { type: 'error', size: 'small', round: true, bordered: false }, () => '输出')
        : h(NTag, { type: 'success', size: 'small', round: true, bordered: false }, () => '输入');
    }
  },
  {
    title: '用户输入',
    key: 'firstUserInput',
    minWidth: 240,
    ellipsis: { tooltip: true },
    render(row: Api.Trace.TraceSummary) {
      return row.firstUserInput || '-';
    }
  },
  {
    title: '开始时间',
    key: 'createdAt',
    width: 170,
    render(row: Api.Trace.TraceSummary) {
      return row.createdAt;
    }
  },
  {
    title: '耗时',
    key: 'totalLatencyMs',
    width: 90,
    render(row: Api.Trace.TraceSummary) {
      const ms = row.totalLatencyMs;
      const text = formatLatency(ms);
      const color = ms > 30000 ? '#ef4444' : ms > 10000 ? '#f59e0b' : '#22c55e';
      return h(NTag, { type: 'info', size: 'small', round: true, bordered: false, style: { color } }, () => text);
    }
  },
  {
    title: 'TraceID',
    key: 'traceId',
    width: 200,
    ellipsis: { tooltip: true },
    render(row: Api.Trace.TraceSummary) {
      return h(NButton, {
        text: true,
        type: 'primary',
        size: 'tiny',
        onClick: () => openDetail(row.traceId)
      }, () => row.traceId);
    }
  },
  {
    title: '用户ID',
    key: 'userId',
    width: 110,
    ellipsis: { tooltip: true }
  },
  {
    title: 'Tokens',
    key: 'totalTokens',
    width: 100,
    render(row: Api.Trace.TraceSummary) {
      return formatTokens(row.totalInputTokens + row.totalOutputTokens);
    }
  },
  {
    title: '错误来源',
    key: 'errorMessage',
    width: 140,
    ellipsis: { tooltip: true },
    render(row: Api.Trace.TraceSummary) {
      return row.errorMessage || '-';
    }
  }
];

// ── 内联子组件：JSON 树节点 ────────────────────────────────────
/** JSON 树形展示组件（递归渲染 input/output payload） */
const JsonTreeNode = defineComponent({
  name: 'JsonTreeNode',
  props: {
    label: { type: String, required: true },
    value: { type: [String, Object, Array, Number, Boolean, null] as PropType<any>, default: undefined },
    expanded: { type: Boolean, default: false },
    badge: { type: String, default: '' }
  },
  setup(props, { slots }) {
    const isExpanded = ref(props.expanded);
    const hasChildren = computed(() => {
      const v = props.value;
      return v !== null && typeof v === 'object' && (Array.isArray(v) ? v.length > 0 : Object.keys(v).length > 0);
    });

    function toggle() {
      if (hasChildren.value) isExpanded.value = !isExpanded.value;
    }

    /** 渲染值 */
    function renderValue(val: any): VNode {
      if (val === null || val === undefined) return h('span', { class: 'text-gray-500' }, 'null');
      if (typeof val === 'string') {
        // 长字符串截断显示
        if (val.length > 200) {
          return h('span', [
            h('span', { class: 'text-green-400' }, `"${val.slice(0, 200)}"`),
            h('span', { class: 'text-gray-500 ml-1' }, `... [${val.length} chars]`)
          ]);
        }
        return h('span', { class: 'text-green-400' }, `"${val}"`);
      }
      if (typeof val === 'number') return h('span', { class: 'text-orange-400' }, String(val));
      if (typeof val === 'boolean') return h('span', { class: 'text-purple-400' }, String(val));
      return h('span', { class: 'text-gray-300' }, String(val));
    }

    return () => {
      const children: VNode[] = [];

      // 展开/折叠箭头
      if (hasChildren.value) {
        children.push(h('span', {
          class: 'mr-1 cursor-pointer select-none text-gray-400 inline-block w-4 text-center',
          onClick: toggle
        }, isExpanded.value ? '▾' : '▸'));
      } else {
        children.push(h('span', { class: 'mr-1 w-4 inline-block' }, ''));
      }

      // Label
      children.push(h('span', {
        class: hasChildren.value ? 'cursor-pointer text-blue-300 hover:text-blue-200 font-medium' : 'text-gray-300',
        onClick: toggle
      }, props.label));

      // Badge
      if (props.badge) {
        children.push(h(NTag, { size: 'tiny', type: 'info', round: true, bordered: false, class: 'ml-2' }, () => props.badge));
      }

      // 值（非对象类型直接显示）
      if (!hasChildren.value && props.value !== undefined) {
        children.push(h('span', { class: 'ml-2' }, ': '), renderValue(props.value));
      }

      // 展开后的子项
      if (isExpanded.value && hasChildren.value) {
        const val = props.value;
        const childNodes: VNode[] = [];
        if (Array.isArray(val)) {
          val.forEach((item, i) => {
            childNodes.push(h(JsonTreeNode, {
              label: `[${i}]`,
              value: item,
              key: i
            }));
          });
        } else if (typeof val === 'object') {
          Object.entries(val).forEach(([k, v]) => {
            childNodes.push(h(JsonTreeNode, {
              label: k,
              value: v,
              key: k
            }));
          });
        }
        children.push(h('div', { class: 'ml-5 border-l border-gray-600 pl-2 mt-0.5' }, childNodes));
      }

      // 渲染父组件传入的插槽（子 JsonTreeNode / 任意 vnode）
      const slotContent = slots.default?.();
      if (slotContent) {
        const wrapperClass = (hasChildren.value && isExpanded.value)
          ? 'ml-5 border-l border-gray-600 pl-2 mt-0.5'
          : 'mt-1';
        children.push(h('div', { class: wrapperClass }, slotContent));
      }

      return h('div', { class: 'py-0.5 hover:bg-white/5 rounded' }, children);
    };
  }
});
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- ═══════════════════════════════════════════════════ -->
    <!-- 模式 A：列表页 -->
    <!-- ═══════════════════════════════════════════════════ -->
    <template v-if="mode === 'list'">
      <!-- 左侧筛选 + 右侧内容区 -->
      <div class="flex h-full gap-3 overflow-hidden">
        <!-- 左侧筛选面板 -->
        <div class="w-220px flex-shrink-0 overflow-y-auto rounded-lg bg-white p-4 shadow-sm dark:bg-[#18181b]">
          <div class="mb-3 flex items-center justify-between">
            <span class="text-14px font-bold">筛选器</span>
            <div class="flex gap-1">
              <NButton size="tiny" @click="resetFilters">重置</NButton>
              <NButton size="tiny" type="primary" @click="handleSearch">搜索</NButton>
            </div>
          </div>

          <!-- ① 调用状态（默认全选 OK + ERROR） -->
          <div class="mb-4 pl-3 border-l-2 border-blue-500">
            <div class="mb-1 text-12px text-gray-600">调用状态</div>
            <NSpace :size="12">
              <NCheckbox :checked="statusFilters.ok" @update:checked="(v: boolean) => statusFilters.ok = v">
                OK
              </NCheckbox>
              <NCheckbox :checked="statusFilters.error" @update:checked="(v: boolean) => statusFilters.error = v">
                ERROR
              </NCheckbox>
            </NSpace>
          </div>

          <!-- ② 调用延时（最小/最大 + ms） -->
          <div class="mb-4 pl-3 border-l-2 border-blue-500">
            <div class="mb-1 text-12px text-gray-600">调用延时</div>
            <div class="flex w-full items-center gap-1">
              <NInput v-model:value="filters.minLatency" placeholder="最小耗时" size="small" class="flex-1 min-w-0" />
              <span class="shrink-0 text-12px text-gray-400">~</span>
              <NInput v-model:value="filters.maxLatency" placeholder="最大耗时" size="small" class="flex-1 min-w-0" />
              <span class="shrink-0 text-12px text-gray-400">ms</span>
            </div>
          </div>

          <!-- ③ 对话 ID (TraceID) -->
          <div class="mb-4 pl-3 border-l-2 border-blue-500">
            <div class="mb-1 text-12px text-gray-600">
              对话 ID <span class="text-gray-400">(TraceID)</span>
            </div>
            <NInput v-model:value="filters.traceId" placeholder="请输入" size="small" clearable />
          </div>

          <!-- ④ 会话 ID (SessionID) -->
          <div class="mb-4 pl-3 border-l-2 border-blue-500">
            <div class="mb-1 text-12px text-gray-600">
              会话 ID <span class="text-gray-400">(SessionID)</span>
            </div>
            <NInput v-model:value="filters.sessionId" placeholder="请输入" size="small" clearable />
          </div>

          <!-- ⑤ 用户 ID（默认填入当前登录用户） -->
          <div class="mb-4 pl-3 border-l-2 border-blue-500">
            <div class="mb-1 text-12px text-gray-600">用户 ID</div>
            <NInput
              v-model:value="filters.userId"
              :placeholder="currentUserId || '请输入用户 ID'"
              size="small"
              clearable
            />
          </div>

          <!-- ⑥ 自定义标签（key + 操作符 + value，可多条） -->
          <div class="mb-4 pl-3 border-l-2 border-blue-500">
            <div class="mb-1 flex items-center justify-between text-12px text-gray-600">
              <span>自定义标签</span>
              <NSpace :size="12">
                <NButton text size="tiny" type="error" @click="clearCustomTags">删除</NButton>
                <NButton text size="tiny" type="primary" @click="addCustomTag">添加</NButton>
              </NSpace>
            </div>
            <div v-for="(tag, idx) in filters.customTags" :key="idx" class="mb-2 flex w-full items-center gap-1">
              <NInput v-model:value="tag.key" placeholder="key" size="small" class="flex-1 min-w-0" />
              <NSelect v-model:value="tag.op" :options="operatorOptions" size="small" class="w-20 shrink-0" />
              <NInput v-model:value="tag.value" placeholder="value" size="small" class="flex-1 min-w-0" />
            </div>
          </div>

          <!-- ⑦ 时间范围（保留） -->
          <div class="mb-4 pl-3 border-l-2 border-blue-500">
            <div class="mb-1 text-12px text-gray-600">时间范围</div>
            <NDatePicker
              v-model:value="dateRange"
              type="datetimerange"
              clearable
              class="w-full!"
              size="small"
            />
          </div>
        </div>

        <!-- 右侧：统计 + 表格 -->
        <div class="min-w-0 flex-1 flex flex-col overflow-hidden rounded-lg bg-white shadow-sm dark:bg-[#18181b]">
          <!-- 顶部：日期范围 + 统计卡片 -->
          <div class="border-b border-gray-100 px-5 py-3 dark:border-gray-700">
            <div class="mb-3 flex items-center justify-between">
              <NDatePicker
                v-model:value="dateRange"
                type="datetimerange"
                clearable
                size="small"
                class="w-320px"
              />
              <NSpace align="center" :size="12">
                <span class="text-12px text-gray-400">自刷新</span>
                <NSwitch size="small" />
              </NSpace>
            </div>

            <!-- 统计卡片区 -->
            <div v-if="stats" class="grid grid-cols-5 gap-4">
              <div v-for="item in [
                { label: '请求数', value: stats.totalCount, unit: '', color: '#2563eb' },
                { label: '错误数', value: stats.errorCount, unit: '', color: stats.errorCount > 0 ? '#dc2626' : '#16a34a' },
                { label: 'P50延迟', value: stats.p50Latency, unit: '', color: '#7c3aed' },
                { label: 'P99延迟', value: stats.p99Latency, unit: '', color: '#ea580c' },
                { label: 'Tokens', value: stats.totalTokens, unit: '', color: '#0891b2' }
              ]" :key="item.label" class="rounded-md bg-gray-50 px-4 py-2.5 dark:bg-[#27272a]">
                <div class="text-12px text-gray-400">{{ item.label }}</div>
                <div class="mt-0.5 text-20px font-bold" :style="{ color: item.color }">{{ item.value }}{{ item.unit }}</div>
                <div class="mt-0.5 text-11px text-gray-300 cursor-pointer hover:text-blue-500">+ 对比</div>
              </div>
            </div>
          </div>

          <!-- 表格 -->
          <div class="flex-1 overflow-auto p-4">
            <NSpin :show="loading" class="h-full">
              <NDataTable
                :columns="listColumns"
                :data="listData"
                :pagination="false"
                :row-key="row => row.traceId"
                striped
                size="small"
                :scroll-x="1200"
                @update:page="onPageChange"
              >
                <template #empty>
                  <NEmpty v-if="!loading" description="暂无 Trace 数据" class="py-16" />
                </template>
              </NDataTable>

              <!-- 分页 -->
              <div v-if="total > 0" class="mt-3 flex justify-end">
                <NPagination
                  v-model:page="pagination.page"
                  :page-count="Math.ceil(total / pagination.pageSize)"
                  :page-size="pagination.pageSize"
                  size="small"
                  @update:page="onPageChange"
                />
              </div>
            </NSpin>
          </div>
        </div>
      </div>
    </template>

    <!-- ═══════════════════════════════════════════════════ -->
    <!-- 模式 B：详情页 -->
    <!-- ═══════════════════════════════════════════════════ -->
    <template v-else-if="mode === 'detail'">
      <div class="h-full flex flex-col overflow-hidden rounded-lg bg-white shadow-sm dark:bg-[#18181b]">
        <!-- 详情头部栏 -->
        <div v-if="detailSummary" class="border-b border-gray-100 px-5 py-3 dark:border-gray-700">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-4">
              <NButton text size="small" @click="backToList">
                <template #icon><icon-ant-design-arrow-left-outlined /></template>
                返回
              </NButton>
              <span class="text-14px font-bold">Tracer</span>
              <NDivider vertical />
              <span class="text-13px text-gray-500">TraceID: {{ detailSummary.traceId }}</span>
              <NDivider vertical />
              <span class="text-13px text-gray-500">SessionID: {{ detailSummary.sessionId }}</span>
              <NDivider vertical />
              <NTag :type="detailSummary.status === 'SUCCESS' ? 'success' : 'error'" size="small" round>
                {{ detailSummary.status }}
              </NTag>
              <NDivider vertical />
              <span class="text-13px text-gray-400">Span: -</span>
              <NDivider vertical />
              <span class="text-13px text-gray-400">用户: {{ detailSummary.userId }}</span>
            </div>
            <NButton size="small" type="primary" @click="copyText(detailSummary.traceId)">
              <template #icon><icon-ant-design-copy-outlined /></template>
              复制 TraceID
            </NButton>
          </div>

          <!-- 统计行 -->
          <div class="mt-2 grid grid-cols-9 gap-3 text-12px">
            <div><span class="text-gray-400">开始时间:</span> {{ detailSummary.startTime }}</div>
            <div><span class="text-gray-400">耗时:</span> {{ formatLatency(detailSummary.durationMs) }}</div>
            <div><span class="text-gray-400">span数:</span> {{ detailSummary.eventCount }}</div>
            <div><span class="text-gray-400">LLM调用次数:</span> {{ detailSummary.llmCallCount }}</div>
            <div><span class="text-gray-400">输入Tokens:</span> {{ formatTokens(detailSummary.inputTokens) }}</div>
            <div><span class="text-gray-400">输出Tokens:</span> {{ formatTokens(detailSummary.outputTokens) }}</div>
            <div><span class="text-gray-400">总Token:</span> {{ formatTokens(detailSummary.totalTokens) }}</div>
            <div><span class="text-gray-400">总Token:</span> {{ formatTokens(detailSummary.totalTokens) }}</div>
          </div>
        </div>

        <!-- 事件列表 + 详情面板 -->
        <div class="flex min-h-0 flex-1 overflow-hidden">
          <!-- 左侧：事件列表 -->
          <div class="w-280px flex-shrink-0 border-r border-gray-100 overflow-y-auto dark:border-gray-700">
            <div class="sticky top-0 z-10 border-b border-gray-100 bg-white p-3 dark:border-gray-700 dark:bg-[#18181b]">
              <NInput placeholder="请输入消息内容或com关键词" size="small" clearable>
                <template #prefix><icon-ant-design-search-outlined class="text-gray-400" /></template>
              </NInput>
            </div>

            <NSpin :show="detailLoading" class="h-full">
              <div v-if="!detailLoading && detailEvents.length === 0" class="px-4 py-6 text-center text-13px text-gray-400">
                暂无事件数据
              </div>
              <div
                v-for="(event, idx) in detailEvents"
                :key="idx"
                class="cursor-pointer border-b border-gray-50 px-3 py-2.5 transition-colors hover:bg-blue-50 dark:border-gray-800 dark:hover:bg-[#1e1e22]"
                :class="{ 'bg-blue-50! dark:bg-[#1e1e22]!': selectedEventIndex === idx }"
                @click="selectedEventIndex = idx"
              >
                <div class="flex items-center justify-between">
                  <NTag :type="eventTypeTag(event.eventType).type" size="tiny" round bordered>
                    {{ eventTypeTag(event.eventType).label }}
                  </NTag>
                  <span class="text-11px text-gray-400">{{ event.latencyMs > 0 ? formatLatency(event.latencyMs) : '-' }}</span>
                </div>
                <div class="mt-1 truncate text-12px text-gray-600 dark:text-gray-300">
                  {{ event.inputPayload || event.model || event.eventType.replace('_', ' ') }}
                </div>
                <div class="mt-0.5 flex justify-between text-10px text-gray-400">
                  <span>{{ event.inputPayload ? formatSize(event.inputPayload.length) : '-' }}</span>
                  <span>{{ event.outputPayload ? formatSize(event.outputPayload.length) : '-' }}</span>
                  <span>{{ dayjs(event.createdAt).format('HH:mm:ss') }}</span>
                </div>
              </div>
            </NSpin>
          </div>

          <!-- 右侧：输入/输出详情 -->
          <div class="min-w-0 flex-1 flex flex-col overflow-hidden">
            <div v-if="currentEvent" class="flex h-full flex-col">
              <!-- 面板头部 -->
              <div class="border-b border-gray-100 px-4 py-2.5 flex items-center justify-between dark:border-gray-700">
                <div class="flex items-center gap-2">
                  <NTag :type="eventTypeTag(currentEvent.eventType).type" size="small">
                    {{ eventTypeTag(currentEvent.eventType).label }}/{{ currentEvent.phase || '-' }}
                  </NTag>
                  <span class="text-12px text-gray-400">
                    stepOrder: {{ currentEvent.stepOrder }} |
                    耗时: {{ formatLatency(currentEvent.latencyMs) }} |
                    Tokens: {{ formatTokens(currentEvent.totalTokens) }}
                  </span>
                </div>
                <NSpace :size="8">
                  <NButton size="tiny" @click="copyText(JSON.stringify(currentEvent, null, 2))">
                    <template #icon><icon-ant-design-copy-outlined /></template>
                    复制/编辑
                  </NButton>
                  <NButton size="tiny">全屏展开</NButton>
                </NSpace>
              </div>

              <!-- 内容区域：JSON 树形展示 -->
              <div class="flex-1 overflow-auto p-4">
                <div class="rounded-lg bg-[#1e1e1e] p-4 font-mono text-13px text-gray-200">
                  <!-- 输入部分 -->
                  <JsonTreeNode
                    label="输入/输出"
                    :expanded="true"
                    :badge="`@op:${currentEvent.stepOrder}`"
                  >
                    <!-- inputPayload -->
                    <JsonTreeNode
                      v-if="currentEvent.inputPayload"
                      label="input"
                      :value="tryParseJson(currentEvent.inputPayload)"
                    />
                    <!-- outputPayload -->
                    <JsonTreeNode
                      v-if="currentEvent.outputPayload"
                      label="output"
                      :value="tryParseJson(currentEvent.outputPayload)"
                    />
                    <!-- 元数据 -->
                    <JsonTreeNode label="meta" :expanded="false">
                      <div class="ml-4 space-y-1 text-11px text-gray-400">
                        <div>model: {{ currentEvent.model || '-' }}</div>
                        <div>phase: {{ currentEvent.phase || '-' }}</div>
                        <div>latencyMs: {{ currentEvent.latencyMs }}</div>
                        <div>tokens: {{ currentEvent.inputTokens }} / {{ currentEvent.outputTokens }} / {{ currentEvent.totalTokens }}</div>
                        <div>success: {{ currentEvent.success }}</div>
                      </div>
                    </JsonTreeNode>
                  </JsonTreeNode>
                </div>
              </div>
            </div>

            <NEmpty v-else description="选择左侧事件查看详情" class="m-auto" />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss"></style>
