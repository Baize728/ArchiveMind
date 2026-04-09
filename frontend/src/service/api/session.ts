import { request } from '../request';

/** 创建新会话 */
export function fetchCreateSession() {
  return request<Api.Chat.Session>({
    url: '/sessions',
    method: 'post'
  });
}

/** 获取按时间分组的会话列表 */
export function fetchSessionList() {
  return request<Api.Chat.GroupedSessionList>({
    url: '/sessions'
  });
}

/** 切换活跃会话 */
export function fetchSwitchSession(sessionId: string) {
  return request<Api.Chat.SessionDetail>({
    url: `/sessions/${sessionId}/active`,
    method: 'put'
  });
}

/** 删除指定会话 */
export function fetchDeleteSession(sessionId: string) {
  return request<void>({
    url: `/sessions/${sessionId}`,
    method: 'delete'
  });
}

/** 更新会话标题 */
export function fetchUpdateSessionTitle(sessionId: string, title: string) {
  return request<void>({
    url: `/sessions/${sessionId}/title`,
    method: 'put',
    data: { title }
  });
}
