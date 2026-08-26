package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.dao.ModelEntryMapper;
import com.teamer.teapot.ai.core.model.ModelEntryDO;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表（SPEC §6.4 修订）：按 provider:model 解析 Model 实例并缓存。
 * 模型入口（含可选 baseUrl）由 t_model_entry 界面配置化提供；
 * API Key 只存在于服务器环境变量；t_agent 只存模型标识字符串。
 */
@Slf4j
@Service
public class ModelRegistry {

    /** thinking 模式实例的缓存 key 后缀（与普通实例分槽） */
    private static final String THINK_SUFFIX = "#think";

    private final Map<String, Model> cache = new ConcurrentHashMap<>();

    private final ModelEntryMapper modelEntryMapper;

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${OPENAI_BASE_URL:}")
    private String openaiBaseUrl;

    public ModelRegistry(ModelEntryMapper modelEntryMapper) {
        this.modelEntryMapper = modelEntryMapper;
    }

    public Model resolve(String modelId) {
        return resolve(modelId, false);
    }

    /**
     * 解析 Model 实例并缓存；thinking=true 时构建 DashScope 思考模式实例（独立缓存槽）。
     * openai 供应商无对应 builder 能力，thinking 被忽略。
     */
    public Model resolve(String modelId, boolean thinking) {
        if (modelId == null || modelId.isBlank()) {
            throw new BizException("modelId 不能为空");
        }
        String key = thinking ? modelId + THINK_SUFFIX : modelId;
        return cache.computeIfAbsent(key, k -> create(modelId, thinking));
    }

    /** 模型入口变更后失效实例缓存（ModelService 写操作调用）；thinking 变体一并清除 */
    public void evict(String modelId) {
        cache.remove(modelId);
        cache.remove(modelId + THINK_SUFFIX);
    }

    private Model create(String modelId, boolean thinking) {
        int idx = modelId.indexOf(':');
        if (idx <= 0 || idx == modelId.length() - 1) {
            throw new BizException("modelId 格式须为 provider:model：" + modelId);
        }
        String provider = modelId.substring(0, idx);
        String modelName = modelId.substring(idx + 1);
        ModelEntryDO entry = modelEntryMapper.selectByModelId(provider, modelName);
        return switch (provider) {
            case "dashscope" -> {
                if (dashscopeApiKey.isBlank()) {
                    throw new BizException("未配置 DASHSCOPE_API_KEY，无法使用 " + modelId);
                }
                DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                        .apiKey(dashscopeApiKey)
                        .modelName(modelName)
                        .stream(true);
                // 能力位含 image/video 时显式走 multimodal-generation 端点，不依赖模型名启发式（SPEC §19）
                if (entry != null && entry.getCapabilities() != null
                        && (entry.getCapabilities().contains("image") || entry.getCapabilities().contains("video"))) {
                    builder.endpointType(EndpointType.MULTIMODAL);
                }
                // Agent 级思考模式开关：模型层 enableThinking（GenerateOptions 无此开关）
                if (thinking) {
                    builder.enableThinking(true);
                }
                yield builder.build();
            }
            case "openai" -> {
                if (openaiApiKey.isBlank()) {
                    throw new BizException("未配置 OPENAI_API_KEY，无法使用 " + modelId);
                }
                if (thinking) {
                    // OpenAIChatModel.Builder 无 enableThinking 能力，忽略 Agent 级思考模式开关
                    log.warn("openai 供应商不支持思考模式开关，已忽略 modelId={}", modelId);
                }
                OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                        .apiKey(openaiApiKey)
                        .modelName(modelName)
                        .stream(true);
                // 入口配置的 baseUrl 优先于环境变量 OPENAI_BASE_URL
                String baseUrl = entry != null && entry.getBaseUrl() != null && !entry.getBaseUrl().isBlank()
                        ? entry.getBaseUrl() : openaiBaseUrl;
                if (!baseUrl.isBlank()) {
                    builder.baseUrl(baseUrl);
                }
                yield builder.build();
            }
            default -> throw new BizException("不支持的模型供应商：" + provider);
        };
    }
}
