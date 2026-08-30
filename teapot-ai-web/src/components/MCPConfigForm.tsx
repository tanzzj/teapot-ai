import { Col, Row } from 'antd';
import { Form, Input, Select } from '@agentscope-ai/design';

/** 将 env/headers 的 Record<string,string> 转为 "KEY=VALUE" 逐行文本 */
export function mapToLines(m?: Record<string, string>): string {
  if (!m || Object.keys(m).length === 0) return '';
  return Object.entries(m).map(([k, v]) => `${k}=${v}`).join('\n');
}

/** 将 "KEY=VALUE" 逐行文本转为 Record<string,string> */
export function linesToMap(text: string): Record<string, string> {
  const result: Record<string, string> = {};
  if (!text) return result;
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const eqIdx = trimmed.indexOf('=');
    if (eqIdx <= 0) continue;
    result[trimmed.slice(0, eqIdx).trim()] = trimmed.slice(eqIdx + 1).trim();
  }
  return result;
}

/** 将 args 数组转为逐行文本 */
export function argsToLines(args?: string[]): string {
  return (args ?? []).join('\n');
}

/** 将逐行文本转为 args 数组 */
export function linesToArgs(text: string): string[] {
  return text.split('\n').map(l => l.trim()).filter(Boolean);
}

/**
 * MCP Server 连接配置共享表单字段（系统配置弹窗 与 Agent 级自定义配置弹窗复用）：
 * 传输协议 + 按协议的条件字段（stdio：command/args/env；HTTP/SSE：url/headers）。
 * 须置于 <Form layout="vertical"> 内；字段值均为行文本（args/env/headers 用
 * argsToLines/linesToArgs、mapToLines/linesToMap 与结构化数据互转）。
 */
export function MCPConfigFormFields({
  showName = false,
  nameDisabled = false,
  showRemark = false,
}: {
  /** 是否展示名称字段（系统配置记录需要；Agent 内联配置不需要） */
  showName?: boolean;
  /** 名称禁用（编辑记录时名称不可改） */
  nameDisabled?: boolean;
  /** 是否展示备注字段（仅系统配置记录需要） */
  showRemark?: boolean;
}) {
  const transport = Form.useWatch('transport');

  return (
    <>
      {showName ? (
        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item name="name" label="名称" rules={[{ required: true, message: '必填' }]}>
              <Input placeholder="如 filesystem、github-mcp" disabled={nameDisabled} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12}>
            <Form.Item name="transport" label="传输协议" rules={[{ required: true, message: '必选' }]}>
              <Select
                style={{ width: '100%' }}
                options={[
                  { value: 'streamable_http', label: 'Streamable HTTP（远程）' },
                  { value: 'sse', label: 'SSE（远程）' },
                  { value: 'stdio', label: 'Stdio（本地进程）' },
                ]}
              />
            </Form.Item>
          </Col>
        </Row>
      ) : (
        <Form.Item name="transport" label="传输协议" rules={[{ required: true, message: '必选' }]}>
          <Select
            options={[
              { value: 'streamable_http', label: 'Streamable HTTP（远程）' },
              { value: 'sse', label: 'SSE（远程）' },
              { value: 'stdio', label: 'Stdio（本地进程）' },
            ]}
          />
        </Form.Item>
      )}

      {transport === 'stdio' ? (
        <>
          <Form.Item
            name="command"
            label="启动命令"
            rules={[{ required: true, message: '必填' }]}
            tooltip="本地进程的启动命令，如 npx、uvx、node 等"
          >
            <Input placeholder="如 npx、uvx、node" />
          </Form.Item>
          <Form.Item
            name="args"
            label="命令参数"
            tooltip="每行一个参数，如 @modelcontextprotocol/server-filesystem"
          >
            <Input.TextArea rows={3} placeholder={"@modelcontextprotocol/server-filesystem\n/path/to/dir"} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item
            name="env"
            label="环境变量"
            tooltip="每行 KEY=VALUE 格式"
          >
            <Input.TextArea rows={3} placeholder={"API_KEY=your-key\nDEBUG=true"} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        </>
      ) : (
        <>
          <Form.Item
            name="url"
            label="服务 URL"
            rules={[{ required: true, message: '必填' }]}
            tooltip="MCP Server 的 HTTP/SSE 端点地址"
          >
            <Input placeholder={transport === 'sse' ? 'http://localhost:3001/sse' : 'http://localhost:3001/mcp'} />
          </Form.Item>
          <Form.Item
            name="headers"
            label="请求头"
            tooltip="每行 KEY=VALUE 格式，如 Authorization=Bearer xxx"
          >
            <Input.TextArea rows={3} placeholder={"Authorization=Bearer your-token"} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        </>
      )}

      <Form.Item name="description" label="描述">
        <Input placeholder="可选，简述该 MCP Server 提供的能力" />
      </Form.Item>
      {showRemark && (
        <Form.Item name="remark" label="备注">
          <Input placeholder="可选" />
        </Form.Item>
      )}
    </>
  );
}
