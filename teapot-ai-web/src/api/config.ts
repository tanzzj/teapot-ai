import { http, unwrap } from './http';
import type {
  Result,
  SandboxListData,
  SandboxOptions,
  SandboxRecordName,
  StorageListData,
  StorageOptions,
  StorageRecordName,
} from '../types';

/**
 * 系统配置接口（SPEC §16.5.1 / §20 / §21 / §22）：
 * 沙箱全局凭证、OSS/沙箱连接记录；GET 回显一律脱敏，写仅 admin。
 */

/** 沙箱选项与全局接入状态（SPEC §16.11） */
export function sandboxOptions() {
  return unwrap<SandboxOptions>(http.get<Result<SandboxOptions>>('/api/config/sandbox-options'));
}

/** 写入全局 AgentRun 接入凭证（仅 admin，SPEC §16.5.1） */
export function updateSandboxConfig(payload: Record<string, string>) {
  return unwrap<SandboxOptions>(http.put<Result<SandboxOptions>>('/api/config/sandbox', payload));
}

/** 图片存储策略与 OSS 接入状态（SPEC §20.5） */
export function storageOptions() {
  return unwrap<StorageOptions>(http.get<Result<StorageOptions>>('/api/config/storage-options'));
}

/** 写入图片存储策略与激活记录（仅 admin，SPEC §20.5/§20.12） */
export function saveStorageConfig(payload: Record<string, string>) {
  return unwrap<StorageOptions>(http.put<Result<StorageOptions>>('/api/config/storage', payload));
}

/** OSS 连接记录列表（仅 admin，SPEC §20.12 多记录） */
export function storageList() {
  return unwrap<StorageListData>(http.get<Result<StorageListData>>('/api/config/storage-list'));
}

/** 新建 OSS 连接记录（仅 admin，§20.12） */
export function createStorageRecord(payload: Record<string, string>) {
  return unwrap<StorageListData>(http.post<Result<StorageListData>>('/api/config/storage-record', payload));
}

/** 更新 OSS 连接记录（仅 admin，§20.12；AK/Secret 留空不修改） */
export function updateStorageRecord(payload: Record<string, string>) {
  return unwrap<StorageListData>(http.put<Result<StorageListData>>('/api/config/storage-record', payload));
}

/** 删除 OSS 连接记录（仅 admin，§20.12；激活中禁删） */
export function deleteStorageRecord(name: string) {
  return unwrap<StorageListData>(
    http.delete<Result<StorageListData>>(`/api/config/storage-record/${encodeURIComponent(name)}`),
  );
}

/** OSS 记录轻量名单（§22.1，developer/viewer 可读）：Agent 配置下拉选择用 */
export function storageRecordNames() {
  return unwrap<StorageRecordName[]>(http.get<Result<StorageRecordName[]>>('/api/config/storage-record-names'));
}

/** 沙箱连接记录列表（仅 admin，SPEC §22.2） */
export function sandboxList() {
  return unwrap<SandboxListData>(http.get<Result<SandboxListData>>('/api/config/sandbox-list'));
}

/** 新建沙箱连接记录（仅 admin，§22.2） */
export function createSandboxRecord(payload: Record<string, string>) {
  return unwrap<SandboxListData>(http.post<Result<SandboxListData>>('/api/config/sandbox-record', payload));
}

/** 更新沙箱连接记录（仅 admin，§22.2；敏感列留空不修改） */
export function updateSandboxRecord(payload: Record<string, string>) {
  return unwrap<SandboxListData>(http.put<Result<SandboxListData>>('/api/config/sandbox-record', payload));
}

/** 删除沙箱连接记录（仅 admin，§22.2） */
export function deleteSandboxRecord(name: string) {
  return unwrap<SandboxListData>(
    http.delete<Result<SandboxListData>>(`/api/config/sandbox-record/${encodeURIComponent(name)}`),
  );
}

/** 沙箱记录轻量名单（§22.2，developer/viewer 可读）：Agent 配置下拉选择用 */
export function sandboxRecordNames() {
  return unwrap<SandboxRecordName[]>(http.get<Result<SandboxRecordName[]>>('/api/config/sandbox-record-names'));
}
