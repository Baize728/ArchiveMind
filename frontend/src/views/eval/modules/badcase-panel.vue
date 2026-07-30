<script setup lang="ts">
import { fetchEvalSamples, saveEvalSample } from '@/service/api/eval';
import { NTag, NButton } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';

defineOptions({ name: 'BadCasePanel' });

// ── 列表状态 ──────────────────────────────────────
const loading = ref(false);
const listData = ref<Api.Eval.SampleRow[]>([]);
const total = ref(0);
const pagination = reactive({ page: 1, pageSize: 20 });

// 筛选
const sourceFilter = ref<'BADCASE_BACKFLOW' | 'MANUAL'>('BADCASE_BACKFLOW');
const statusFilter = ref<'pending' | 'labeled'>('pending');

async function loadList() {
  loading.value = true;
  try {
    const { data, error } = await fetchEvalSamples({
      source: sourceFilter.value,
      status: statusFilter.value,
      page: pagination.page,
      size: pagination.pageSize
    });
    if (error) {
      window.$message?.error('加载失败');
      return;
    }
    if (data) {
      listData.value = data.list || [];
      total.value = data.total || 0;
    }
  } finally {
    loading.value = false;
  }
}

function handlePageChange(page: number) {
  pagination.page = page;
  loadList();
}

function handleFilterChange() {
  pagination.page = 1;
  loadList();
}

onMounted(loadList);

// ── 标注弹窗 ──────────────────────────────────────
const labelModalVisible = ref(false);
const labelForm = reactive({
  traceId: '',
  expectedIntent: '',
  expectedSlots: '',
  expectedClarifyAction: '',
  expectedAnswer: '',
  labelNote: ''
});
const labelLoading = ref(false);

function openLabelModal(row: Api.Eval.SampleRow) {
  labelForm.traceId = row.traceId;
  labelForm.expectedIntent = row.expectedIntent || '';
  labelForm.expectedSlots = row.expectedSlots || '';
  labelForm.expectedClarifyAction = row.expectedClarifyAction || '';
  labelForm.expectedAnswer = row.expectedAnswer || '';
  labelForm.labelNote = row.labelNote || '';
  labelModalVisible.value = true;
}

async function submitLabel() {
  if (!labelForm.expectedAnswer.trim()) {
    window.$message?.warning('期望答案不能为空');
    return;
  }
  labelLoading.value = true;
  try {
    const { error } = await saveEvalSample({
      traceId: labelForm.traceId,
      expectedIntent: labelForm.expectedIntent || undefined,
      expectedSlots: labelForm.expectedSlots || undefined,
      expectedClarifyAction: labelForm.expectedClarifyAction || undefined,
      expectedAnswer: labelForm.expectedAnswer,
      labelNote: labelForm.labelNote || undefined
    });
    if (error) {
      window.$message?.error('保存失败');
      return;
    }
    window.$message?.success('标注保存成功');
    labelModalVisible.value = false;
    loadList();
  } finally {
    labelLoading.value = false;
  }
}

// ── 表格列 ──────────────────────────────────────
const columns = computed<DataTableColumns<Api.Eval.SampleRow>>(() => [
  {
    title: 'Trace ID',
    key: 'traceId',
    width: 200,
    ellipsis: { tooltip: true }
  },
  {
    title: '来源',
    key: 'source',
    width: 140,
    render: row => h(NTag, {
      type: row.source === 'BADCASE_BACKFLOW' ? 'warning' : 'info',
      size: 'small'
    }, { default: () => row.source === 'BADCASE_BACKFLOW' ? '点踩回流' : '人工标注' })
  },
  {
    title: '标注状态',
    key: 'status',
    width: 100,
    render: row => h(NTag, {
      type: row.expectedAnswer ? 'success' : 'warning',
      size: 'small'
    }, { default: () => row.expectedAnswer ? '已标注' : '待标注' })
  },
  {
    title: '期望答案',
    key: 'expectedAnswer',
    ellipsis: { tooltip: true },
    render: row => row.expectedAnswer || h('span', { class: 'text-gray-400' }, '未填写')
  },
  {
    title: '标注备注',
    key: 'labelNote',
    width: 200,
    ellipsis: { tooltip: true },
    render: row => row.labelNote || '-'
  },
  {
    title: '操作',
    key: 'action',
    width: 120,
    fixed: 'right',
    render: row => h(NButton, {
      size: 'small',
      type: 'primary',
      quaternary: true,
      onClick: () => openLabelModal(row)
    }, { default: () => row.expectedAnswer ? '编辑' : '标注' })
  }
]);
</script>

<template>
  <div class="flex flex-col gap-4">
    <!-- 筛选栏 -->
    <div class="flex items-center gap-4">
      <NRadioGroup v-model:value="sourceFilter" @update:value="handleFilterChange">
        <NRadioButton value="BADCASE_BACKFLOW">点踩回流</NRadioButton>
        <NRadioButton value="MANUAL">人工标注</NRadioButton>
      </NRadioGroup>
      <NRadioGroup v-model:value="statusFilter" @update:value="handleFilterChange">
        <NRadioButton value="pending">待标注</NRadioButton>
        <NRadioButton value="labeled">已标注</NRadioButton>
      </NRadioGroup>
      <NButton quaternary @click="loadList">
        <template #icon><icon-mdi:refresh /></template>
        刷新
      </NButton>
    </div>

    <!-- 列表 -->
    <NDataTable
      :columns="columns"
      :data="listData"
      :loading="loading"
      :pagination="{
        page: pagination.page,
        pageSize: pagination.pageSize,
        itemCount: total,
        showSizePicker: false,
        onChange: handlePageChange
      }"
      :bordered="false"
      remote
    />

    <!-- 标注弹窗 -->
    <NModal
      v-model:show="labelModalVisible"
      preset="card"
      title="标注样本"
      style="width: 640px"
      :mask-closable="false"
    >
      <NForm label-placement="top">
        <NFormItem label="Trace ID">
          <NInput :value="labelForm.traceId" disabled />
        </NFormItem>
        <NFormItem label="期望意图">
          <NSelect
            v-model:value="labelForm.expectedIntent"
            :options="[
              { label: 'KNOWLEDGE_QA', value: 'KNOWLEDGE_QA' },
              { label: 'DOC_OPERATION', value: 'DOC_OPERATION' },
              { label: 'CHITCHAT', value: 'CHITCHAT' },
              { label: 'AMBIGUOUS', value: 'AMBIGUOUS' }
            ]"
            clearable
            placeholder="可选"
          />
        </NFormItem>
        <NFormItem label="期望澄清动作">
          <NSelect
            v-model:value="labelForm.expectedClarifyAction"
            :options="[
              { label: 'ASK（需要追问）', value: 'ASK' },
              { label: 'READY（可直接检索）', value: 'READY' }
            ]"
            clearable
            placeholder="可选"
          />
        </NFormItem>
        <NFormItem label="期望答案（必填）">
          <NInput
            v-model:value="labelForm.expectedAnswer"
            type="textarea"
            :rows="4"
            placeholder="标准答案，用于回归评测比对"
          />
        </NFormItem>
        <NFormItem label="标注备注">
          <NInput
            v-model:value="labelForm.labelNote"
            type="textarea"
            :rows="2"
            placeholder="可选，标注说明"
          />
        </NFormItem>
      </NForm>

      <template #footer>
        <div class="flex justify-end gap-2">
          <NButton @click="labelModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="labelLoading" @click="submitLabel">
            保存
          </NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>
