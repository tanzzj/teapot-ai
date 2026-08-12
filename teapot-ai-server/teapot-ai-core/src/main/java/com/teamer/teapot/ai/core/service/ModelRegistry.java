package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.dao.ModelEntryMapper;
import com.teamer.teapot.ai.core.model.ModelEntryDO;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表（SPEC §6.4 修订）：按 provider:model 解析 Model 实例并缓存。
 * 模型入口（含可选 baseUrl）由 t_model_entry 界面配置化提供；
 * API Key 只存在于服务器环境变量；t_agent 只存模型标识字符串。
 */
@Service
public class ModelRegistry {

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
        if (modelId == null || modelId.isBlank()) {
            throw new BizException("modelId 不能为空");
        }
        return cache.computeIfAbsent(modelId, this::create);
    }

    /** 模型入口变更后失效实例缓存（ModelService 写操作调用） */
    public void evict(String modelId) {
        cache.remove(modelId);
    }

    private Model create(String modelId) {
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
                yield DashScopeChatModel.builder()
                        .apiKey(dashscopeApiKey)
                        .modelName(modelName)
                        .stream(true)
                        .build();
            }
            case "openai" -> {
                if (openaiApiKey.isBlank()) {
                    throw new BizException("未配置 OPENAI_API_KEY，无法使用 " + modelId);
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
