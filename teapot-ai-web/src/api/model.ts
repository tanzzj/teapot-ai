import { http, unwrap } from './http';
import type { Result } from '../types';

/** 模型入口（SPEC §6.4 修订：界面配置化；API Key 不入库） */
export interface ModelEntry {
  id?: number;
  provider: string;
  modelName: string;
  displayName?: string | null;
  baseUrl?: string | null;
  status?: number;
  createdBy?: string;
  createdAt?: string;
}

export function modelList() {
  return unwrap<ModelEntry[]>(http.get<Result<ModelEntry[]>>('/api/model/list'));
}

export function modelCreate(payload: Omit<ModelEntry, 'id'>) {
  return unwrap<ModelEntry>(http.post<Result<ModelEntry>>('/api/model/create', payload));
}

export function modelUpdate(id: number, payload: Partial<ModelEntry>) {
  return unwrap<ModelEntry>(http.put<Result<ModelEntry>>(`/api/model/${id}`, payload));
}

export function modelDelete(id: number) {
  return unwrap<void>(http.delete<Result<void>>(`/api/model/${id}`));
}
