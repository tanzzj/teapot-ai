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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模型注册表（SPEC §6.4 修订）：按 provider:model 解析 Model 实例并缓存。
 * 模型入口（含可选 baseUrl）由 t_model_entry 界面配置化提供；系统入口的 API Key 只存在于
 * 服务器环境变量，t_agent 只存模型标识字符串；Agent 自带模型（SPEC §29）例外——
 * 凭证随 Agent 的 feature 加密存储，解析时以 {@link Credential} 覆写密钥/baseUrl/能力位。
 */
@Slf4j
@Service
public class ModelRegistry {

    /** thinking 模式实例的缓存 key 后缀（与普通实例分槽） */
    private static final String THINK_SUFFIX = "#think";

    /**
     * 显式不思考实例的缓存 key 后缀。qwen3 混合思考模型（如 qwen3.8-flash）服务端
     * 默认开启思考，不传参数不等于关闭——判定类轻量调用必须显式 enable_thinking=false。
     */
    private static final String NO_THINK_SUFFIX = "#nothink";

    /** Agent 自带凭证实例的缓存 key 前缀（后接凭证指纹，SPEC §29） */
    private static final String CRED_PREFIX = "#cred";

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
        return resolve(modelId, thinking ? Boolean.TRUE : null, null);
    }

    /**
     * 解析显式关闭思考的实例（DashScope 下发 enable_thinking=false）：
     * 用于意图守卫判定等轻量调用，不依赖混合思考模型的服务端默认值。
     * openai 供应商无对应 builder 能力，行为与普通解析一致。
     */
    public Model resolveNoThinking(String modelId) {
        return resolve(modelId, Boolean.FALSE, null);
    }

    /** 带 Agent 自带凭证的解析（SPEC §29）；cred=null 等价于走系统入口与环境变量 */
    public Model resolve(String modelId, boolean thinking, Credential cred) {
        return resolve(modelId, thinking ? Boolean.TRUE : null, cred);
    }

    /** 带 Agent 自带凭证的无思考解析（守卫/渲染子模型同款） */
    public Model resolveNoThinking(String modelId, Credential cred) {
        return resolve(modelId, Boolean.FALSE, cred);
    }

    /**
     * 解析 Model 实例并缓存；thinking 三态：TRUE=显式开启、FALSE=显式关闭、null=不下发参数
     * （DashScope 混合思考模型此时跟随服务端默认，多为开启）。
     */
    private Model resolve(String modelId, Boolean thinking, Credential cred) {
        if (modelId == null || modelId.isBlank()) {
            throw new BizException("modelId 不能为空");
        }
        String key = modelId
                + (thinking == null ? "" : thinking ? THINK_SUFFIX : NO_THINK_SUFFIX)
                + (cred == null ? "" : CRED_PREFIX + cred.fingerprint());
        return cache.computeIfAbsent(key, k -> create(modelId, thinking, cred));
    }

    /**
     * 模型入口变更后失效实例缓存（ModelService 写操作调用）；thinking 变体与凭证变体一并清除。
     * 凭证后缀恒以 '#' 起头且 modelId 的字符集不含 '#'，故前缀匹配不会误伤同名前缀的其它模型。
     */
    public void evict(String modelId) {
        cache.keySet().removeIf(k -> k.equals(modelId) || k.startsWith(modelId + "#"));
    }

    /**
     * 模型可输入的媒体模态（t_model_entry.capabilities，SPEC §19）：小写去重集合，
     * 入口未登记或无能力位返回空集（纯文本）。供媒体模态守卫判定哪些媒体块能原样发给模型
     * （SPEC-media-gen §4.4）。
     */
    public Set<String> capabilities(String modelId) {
        return capabilities(modelId, null);
    }

    /** 带 Agent 自带凭证的能力位查询（SPEC §29）：凭证声明的能力优先于系统入口 */
    public Set<String> capabilities(String modelId, Credential cred) {
        if (modelId == null || modelId.isBlank()) {
            return Set.of();
        }
        if (cred != null && !cred.capabilities().isEmpty()) {
            return cred.capabilities();
        }
        int idx = modelId.indexOf(':');
        if (idx <= 0 || idx == modelId.length() - 1) {
            return Set.of();
        }
        ModelEntryDO entry = modelEntryMapper.selectByModelId(
                modelId.substring(0, idx), modelId.substring(idx + 1));
        if (entry == null || entry.getCapabilities() == null || entry.getCapabilities().isBlank()) {
            return Set.of();
        }
        return Arrays.stream(entry.getCapabilities().split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Model create(String modelId, Boolean thinking, Credential cred) {
        int idx = modelId.indexOf(':');
        if (idx <= 0 || idx == modelId.length() - 1) {
            throw new BizException("modelId 格式须为 provider:model：" + modelId);
        }
        String provider = modelId.substring(0, idx);
        String modelName = modelId.substring(idx + 1);
        ModelEntryDO entry = modelEntryMapper.selectByModelId(provider, modelName);
        return switch (provider) {
            case "dashscope" -> {
                String apiKey = cred != null && !cred.apiKey().isBlank() ? cred.apiKey() : dashscopeApiKey;
                if (apiKey.isBlank()) {
                    throw new BizException("未配置 DASHSCOPE_API_KEY，无法使用 " + modelId);
                }
                DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .stream(true);
                // 能力位含 image/video 时显式走 multimodal-generation 端点，不依赖模型名启发式（SPEC §19）
                Set<String> caps = capabilities(modelId, cred);
                if (caps.contains("image") || caps.contains("video")) {
                    builder.endpointType(EndpointType.MULTIMODAL);
                }
                // Agent 级思考模式开关：模型层 enableThinking（GenerateOptions 无此开关）；
                // 三态透传，FALSE 显式关闭混合思考模型的服务端默认思考
                if (thinking != null) {
                    builder.enableThinking(thinking);
                }
                yield builder.build();
            }
            case "openai" -> {
                String apiKey = cred != null && !cred.apiKey().isBlank() ? cred.apiKey() : openaiApiKey;
                if (apiKey.isBlank()) {
                    throw new BizException("未配置 OPENAI_API_KEY，无法使用 " + modelId);
                }
                if (Boolean.TRUE.equals(thinking)) {
                    // OpenAIChatModel.Builder 无 enableThinking 能力，忽略 Agent 级思考模式开关
                    log.warn("openai 供应商不支持思考模式开关，已忽略 modelId={}", modelId);
                }
                OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .stream(true);
                // baseUrl 优先级：Agent 自带网关 > 入口配置的 baseUrl > 环境变量 OPENAI_BASE_URL
                String baseUrl = firstNonBlank(cred == null ? null : cred.baseUrl(),
                        entry == null ? null : entry.getBaseUrl(), openaiBaseUrl);
                if (!baseUrl.isBlank()) {
                    builder.baseUrl(baseUrl);
                }
                yield builder.build();
            }
            default -> throw new BizException("不支持的模型供应商：" + provider);
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /**
     * Agent 自带模型的解析凭证（SPEC §29）：由 {@code t_agent.feature.customModels} 解密后构造。
     * 字段留空即回落系统入口/环境变量；{@code #fingerprint()} 只用于缓存分槽，不进日志。
     */
    public record Credential(String apiKey, String baseUrl, Set<String> capabilities) {

        public Credential {
            apiKey = apiKey == null ? "" : apiKey;
            baseUrl = baseUrl == null ? "" : baseUrl;
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }

        /** 凭证指纹（SHA-256 前 12 位）：同 modelId 不同密钥/网关必须分槽，否则会串号 */
        String fingerprint() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest((apiKey + "\n" + baseUrl + "\n"
                        + new TreeSet<>(capabilities)).getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    hex.append(String.format("%02x", hash[i]));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new BizException("计算模型凭证指纹失败：" + e.getMessage());
            }
        }
    }
}
