import { http, unwrap } from './http';
import type { MCPListData, MCPRecordName, Result } from '../types';

/**
 * MCP Server 配置接口（/api/mcp-config）：
 * CRUD + toggle 仅 admin；registry 轻量名单 developer/viewer 可读。
 */

/** MCP 配置列表（仅 admin） */
export function mcpConfigList() {
  return unwrap<MCPListData>(http.get<Result<MCPListData>>('/api/mcp-config/list'));
}

/** 新建 MCP 配置（仅 admin） */
export function createMCPRecord(payload: Record<string, unknown>) {
  return unwrap<MCPListData>(http.post<Result<MCPListData>>('/api/mcp-config', payload));
}

/** 更新 MCP 配置（仅 admin） */
export function updateMCPRecord(payload: Record<string, unknown>) {
  return unwrap<MCPListData>(http.put<Result<MCPListData>>('/api/mcp-config', payload));
}

/** 删除 MCP 配置（仅 admin） */
export function deleteMCPRecord(name: string) {
  return unwrap<MCPListData>(
    http.delete<Result<MCPListData>>(`/api/mcp-config/${encodeURIComponent(name)}`),
  );
}

/** 切换启用/禁用（仅 admin） */
export function toggleMCPRecord(name: string, enabled: boolean) {
  return unwrap<MCPListData>(
    http.patch<Result<MCPListData>>(`/api/mcp-config/toggle/${encodeURIComponent(name)}`, { enabled }),
  );
}

/** MCP 配置轻量名单（developer/viewer 可读）：Agent 配置下拉选择用 */
export function mcpRegistry() {
  return unwrap<MCPRecordName[]>(http.get<Result<MCPRecordName[]>>('/api/mcp-config/registry'));
}
