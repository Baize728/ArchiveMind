import { localStg } from '@/utils/storage';

function llmFetch(path: string, options: RequestInit = {}) {
  const token = localStg.get('token');
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);
  return fetch(`/proxy-api${path}`, { ...options, headers })
    .then(res => (res.ok ? res.json() : null))
    .catch(() => null);
}

export function fetchLlmProviders() {
  return llmFetch('/llm/providers')
    .then(data => ({ data: data as Api.Llm.ProvidersResponse | null, error: !data }));
}

export function setLlmPreference(providerId: string) {
  return llmFetch(`/llm/providers/preference?providerId=${encodeURIComponent(providerId)}`, { method: 'POST' })
    .then(data => ({ data: data as Api.Llm.PreferenceResponse | null, error: !data }));
}
