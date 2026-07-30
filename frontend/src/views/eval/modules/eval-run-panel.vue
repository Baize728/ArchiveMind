<script setup lang="ts">
import { startEvaluation, getEvalTask } from '@/service/api/eval';

defineOptions({ name: 'EvalRunPanel' });

const emit = defineEmits<{
  (e: 'taskStarted', taskId: string): void;
}>();

// ── 评测表单 ──────────────────────────────────────
const form = reactive({
  mode: 'GOLD_SET' as Api.Eval.Mode,
  startAt: '',
  endAt: '',
  includeJudge: true,
  limit: 1000
});

const modeOptions = [
  { label: 'Gold Set 回归（expected_answer 非空的样本）', value: 'GOLD_SET' },
  { label: 'BadCase 回归（点踩回流已补标注的样本）', value: 'BADCASE_ONLY' },
  { label: '时间范围评测（指定时间段所有 trace）', value: 'TIME_RANGE' }
];

const dateRange = ref<[number, number] | null>(null);

watchEffect(() => {
  if (dateRange.value) {
    form.startAt = new Date(dateRange.value[0]).toISOString();
    form.endAt = new Date(dateRange.value[1]).toISOString();
  } else {
    form.startAt = '';
    form.endAt = '';
  }
});

// ── 任务状态轮询 ──────────────────────────────────
const currentTaskId = ref('');
const taskStatus = ref<Api.Eval.TaskStatusResponse | null>(null);
const polling = ref(false);
let pollTimer: ReturnType<typeof setInterval> | null = null;

async function startEval() {
  if (form.mode === 'TIME_RANGE' && (!form.startAt || !form.endAt)) {
    window.$message?.warning('请选择时间范围');
    return;
  }

  const { data, error } = await startEvaluation({
    mode: form.mode,
    startAt: form.mode === 'TIME_RANGE' ? form.startAt : undefined,
    endAt: form.mode === 'TIME_RANGE' ? form.endAt : undefined,
    includeJudge: form.includeJudge,
    limit: form.limit
  });

  if (error || !data?.taskId) {
    window.$message?.error('启动评测失败');
    return;
  }

  currentTaskId.value = data.taskId;
  taskStatus.value = null;
  polling.value = true;
  window.$message?.success(`评测已启动: ${data.taskId}`);

  // 开始轮询
  startPolling(data.taskId);
}

function startPolling(taskId: string) {
  stopPolling();
  pollTimer = setInterval(async () => {
    const { data } = await getEvalTask(taskId);
    if (data) {
      taskStatus.value = data;
      if (data.status === 'COMPLETED' || data.status === 'FAILED') {
        stopPolling();
        if (data.status === 'COMPLETED') {
          window.$message?.success('评测完成');
          emit('taskStarted', taskId);
        } else {
          window.$message?.error('评测失败: ' + (data.errorMessage || '未知错误'));
        }
      }
    }
  }, 3000);
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  polling.value = false;
}

onUnmounted(stopPolling);

// ── 进度展示 ──────────────────────────────────────
const progressPercent = computed(() => {
  if (!taskStatus.value) return 0;
  const { total, done } = taskStatus.value.progress;
  return total > 0 ? Math.round((done / total) * 100) : 0;
});
</script>

<template>
  <div class="flex flex-col gap-6">
    <!-- 评测配置 -->
    <NCard title="评测配置" size="small">
      <NForm label-placement="top">
        <NFormItem label="评测模式">
          <NRadioGroup v-model:value="form.mode">
            <NRadio
              v-for="opt in modeOptions"
              :key="opt.value"
              :value="opt.value"
            >
              {{ opt.label }}
            </NRadio>
          </NRadioGroup>
        </NFormItem>

        <NFormItem v-if="form.mode === 'TIME_RANGE'" label="时间范围">
          <NDatePicker
            v-model:value="dateRange"
            type="datetimerange"
            clearable
            class="w-full"
          />
        </NFormItem>

        <NFormItem label="LLM Judge">
          <NSwitch v-model:value="form.includeJudge">
            <template #checked>启用</template>
            <template #unchecked>禁用</template>
          </NSwitch>
          <span class="ml-2 text-12px color-gray-400">启用后对每条 trace 调用 Judge LLM 评分（faithfulness/relevance/completeness/naturalness）</span>
        </NFormItem>

        <NFormItem v-if="form.mode === 'TIME_RANGE'" label="最大 trace 数">
          <NInputNumber v-model:value="form.limit" :min="1" :max="5000" />
        </NFormItem>

        <div class="flex justify-end">
          <NButton
            type="primary"
            :loading="polling"
            :disabled="polling"
            @click="startEval"
          >
            <template #icon><icon-mdi:play /></template>
            启动评测
          </NButton>
        </div>
      </NForm>
    </NCard>

    <!-- 任务状态 -->
    <NCard v-if="taskStatus" title="任务状态" size="small">
      <div class="flex flex-col gap-4">
        <div class="flex items-center gap-4">
          <span class="text-14px font-600">Task ID:</span>
          <NText code>{{ taskStatus.taskId }}</NText>
        </div>

        <div class="flex items-center gap-3">
          <span class="text-14px font-600">状态:</span>
          <NTag
            :type="taskStatus.status === 'COMPLETED' ? 'success' : taskStatus.status === 'FAILED' ? 'error' : 'info'"
            size="medium"
          >
            {{ taskStatus.status }}
          </NTag>
        </div>

        <div v-if="taskStatus.status === 'RUNNING'">
          <div class="mb-1 flex items-center justify-between text-13px">
            <span>进度: {{ taskStatus.progress.done }} / {{ taskStatus.progress.total }}</span>
            <span>{{ progressPercent }}%</span>
          </div>
          <NProgress
            type="line"
            :percentage="progressPercent"
            :show-indicator="false"
          />
        </div>

        <div v-if="taskStatus.status === 'FAILED'" class="text-red-500">
          {{ taskStatus.errorMessage }}
        </div>
      </div>
    </NCard>
  </div>
</template>
