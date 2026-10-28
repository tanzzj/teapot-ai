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
  /** 模型上下文窗口 token 数；0/空 = 未配置，由框架按模型名查表（SPEC §30.1） */
  contextWindowSize?: number | null;
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
  /** 该供应商能否自己报出上下文窗口与输入模态（SPEC §30.6）；true 时这两项不必手填 */
  modelDiscovery?: boolean;
}

/** 供应商清单（后端策略 Bean 汇总，界面 provider 下拉与思考档联动的唯一数据源） */
export function modelProviders() {
  return unwrap<ModelProviderMeta[]>(http.get<Result<ModelProviderMeta[]>>('/api/model/providers'));
}

/** 供应商在售模型清单（admin；作模型名下拉数据源，未实现的供应商返回空表 → 界面降级手输） */
export function modelVendorModels(provider: string) {
  return unwrap<string[]>(http.get<Result<string[]>>(`/api/model/vendor-models/${provider}`));
}

/**
 * 探活结果（SPEC §30.2）：ok=密钥可用且模型存在；
 * contextWindowSize=0 表示该供应商的接口不给出窗口，不代表模型不可用；
 * inputModalities=空表示该供应商不报模态，能力位维持原样不动。
 */
export interface ModelProbeResult {
  ok: boolean;
  httpStatus: number;
  contextWindowSize: number;
  /** 供应商报出的输入模态能力位（image/audio/video），后端会据此回填能力位 */
  inputModalities?: string[];
  message: string;
}

/** 模型入口探活（admin）：只打供应商的只读端点，不产生 token 计费；探到的窗口会自动回填 */
export function modelProbe(id: number) {
  return unwrap<ModelProbeResult>(http.post<Result<ModelProbeResult>>(`/api/model/${id}/probe`));
}

/**
 * 未落库模型的探活（SPEC §30.6）：Agent 自带模型在配置页就要拿到窗口与能力位。
 * 仅对「可发现」供应商开放（见 modelProviders 的 modelDiscovery），密钥留空则回落服务端环境变量。
 */
export function modelProbeDraft(payload: { provider: string; modelName: string; apiKey?: string }) {
  return unwrap<ModelProbeResult>(http.post<Result<ModelProbeResult>>('/api/model/probe-draft', payload));
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
