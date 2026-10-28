/** 自定义模型行（feature.customModels，SPEC §29）：表单内 capabilities 用多选数组，入库为逗号串 */
export interface CustomModelRow {
  provider?: string;
  modelName?: string;
  displayName?: string;
  baseUrl?: string;
  apiKey?: string;
  /** 服务端已存有该模型密钥的标记（回显带出，不提交） */
  hasApiKey?: boolean;
  capabilities?: string[];
  /** 上下文窗口 token 数；供应商报得出的由同步写入，报不出的可手填（SPEC §30.6） */
  contextWindowSize?: number;
}

/** provider:model 标识，与后端 AgentFeature.CustomModel.modelId() 同构 */
export function customModelId(row: Pick<CustomModelRow, 'provider' | 'modelName'>) {
  return `${row.provider}:${row.modelName}`;
}

/** 下拉展示名：配了展示名则「展示名（provider:model）」 */
export function customModelLabel(row: CustomModelRow) {
  const id = customModelId(row);
  return row.displayName?.trim() ? `${row.displayName.trim()}（${id}）` : id;
}

/**
 * 模型下拉分组项（SPEC §29）：系统开放给本空间的模型 + 本 Agent 自带模型。
 * 主模型与压缩/守卫/记忆/标题各界点子模型同源；自定义组随表单实时变化，
 * 新增一行后立刻可选中，不必先保存。空组不下发。
 */
export function modelSelectOptions(systemModels: string[], customModels?: CustomModelRow[]) {
  const customs = (customModels ?? []).filter((m) => m?.provider && m?.modelName);
  return [
    systemModels.length > 0
      ? { label: '系统模型', options: systemModels.map((m) => ({ label: m, value: m })) }
      : null,
    customs.length > 0
      ? {
          label: '本 Agent 自定义',
          options: customs.map((m) => ({ label: customModelLabel(m), value: customModelId(m) })),
        }
      : null,
  ].filter(Boolean) as { label: string; options: { label: string; value: string }[] }[];
}
