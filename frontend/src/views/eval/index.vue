<script setup lang="ts">
import BadCasePanel from './modules/badcase-panel.vue';
import EvalRunPanel from './modules/eval-run-panel.vue';
import EvalReportPanel from './modules/eval-report-panel.vue';

defineOptions({ name: 'Eval' });

const activeTab = ref<'badcase' | 'run' | 'report'>('badcase');

// report tab 需要接收 run 传来的 taskId
const reportTaskId = ref('');
</script>

<template>
  <div class="h-full p-4">
    <NTabs v-model:value="activeTab" type="line" animated>
      <NTabPane name="badcase" tab="BadCase 标注">
        <BadCasePanel v-if="activeTab === 'badcase'" />
      </NTabPane>
      <NTabPane name="run" tab="评测执行">
        <EvalRunPanel
          v-if="activeTab === 'run'"
          @task-started="(taskId: string) => { reportTaskId = taskId; activeTab = 'report'; }"
        />
      </NTabPane>
      <NTabPane name="report" tab="评测报告">
        <EvalReportPanel
          v-if="activeTab === 'report'"
          :task-id="reportTaskId"
        />
      </NTabPane>
    </NTabs>
  </div>
</template>
