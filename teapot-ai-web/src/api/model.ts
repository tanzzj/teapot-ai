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
  /** 授权空间桶（SPEC §28.11）：0=全部空间（含个人），其余为团队空间 id；空=未开放 */
  workspaceIds?: number[];
}

export function modelList() {
  return unwrap<ModelEntry[]>(http.get<Result<ModelEntry[]>>('/api/model/list'));
}

/** 已接入供应商及其思考档表达力（SPEC §29.16）：TRISTATE=可开可关、ON_ONLY=只能显式开、NONE=无通道 */
export interface ModelProviderMeta {
  provider: string;
  thinkingSupport: 'TRISTATE' | 'ON_ONLY' | 'NONE';
}

/** 供应商清单（后端策略 Bean 汇总，界面 provider 下拉与思考档联动的唯一数据源） */
export function modelProviders() {
  return unwrap<ModelProviderMeta[]>(http.get<Result<ModelProviderMeta[]>>('/api/model/providers'));
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
