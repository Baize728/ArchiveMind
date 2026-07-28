import { request } from '../request';

/** 获取 Trace 列表 */
export function fetchTraceList(params?: Api.Trace.ListParams) {
  return request<Api.Trace.ListResponse>({
    url: '/traces/list',
    method: 'get',
    params
  });
}

/** 获取 Trace 详情（含全部事件） */
export function fetchTraceDetail(traceId: string) {
  return request<Api.Trace.DetailResponse>({
    url: `/traces/${traceId}`,
    method: 'get'
  });
}
