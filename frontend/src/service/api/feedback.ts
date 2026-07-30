import { request } from '../request';

/** 提交用户反馈（点赞/点踩） */
export function submitFeedback(data: Api.Feedback.Request) {
  return request<{ code: number; message: string }>({
    url: '/feedback',
    method: 'post',
    data
  });
}
