<script setup lang="ts">
import { getEvalTask } from '@/service/api/eval';
import { NTag, NButton } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';

defineOptions({ name: 'EvalReportPanel' });

const props = defineProps<{ taskId: string }>();

const loading = ref(false);
const report = ref<Api.Eval.EvalReport | null>(null);
const taskStatus = ref<string>('');
const showDetails = ref(false);

// 加载任务结果
async function loadReport() {
  if (!props.taskId) return;
  loading.value = true;
  try {
    const { data } = await getEvalTask(props.taskId);
    if (data) {
      taskStatus.value = data.status;
      if (data.result) {
        report.value = data.result;
      }
    }
  } finally {
    loading.value = false;
  }
}

watch(() => props.taskId, (newId) => {
  if (newId) {
    report.value = null;
    taskStatus.value = '';
    loadReport();
  }
}, { immediate: true });

// 手动刷新
function refresh() {
  loadReport();
}

// ── 指标表格列 ──────────────────────────────────
const metricColumns = computed<DataTableColumns<{ name: string; stat: Api.Eval.MetricStat }>>(() => [
  {
    title: '指标',
    key: 'name',
    width: 220,
    render: row => {
      const labels: Record<string, string> = {
        intentAccuracy: '意图准确率',
        slotAccuracy: '槽位准确率',
        clarifyNecessityAccuracy: '澄清必要性准确率',
        tokenCost: 'Token 成本',
        tokenCostScore: 'Token 成本分',
        latencyMs: '延迟(ms)',
        latencyScore: '延迟分',
        fallbackRate: 'Fallback 率',
        fallbackScore: 'Fallback 分',
        hallucinationControl: '幻觉控制',
        multiTurnConsistency: '多轮一致性'
      };
      return labels[row.name] || row.name;
    }
  },
  {
    title: '平均值',
    key: 'value',
    width: 120,
    render: row => row.stat.value != null
      ? (row.name.includes('Cost') || row.name === 'tokenCost' || row.name === 'latencyMs'
        ? row.stat.value.toFixed(0)
        : (row.stat.value * 100).toFixed(1) + '%')
      : '-'
  },
  {
    title: '样本数 (n)',
    key: 'n',
    width: 100,
    render: row => `${row.stat.n}`
  },
  {
    title: '范围',
    key: 'scope',
    render: row => row.stat.scope
      ? h(NTag, { size: 'small', type: 'warning' }, { default: () => row.stat.scope })
      : '-'
  }
]);

const metricList = computed(() => {
  if (!report.value?.metricAverages) return [];
  return Object.entries(report.value.metricAverages).map(([name, stat]) => ({
    name,
    stat
  }));
});

// ── 失败 trace 列 ──────────────────────────────────
const failedColumns: DataTableColumns<Api.Eval.FailedTrace> = [
  { title: 'Trace ID', key: 'traceId', width: 200, ellipsis: { tooltip: true } },
  {
    title: '分数',
    key: 'score',
    width: 100,
    render: row => row.score.toFixed(1)
  },
  {
    title: '失败原因',
    key: 'failReason',
    render: row => {
      const labels: Record<string, string> = {
        'totalScore<80': '总分低于80',
        'hallucination': '存在幻觉（faithfulness=0）',
        'degradedFromBaseline': '相对基线退化'
      };
      return labels[row.failReason] || row.failReason;
    }
  }
];

// ── 明细列 ──────────────────────────────────────
const detailColumns = computed<DataTableColumns<Api.Eval.TraceEvalResult>>(() => [
  { title: 'Trace ID', key: 'traceId', width: 180, ellipsis: { tooltip: true } },
  {
    title: '总分',
    key: 'score',
    width: 80,
    render: row => row.score != null ? row.score.toFixed(1) : '-'
  },
  {
    title: '规则分',
    key: 'ruleScore',
    width: 80,
    render: row => row.ruleScore != null ? row.ruleScore.toFixed(1) : '-'
  },
  {
    title: 'Judge 分',
    key: 'judgeScore',
    width: 80,
    render: row => row.judgeScore != null ? row.judgeScore.toFixed(1) : '-'
  },
  {
    title: '反馈分',
    key: 'feedbackScore',
    width: 80,
    render: row => row.feedbackScore != null ? row.feedbackScore.toFixed(1) : '-'
  },
  {
    title: '意图',
    key: 'intent',
    width: 120,
    render: row => (row.detail as Record<string, unknown>).predictedIntent as string || '-'
  }
]);

