package com.teamer.teapot.ai.core.agentscope;

import com.teamer.teapot.ai.core.model.AgentFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成模型目录（SPEC-media-gen §4.8）：能力位 → DashScopeMultiModalTool 工具名 → 可用模型清单。
 *
 * <p>候选清单不是从百炼模型列表接口推出来的——那个接口（compatible-mode /models）里根本没有
 * wanx-v1 / wan2.6-t2v / kf2v 这类媒体模型，而且媒体模型分散在四个不同的 endpoint 上
 * （text2image、multimodal-generation、video-generation、image2video），
 * 换一个 endpoint 就报 "url error"。清单逐项实测得到（提交任务即 DELETE 取消，不出图不计费）。
 *
 * <p>清单只作下拉候选，不作风控：用户可自由输入更新在售的模型名（前端 AutoComplete），
 * 后端 validate 不校验枚举，真传错了由百炼报错兜底。
 */
public final class MediaModelCatalog {

    /** 单个能力位的目录项；field 同时是 runtime.mediaModels 的 JSON key */
    public record Entry(String field, String label, String tool, String defaultModel, List<String> models) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("textToImage", "文生图", "dashscope_text_to_image", "wanx-v1", List.of(
                    "wanx-v1", "qwen-image", "qwen-image-plus", "wanx2.0-t2i-turbo",
                    "wan2.2-t2i-flash", "wan2.2-t2i-plus", "wan2.5-t2i-preview")),
            // 图生图走 multimodal-generation，与文生图不同 endpoint：
            // wanx*/纯生成系 qwen-image* 拿不到图，会被工具本地守卫 rejectsImageInput 拦下
            new Entry("imageToImage", "图生图/改图", "dashscope_image_to_image", "qwen-image-edit", List.of(
                    "qwen-image-edit", "qwen-image-edit-plus", "qwen-image-edit-max",
                    "qwen-image-2.0", "qwen-image-2.0-pro")),
            new Entry("textToVideo", "文生视频", "dashscope_text_to_video", "wan2.6-t2v", List.of(
                    "wan2.6-t2v", "wan2.7-t2v", "wan2.5-t2v-preview")),
            new Entry("imageToVideo", "图生视频", "dashscope_image_to_video", "wan2.6-i2v-flash", List.of(
                    "wan2.6-i2v-flash", "wan2.6-i2v", "wan2.5-i2v-preview")),
            new Entry("frameToVideo", "首尾帧生视频", "dashscope_first_and_last_frame_image_to_video",
                    "wan2.2-kf2v-flash", List.of("wan2.2-kf2v-flash", "wanx2.1-kf2v-plus")),
            new Entry("textToAudio", "语音合成", "dashscope_text_to_audio", "qwen3-tts-flash", List.of(
                    "qwen3-tts-flash", "qwen3-tts-instruct-flash", "qwen-tts-2025-05-22")));

    /** 工具名 → 目录项 */
    private static final Map<String, Entry> BY_TOOL = new LinkedHashMap<>();

    static {
        for (Entry entry : ENTRIES) {
            BY_TOOL.put(entry.tool(), entry);
        }
    }

    private MediaModelCatalog() {
    }

    /** 全量目录（供 /api/model/media-models 下发前端下拉） */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** 该工具是否属于本目录管理的生成工具 */
    public static boolean manages(String toolName) {
        return toolName != null && BY_TOOL.containsKey(toolName);
    }

    /**
     * 配置 → 「工具名 → 指定模型」（已 trim，空白项丢弃）。配置为空时返回空 Map（= 存量行为）。
     */
    public static Map<String, String> lockedModels(AgentFeature.MediaModels cfg) {
        Map<String, String> locked = new LinkedHashMap<>();
        if (cfg == null) {
            return locked;
        }
        Map<String, String> byField = new LinkedHashMap<>();
        byField.put("textToImage", cfg.getTextToImage());
        byField.put("imageToImage", cfg.getImageToImage());
        byField.put("textToVideo", cfg.getTextToVideo());
        byField.put("imageToVideo", cfg.getImageToVideo());
        byField.put("frameToVideo", cfg.getFrameToVideo());
        byField.put("textToAudio", cfg.getTextToAudio());
        for (Entry entry : ENTRIES) {
            String model = byField.get(entry.field());
            if (model != null && !model.isBlank()) {
                locked.put(entry.tool(), model.trim());
            }
        }
        return locked;
    }

    /**
     * 锁定模型清单 → 注入 system prompt 的补充段；无锁定项返回 null（用法段保持原样）。
     *
     * <p>光靠 onActing 覆写不够：模型名进了入参，模型自己却不知道，回复里仍会按默认模型的
     * 效果描述产物；这一段让它知道当前能力被固定成了哪个模型。
     */
    public static String lockedUsage(Map<String, String> locked) {
        if (locked == null || locked.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            String model = locked.get(entry.tool());
            if (model == null) {
                continue;
            }
            String suffix = entry.models().contains(model) ? "" : "（清单外的自定义模型）";
            lines.add("- " + entry.label() + "（" + entry.tool() + "）固定使用模型 " + model + suffix
                    + "，工具默认模型为 " + entry.defaultModel());
        }
        if (lines.isEmpty()) {
            return null;
        }
        return "### 已指定的生成模型（必须使用）\n"
                + "管理员已为以下能力固定生成模型，调用对应工具时必须传入该 model 值；\n"
                + "即便你传入的 model 被服务端改写，也要按该模型的实际能力描述产物，不要声称用了别的模型：\n"
                + String.join("\n", lines);
    }

    /** 能力位的默认模型（未知 field 返回 null） */
    public static String defaultModelOf(String field) {
        for (Entry entry : ENTRIES) {
            if (entry.field().equals(field)) {
                return entry.defaultModel();
            }
        }
        return null;
    }
}
