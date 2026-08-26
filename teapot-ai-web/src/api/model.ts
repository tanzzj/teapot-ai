import { http, unwrap } from './http';
import type { Result } from '../types';

/** 模型入口（SPEC §6.4 修订：界面配置化；API Key 不入库） */
export interface ModelEntry {
  id?: number;
  provider: string;
  modelName: string;
  displayName?: string | null;
  baseUrl?: string | null;
  /** 能力位逗号分隔：image,audio,video；空=纯文本（SPEC §19） */
  capabilities?: string | null;
  status?: number;
  createdBy?: string;
  createdAt?: string;
}

export function modelList() {
  return unwrap<ModelEntry[]>(http.get<Result<ModelEntry[]>>('/api/model/list'));
}

/** 供应商在售模型清单（admin；目前仅 dashscope 实现，作模型名下拉数据源） */
export function modelVendorModels(provider: string) {
  return unwrap<string[]>(http.get<Result<string[]>>(`/api/model/vendor-models/${provider}`));
}

/** 启用入口的能力位（多模态 gating，任意登录用户可读） */
export function modelCapabilities() {
  return unwrap<ModelEntry[]>(http.get<Result<ModelEntry[]>>('/api/model/capabilities'));
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
