import { request } from '../request';

/** 触发评测（异步） */
export function startEvaluation(data: Api.Eval.Request) {
  return request<Api.Eval.TaskStartResponse>({
    url: '/eval',
    method: 'post',
    data
  });
}

/** 查询评测任务状态 */
export function getEvalTask(taskId: string) {
  return request<Api.Eval.TaskStatusResponse>({
    url: `/eval/tasks/${taskId}`,
    method: 'get'
  });
}

/** 保存标注 */
export function saveEvalSample(data: Api.Eval.SampleRow) {
  return request({
    url: '/eval/samples',
    method: 'post',
    data
  });
}

/** 查标注列表 */
export function fetchEvalSamples(params: Api.Eval.SampleListParams) {
  return request<Api.Eval.SampleListResponse>({
    url: '/eval/samples',
    method: 'get',
    params
  });
}
