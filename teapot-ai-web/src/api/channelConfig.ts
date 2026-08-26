import { http, unwrap } from './http';
import type { ChannelListData, ChannelRecordName, ChannelTestResult, Result } from '../types';

/**
 * 渠道连接器记录接口（SPEC §24.4，/api/channel-config）：
 * CRUD 仅 admin；registry 轻量名单 developer/viewer 可读（Agent 配置下拉选择用）。
 * app_secret AES-GCM 加密入库，界面不回显明文。
 */

/** 渠道连接记录列表（仅 admin） */
export function channelConfigList() {
  return unwrap<ChannelListData>(http.get<Result<ChannelListData>>('/api/channel-config/list'));
}

/** 新建渠道连接记录（仅 admin） */
export function createChannelRecord(payload: Record<string, string>) {
  return unwrap<ChannelListData>(http.post<Result<ChannelListData>>('/api/channel-config', payload));
}

/** 更新渠道连接记录（仅 admin；appSecret 留空不修改，凭证变更后引用 Agent 的 channel 自动重启） */
export function updateChannelRecord(payload: Record<string, string>) {
  return unwrap<ChannelListData>(http.put<Result<ChannelListData>>('/api/channel-config', payload));
}

/** 删除渠道连接记录（仅 admin；被 Agent 引用时后端拒绝） */
export function deleteChannelRecord(name: string) {
  return unwrap<ChannelListData>(
    http.delete<Result<ChannelListData>>(`/api/channel-config/${encodeURIComponent(name)}`),
  );
}

/** 测试连接（仅 admin，§24.10）：轻量调平台 API 验凭证/网络；凭证留空时后端回落库内解密值 */
export function testChannelConnect(payload: Record<string, string>) {
  return unwrap<ChannelTestResult>(http.post<Result<ChannelTestResult>>('/api/channel-config/test', payload));
}

/** 渠道记录轻量名单（§24.4，developer/viewer 可读）：Agent 配置下拉选择用 */
export function channelRegistry() {
  return unwrap<ChannelRecordName[]>(http.get<Result<ChannelRecordName[]>>('/api/channel-config/registry'));
}
