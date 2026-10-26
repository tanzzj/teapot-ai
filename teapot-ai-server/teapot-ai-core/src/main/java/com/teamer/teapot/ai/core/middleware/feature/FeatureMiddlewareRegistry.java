package com.teamer.teapot.ai.core.middleware.feature;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.model.AgentFeature;
import com.teamer.teapot.ai.core.model.vo.FeatureMiddlewareVO;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * feature 驱动中间件注册表（SPEC §29.23）：显式构造注入收集所有
 * {@link FeatureDrivenMiddleware} Bean，启动时逐个内省配置类并把 schema 缓存下来。
 *
 * <p>三件事：
 * <ul>
 *   <li><b>启动 fail-fast</b>：类名不合规则、key 重复、description 空白、泛型参数缺失、
 *       配置声明非法（字段缺注解 / 类型不支持 / defaultValue 解析不了）都让应用起不来。</li>
 *   <li><b>装配</b>：{@link #assemble} 按 Agent 的 feature 快照挑出接受的中间件，
 *       逐个包成本次装配专属的桥接实例（配置懒解析）。新增中间件只需加一个 Bean，
 *       不必改 AgentBuilder，也不必改 AgentFeature（未知命名空间原样保留）。</li>
 *   <li><b>查询与校验</b>：{@link #schema} 给界面渲染配置表单，{@link #validate} 在保存时
 *       拒绝解析不了的配置。</li>
 * </ul>
 *
 * <p>运行期口径相反：配置非法只记警告并跳过该中间件（fail-open），见 {@link FeatureMiddlewareBinding}。
 */
@Slf4j
@Component
public class FeatureMiddlewareRegistry {

    private final Map<String, Registration> registrations = new LinkedHashMap<>();

    public FeatureMiddlewareRegistry(ObjectProvider<FeatureDrivenMiddleware<?>> provider) {
        provider.orderedStream().forEach(this::register);
        if (registrations.isEmpty()) {
            log.info("feature 驱动中间件注册表为空（暂无实现）");
        } else {
            log.info("feature 驱动中间件已注册 {} 个 keys={}", registrations.size(), registrations.keySet());
        }
    }

    private void register(FeatureDrivenMiddleware<?> middleware) {
        String owner = middleware.getClass().getName();
        String key;
        try {
            key = middleware.key();
        } catch (RuntimeException e) {
            throw new IllegalStateException("feature 驱动中间件注册失败：" + e.getMessage(), e);
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(owner + " 的 key() 不能为空");
        }
        if (registrations.containsKey(key)) {
            throw new IllegalStateException("feature 驱动中间件 key 重复：" + key
                    + "（" + owner + " 与 " + registrations.get(key).middleware().getClass().getName() + "）");
        }
        String description = middleware.description();
        if (description == null || description.isBlank()) {
            throw new IllegalStateException(owner + " 必须给出非空的 description()");
        }
        Set<String> applied = middleware.appliedAgents();
        if (applied == null) {
            throw new IllegalStateException(owner + " 的 appliedAgents() 不能返回 null（全平台可见请返回空集合）");
        }
        Type configType = resolveConfigType(middleware.getClass());
        if (configType == null) {
            throw new IllegalStateException(owner + " 无法解析配置类型：子类必须写出泛型参数"
                    + "（如 extends FeatureDrivenMiddleware<FooConfig>）");
        }
        ConfigModel model;
        try {
            model = ConfigSchemaIntrospector.introspect(configType, owner);
        } catch (RuntimeException e) {
            throw new IllegalStateException("feature 驱动中间件配置声明非法：" + e.getMessage(), e);
        }
        registrations.put(key, new Registration(key, middleware, model));
    }

    /** 沿继承链找 {@code FeatureDrivenMiddleware<C>} 上的实参；解析不到返回 null */
    private static Type resolveConfigType(Class<?> impl) {
        for (Class<?> current = impl; current != null && current.getSuperclass() != null;
             current = current.getSuperclass()) {
            if (!FeatureDrivenMiddleware.class.isAssignableFrom(current.getSuperclass())) {
                continue;
            }
            Type generic = current.getGenericSuperclass();
            if (!(generic instanceof ParameterizedType parameterized)) {
                return null;
            }
            Type[] args = parameterized.getActualTypeArguments();
            return args.length == 1 ? args[0] : null;
        }
        return null;
    }

    /**
     * 按 Agent 装配：挑出 {@link FeatureDrivenMiddleware#accept} 通过的中间件，
     * 每个包一个持有本次 feature 快照的桥接实例。
     */
    public List<MiddlewareBase> assemble(String agentKey, AgentFeature feature) {
        if (registrations.isEmpty()) {
            return List.of();
        }
        Map<String, Object> namespaces = feature == null ? Map.of() : feature.getNamespaces();
        List<MiddlewareBase> middlewares = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Registration registration : registrations.values()) {
            if (!registration.middleware().accept(agentKey, namespaces)) {
                continue;
            }
            Object raw = FeatureDrivenMiddleware.configNamespace(registration.key(), namespaces);
            middlewares.add(bind(registration, agentKey, raw));
            keys.add(registration.key());
        }
        if (!keys.isEmpty()) {
            log.info("feature 驱动中间件已挂载 agentKey={} keys={}", agentKey, keys);
        }
        return middlewares;
    }

    @SuppressWarnings("unchecked")
    private static MiddlewareBase bind(Registration registration, String agentKey, Object raw) {
        FeatureDrivenMiddleware<Object> middleware =
                (FeatureDrivenMiddleware<Object>) registration.middleware();
        return new FeatureMiddlewareBinding<>(middleware, registration.model(), agentKey, raw);
    }

    /**
     * 界面可见的中间件清单（含 schema 与该 Agent 已存配置）：
     * {@code forFrontendDisplay=false} 的不出现；{@code appliedAgents} 非空时只对白名单里的 Agent 出现。
     *
     * @param agentKey 目标 Agent；null = 只列全平台可见的中间件，不带已存配置
     */
    public List<FeatureMiddlewareVO> schema(String agentKey, AgentFeature feature) {
        Map<String, Object> namespaces = feature == null ? Map.of() : feature.getNamespaces();
        List<FeatureMiddlewareVO> views = new ArrayList<>();
        for (Registration registration : registrations.values()) {
            FeatureDrivenMiddleware<?> middleware = registration.middleware();
            if (!middleware.forFrontendDisplay() || !appliesTo(middleware, agentKey)) {
                continue;
            }
            Object raw = FeatureDrivenMiddleware.configNamespace(registration.key(), namespaces);
            String error = null;
            if (raw != null) {
                try {
                    ConfigCodec.parse(registration.model(), raw);
                } catch (RuntimeException e) {
                    error = e.getMessage();
                }
            }
            views.add(FeatureMiddlewareVO.of(registration.key(),
                    FeatureDrivenMiddleware.NS_PREFIX + registration.key(),
                    middleware.description(),
                    registration.model().schema().array(),
                    registration.model().schema().fields(),
                    raw, error));
        }
        return views;
    }

    private static boolean appliesTo(FeatureDrivenMiddleware<?> middleware, String agentKey) {
        Set<String> applied = middleware.appliedAgents();
        return applied.isEmpty() || (agentKey != null && applied.contains(agentKey));
    }

    /**
     * 保存校验：feature 里出现的、且平台认识的 {@code middleware_*} 命名空间必须能解析。
     * 没有对应 Bean 的命名空间按 teapot 既有口径原样保留不拒绝（Bean 下线不该让存量 Agent 存不了盘）。
     */
    public void validate(Map<String, Object> namespaces) {
        if (namespaces == null || registrations.isEmpty()) {
            return;
        }
        for (Registration registration : registrations.values()) {
            Object raw = FeatureDrivenMiddleware.configNamespace(registration.key(), namespaces);
            if (raw == null) {
                continue;
            }
            try {
                ConfigCodec.parse(registration.model(), raw);
            } catch (RuntimeException e) {
                throw new BizException("中间件 " + registration.key() + " 的配置非法：" + e.getMessage());
            }
        }
    }

    private record Registration(String key, FeatureDrivenMiddleware<?> middleware, ConfigModel model) {
    }
}