// ── 分数颜色 ──────────────────────────────────────
function scoreColor(score: number | null): string {
  if (score == null) return 'default';
  if (score >= 80) return 'success';
  if (score >= 60) return 'warning';
  return 'error';
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <!-- 无任务 -->
    <NEmpty v-if="!taskId" description="请先在「评测执行」tab 启动评测" />

    <template v-else>
      <!-- 刷新按钮 -->
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-3">
          <NText class="text-14px font-600">Task: {{ taskId }}</NText>
          <NTag v-if="taskStatus" :type="taskStatus === 'COMPLETED' ? 'success' : 'info'" size="small">
            {{ taskStatus }}
          </NTag>
        </div>
        <NButton quaternary size="small" :loading="loading" @click="refresh">
          <template #icon><icon-mdi:refresh /></template>
          刷新
        </NButton>
      </div>

      <!-- 报告内容 -->
      <template v-if="report">
        <!-- 汇总卡片 -->
        <div class="grid grid-cols-2 gap-4 md:grid-cols-4">
          <NCard size="small" class="text-center">
            <NStatistic label="总 Trace 数" :value="report.totalTraces" />
          </NCard>
          <NCard size="small" class="text-center">
            <NStatistic label="标注覆盖率" :value="(report.labelCoverage * 100).toFixed(0) + '%'" />
          </NCard>
          <NCard size="small" class="text-center">
            <NStatistic label="平均总分">
              <template #default>
                <span :class="(report.averageScore ?? 0) >= 80 ? 'color-green' : (report.averageScore ?? 0) >= 60 ? 'color-orange' : 'color-red'">
                  {{ report.averageScore != null ? report.averageScore.toFixed(1) : '-' }}
                </span>
              </template>
            </NStatistic>
          </NCard>
          <NCard size="small" class="text-center">
            <NStatistic label="回归结果">
              <template #default>
                <NTag :type="report.pass ? 'success' : 'error'" size="large">
                  {{ report.pass ? '通过' : '不通过' }}
                </NTag>
              </template>
            </NStatistic>
          </NCard>
        </div>

        <!-- 失败原因 -->
        <NAlert v-if="!report.pass && report.passReason" type="error" :show-icon="true">
          回归失败: {{ report.passReason }}
        </NAlert>

        <!-- 指标表 -->
        <NCard title="指标平均" size="small">
          <NDataTable
            :columns="metricColumns"
            :data="metricList"
            :bordered="false"
            size="small"
          />
        </NCard>

        <!-- 失败 trace -->
        <NCard v-if="report.failedTraces.length > 0" title="失败 Trace" size="small">
          <NDataTable
            :columns="failedColumns"
            :data="report.failedTraces"
            :bordered="false"
            size="small"
            :max-height="300"
          />
        </NCard>

        <!-- 明细展开 -->
        <NCard size="small">
          <template #header>
            <div class="flex items-center gap-2">
              <span>评测明细</span>
              <NButton quaternary size="tiny" @click="showDetails = !showDetails">
                {{ showDetails ? '收起' : '展开' }}
                <template #icon>
                  <icon-mdi:chevron-down v-if="!showDetails" />
                  <icon-mdi:chevron-up v-else />
                </template>
              </NButton>
            </div>
          </template>
          <NDataTable
            v-if="showDetails"
            :columns="detailColumns"
            :data="report.details"
            :bordered="false"
            size="small"
            :max-height="400"
            :pagination="{ pageSize: 20 }"
          />
        </NCard>
      </template>

      <!-- 加载中 -->
      <NSpin v-else-if="loading" class="py-8" />
    </template>
  </div>
</template>
