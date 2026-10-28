import { useCallback, useEffect, useRef, useState } from 'react';
import { Space, Tag, Typography } from 'antd';
import { Button } from '@agentscope-ai/design';
import { modelProbeDraft, type ModelProbeResult } from '../api/model';

/** 供应商报出的模型元数据（SPEC §30.6）：窗口与输入模态都是权威值，不是人填的 */
export interface VendorModelMeta {
  contextWindowSize?: number;
  capabilities: string[];
}

/**
 * 模型元数据的供应商同步（SPEC §30.6）：供应商能报的就不让人手填。
 *
 * 用在两处形状不同的表单里（模型入口 CRUD、Agent 自带模型），所以只暴露「同步 + 展示」，
 * 值的落库交给调用方写回自己的表单字段——保存时后端还会再同步一次，界面这次只为让管理员看见。
 *
 * 只对 modelDiscovery=true 的供应商发起请求；其余供应商本组件不工作（渲染手工字段）。
 */
export function useVendorModelMeta(params: {
  provider?: string;
  modelName?: string;
  /** Agent 自带模型的密钥；留空 = 用服务端该供应商的环境密钥 */
  apiKey?: string;
  discovery?: boolean;
  onSynced: (meta: VendorModelMeta) => void;
}) {
  const { provider, modelName, apiKey, discovery, onSynced } = params;
  const [meta, setMeta] = useState<ModelProbeResult | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [nonce, setNonce] = useState(0);
  const latest = useRef(0);
  // 回调每次都取最新的，但不作为触发依赖（否则每次 setMeta 都会重新同步）
  const syncedRef = useRef(onSynced);
  syncedRef.current = onSynced;

  useEffect(() => {
    if (!discovery || !provider || !modelName?.trim()) {
      setMeta(null);
      return;
    }
    const seq = ++latest.current;
    const timer = setTimeout(async () => {
      setSyncing(true);
      try {
        const result = await modelProbeDraft({ provider, modelName: modelName.trim(), apiKey: apiKey?.trim() });
        if (seq !== latest.current) return;
        setMeta(result);
        if (result.ok) {
          syncedRef.current({
            contextWindowSize: result.contextWindowSize || undefined,
            capabilities: result.inputModalities ?? [],
          });
        }
      } catch (e) {
        if (seq !== latest.current) return;
        setMeta({
          ok: false,
          httpStatus: 0,
          contextWindowSize: 0,
          inputModalities: [],
          message: e instanceof Error ? e.message : String(e),
        });
      } finally {
        if (seq === latest.current) setSyncing(false);
      }
    }, 450);
    return () => clearTimeout(timer);
    // nonce：手动「重新同步」；apiKey 不入依赖，避免每次击键都打一次供应商
  }, [provider, modelName, discovery, nonce]);

  const resync = useCallback(() => setNonce((n) => n + 1), []);
  return { meta, syncing, resync };
}

/** 同步结果的一行展示：窗口 + 输入模态；不成功时如实报供应商的话，不静默退回手填 */
export function VendorModelMetaNote({
  meta,
  syncing,
  onResync,
}: {
  meta: ModelProbeResult | null;
  syncing: boolean;
  onResync: () => void;
}) {
  if (!meta) {
    return (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        填写模型名后自动向供应商取回上下文窗口与输入模态，无需手填。
      </Typography.Text>
    );
  }
  return (
    <Space size={6} wrap>
      {meta.ok ? (
        <>
          <Tag color="blue">
            上下文窗口 {meta.contextWindowSize > 0 ? `${Math.round(meta.contextWindowSize / 1024)}K` : '未报'}
          </Tag>
          {(meta.inputModalities ?? []).length > 0 ? (
            (meta.inputModalities ?? []).map((c) => <Tag key={c} color="geekblue">{c}</Tag>)
          ) : (
            <Tag>纯文本</Tag>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            供应商同步
          </Typography.Text>
        </>
      ) : (
        <Typography.Text type="danger" style={{ fontSize: 12 }}>
          {meta.message}
        </Typography.Text>
      )}
      <Button size="small" type="link" loading={syncing} onClick={onResync}>
        重新同步
      </Button>
    </Space>
  );
}
