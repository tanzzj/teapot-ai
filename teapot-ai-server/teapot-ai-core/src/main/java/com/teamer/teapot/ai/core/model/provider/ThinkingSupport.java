package com.teamer.teapot.ai.core.model.provider;

/**
 * 供应商对「思考模式」的表达力（SPEC §29.16）：六家 SDK 的通道不等价，
 * 界面按此禁用档位、保存期据此校验，不做静默降级。
 */
public enum ThinkingSupport {

    /** 三态齐：可显式开启、显式关闭、不下发参数 */
    TRISTATE,

    /** 只有"开"与"不下发"两条通道，没有显式关闭的开关 */
    ON_ONLY,

    /** SDK 侧没有可用的思考开关（或需要额外配置项才能表达），一律不下发 */
    NONE
}
