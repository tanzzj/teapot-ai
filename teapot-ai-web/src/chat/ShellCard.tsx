import { useMemo, useRef, useState } from 'react';
import { IconButton, message } from '@agentscope-ai/design';
import { OperateCard, useProviderContext } from '@agentscope-ai/chat';
import {
  SparkCopyLine,
  SparkDownLine,
  SparkLoadingLine,
  SparkToolLine,
  SparkTrueLine,
  SparkUpLine,
} from '@agentscope-ai/icons';

/**
 * shell 工具（execute）卡（SPEC §29.28~§29.30）：外壳与折叠交互用聊天组件的 OperateCard ——
 * thinking 块和默认工具卡都是它，所以收起时那一条 header 与对话里其它过程块完全同款；
 * 展开后输入 / 输出各占一个独立带滚动的框（沿用组件自带的 tool-call-block 结构与限高），
 * 只有命令内容用终端样式（深色底 + 等宽 + `$` 提示符），不再摆整坨 arguments JSON。
 *
 * <p>退出码从正文里提成状态徽标挂在 header 上：0 绿 / 非 0 红 / 执行中灰。默认收起，
 * 只有还在跑的时候展开，免得一次长输出的 shell 调用把整条对话撑开。
 *
 * <p>退出码为什么在正文里：harness 两条执行路径都把它拼进工具输出文本 —— 沙箱路径
 * （ShellExecuteTool）前导 `Exit code: N`，本地路径（LocalFilesystemWithShell）只在非 0 时
 * 尾随追加 `Exit code: N`。两种形态都在这里剥掉。
 *
 * <p>数据来源与计划卡相同（aguiBridge 的 TOOL_CALL_* → tool_call 消息），arguments 是流式
 * 拼接中的 JSON 字符串，解析必须容忍不完整。
 */

interface RuntimeMessageLike {
  status?: string;
  content?: { data?: { arguments?: string; output?: string } }[];
}

const MONO = 'Menlo, Consolas, monospace';

/** 终端样式只给命令行用；高度与滚动交给外层 block-content，避免两层滚动条 */
const COMMAND_BLOCK: React.CSSProperties = {
  background: 'rgba(26, 26, 29, 0.92)',
  color: '#e9e9ec',
  padding: '8px 10px',
  margin: 0,
  fontFamily: MONO,
  fontSize: 12,
  lineHeight: 1.6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
};

/** 输出保持正文观感，只保留等宽与换行（列对齐对 shell 输出有意义） */
const OUTPUT_BLOCK: React.CSSProperties = {
  padding: '8px 10px',
  margin: 0,
  fontFamily: MONO,
  fontSize: 12,
  lineHeight: 1.6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
  color: 'rgba(26, 26, 29, 0.78)',
};

const CWD_LABEL: React.CSSProperties = {
  maxWidth: 260,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontFamily: MONO,
  fontSize: 11.5,
  color: 'rgba(26, 26, 29, 0.45)',
};

/** 流式容错取字符串字段：完整 JSON 优先，未完成时按字面量截取 */
function stringField(args: string, key: string): string {
  if (!args) return '';
  try {
    const parsed = JSON.parse(args) as Record<string, unknown>;
    if (typeof parsed?.[key] === 'string') return parsed[key] as string;
  } catch {
    // arguments 还在流式拼接中，走下面的截取
  }
  const matched = new RegExp('"' + key + '"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)').exec(args);
  if (!matched) return '';
  try {
    return JSON.parse('"' + matched[1] + '"') as string;
  } catch {
    return matched[1];
  }
}

const EXIT_LINE = /(?:^|\n)Exit code:\s*(-?\d+)[ \t]*(?=\n|$)/;

/** 剥掉前导/尾随的退出码行，返回退出码与纯输出 */
function splitExitCode(raw: string | undefined): { exit: number | null; body: string } {
  if (!raw) return { exit: null, body: '' };
  const matched = EXIT_LINE.exec(raw);
  const exit = matched ? Number(matched[1]) : null;
  const body = matched
    ? raw.slice(0, matched.index) + raw.slice(matched.index + matched[0].length)
    : raw;
  return { exit, body: body.replace(/^\s*\n+/, '').replace(/\s+$/, '') };
}

function chipStyle(exit: number): React.CSSProperties {
  const base = {
    padding: '0 7px',
    borderRadius: 999,
    fontSize: 11.5,
    fontFamily: MONO,
    lineHeight: '18px',
  };
  return exit === 0
    ? { ...base, background: 'rgba(24,121,78,0.12)', color: '#17663f' }
    : { ...base, background: 'rgba(209,68,68,0.12)', color: '#b1382f' };
}

/**
 * 输入 / 输出各自的框：沿用组件自带 tool-call-block 的样式类（带边框、框头、内容限高内滚动），
 * 框头可点，单独收起这一个框而不影响另一个。
 */
function Block({
  prefixCls,
  title,
  extra,
  maxHeight,
  children,
}: {
  prefixCls: string;
  title: string;
  extra?: React.ReactNode;
  maxHeight?: number;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(true);
  return (
    <div className={`${prefixCls}-tool-call-block`}>
      <div
        className={`${prefixCls}-tool-call-block-header`}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className={`${prefixCls}-tool-call-block-title`}>{title}</span>
        <div className={`${prefixCls}-tool-call-block-extra`} style={{ gap: 6 }}>
          {extra}
          <IconButton
            size="small"
            bordered={false}
            style={{ marginRight: -6 }}
            icon={open ? <SparkUpLine /> : <SparkDownLine />}
          />
        </div>
      </div>
      {open && (
        <div className={`${prefixCls}-tool-call-block-content`} style={maxHeight ? { maxHeight } : undefined}>
          {children}
        </div>
      )}
    </div>
  );
}

export function ShellCard({ data }: { data: RuntimeMessageLike }) {
  const { getPrefixCls } = useProviderContext();
  const prefixCls = getPrefixCls('operate-card');
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const loading = data?.status === 'in_progress';
  const args = (data?.content?.[0]?.data as { arguments?: string } | undefined)?.arguments ?? '';
  const output = (data?.content?.[1]?.data as { output?: string } | undefined)?.output;
  const command = useMemo(() => stringField(args, 'command'), [args]);
  const cwd = useMemo(() => stringField(args, 'working_directory'), [args]);
  const { exit, body } = useMemo(() => splitExitCode(output), [output]);

  if (!command && !body && !loading) {
    return null;
  }

  const copyCommand = () => {
    navigator.clipboard
      ?.writeText(command)
      .then(() => {
        if (timer.current) clearTimeout(timer.current);
        setCopied(true);
        timer.current = setTimeout(() => setCopied(false), 2000);
      })
      .catch(() => message.error('复制失败，请手动选取'));
  };

  const copyButton = command ? (
    <IconButton
      size="small"
      bordered={false}
      title="复制命令"
      icon={copied ? <SparkTrueLine /> : <SparkCopyLine />}
      onClick={(event: React.MouseEvent) => {
        // 框头整条是收起开关，复制不能顺带把框折起来
        event.stopPropagation();
        copyCommand();
      }}
    />
  ) : null;

  return (
    <OperateCard
      header={{
        icon: loading ? <SparkLoadingLine spin /> : <SparkToolLine />,
        title: 'execute',
        description: exit === null ? undefined : <span style={chipStyle(exit)}>exit {exit}</span>,
      }}
      body={{
        defaultOpen: loading,
        children: (
          <OperateCard.LineBody>
            <Block
              prefixCls={prefixCls}
              title="输入"
              extra={
                <>
                  {cwd && (
                    <span style={CWD_LABEL} title={cwd}>
                      {cwd}
                    </span>
                  )}
                  {copyButton}
                </>
              }
            >
              <pre style={COMMAND_BLOCK}>
                <span style={{ color: '#7ee787', marginRight: 6 }}>$</span>
                {command || '…'}
              </pre>
            </Block>
            {(body || loading) && (
              <Block prefixCls={prefixCls} title="输出" maxHeight={288}>
                <pre style={OUTPUT_BLOCK}>{body || '…'}</pre>
              </Block>
            )}
          </OperateCard.LineBody>
        ),
      }}
    />
  );
}
