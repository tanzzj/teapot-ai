package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.AgentSkillMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentRegistry（SPEC §6.1 平台核心组件）：
 * t_agent 记录 → 进程内 HarnessAgent 实例缓存；配置变更时失效重建。
 * 无状态并发：同一实例服务全部用户，RuntimeContext(userId, sessionId) 隔离。
 */
@Slf4j
@Component
public class AgentRegistry {

    private static final int DEFAULT_COMPACTION_TRIGGER = 30;
    private static final int DEFAULT_COMPACTION_KEEP = 10;

    private final Map<String, HarnessAgent> cache = new ConcurrentHashMap<>();
    private final AgentMapper agentMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final ModelRegistry modelRegistry;
    private final MysqlAgentStateStore stateStore;
    private final MysqlSkillRepository skillRepositoryAgent;
    private final TeapotAiProperties properties;

    public AgentRegistry(AgentMapper agentMapper, AgentSkillMapper agentSkillMapper,
                         ModelRegistry modelRegistry, MysqlAgentStateStore stateStore,
                         @Qualifier("skillRepositoryAgent") MysqlSkillRepository skillRepositoryAgent,
                         TeapotAiProperties properties) {
        this.agentMapper = agentMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.modelRegistry = modelRegistry;
        this.stateStore = stateStore;
        this.skillRepositoryAgent = skillRepositoryAgent;
        this.properties = properties;
    }

    /** 惰性构建（AG-UI registerFactory 的 supplier 入口） */
    public HarnessAgent getOrCreate(String agentKey) {
        return cache.computeIfAbsent(agentKey, this::build);
    }

    /** 配置变更/删除时失效重建（skill 内容变更无需重建，动态合并下一轮生效） */
    public void invalidate(String agentKey) {
        HarnessAgent removed = cache.remove(agentKey);
        if (removed != null) {
            try {
                removed.close();
            } catch (Exception e) {
                log.warn("关闭 HarnessAgent 失败 agentKey={}", agentKey, e);
            }
            log.info("AgentRegistry 实例已失效 agentKey={}", agentKey);
        }
    }

    private HarnessAgent build(String agentKey) {
        AgentDO agentDO = agentMapper.selectByAgentKey(agentKey);
        if (agentDO == null || agentDO.getStatus() == null || agentDO.getStatus() != 1) {
            throw new BizException("Agent 不存在或已停用：" + agentKey);
        }
        Path workspace = Path.of(properties.getAgentscope().getWorkspaceRoot()).resolve(agentKey);
        // Agent↔Skill 绑定过滤（SPEC §6.1 第 4 条）：空绑定 = 平台 skill 市场全集
        List<String> bound = agentSkillMapper.selectByAgentKey(agentKey)
                .stream().map(b -> b.getSkillName()).toList();
        SkillFilter skillFilter = bound.isEmpty()
                ? SkillFilter.all()
                : SkillFilter.only(bound.toArray(new String[0]));
        int trigger = agentDO.getCompactionTrigger() == null
                ? DEFAULT_COMPACTION_TRIGGER : agentDO.getCompactionTrigger();
        int keep = agentDO.getCompactionKeep() == null
                ? DEFAULT_COMPACTION_KEEP : agentDO.getCompactionKeep();

        log.info("构建 HarnessAgent agentKey={} modelId={} workspace={} boundSkills={}",
                agentKey, agentDO.getModelId(), workspace, bound);
        return HarnessAgent.builder()
                .name(agentKey)
                .description(agentDO.getDescription() == null ? agentDO.getName() : agentDO.getDescription())
                .sysPrompt(agentDO.getSysPrompt())
                .model(modelRegistry.resolve(agentDO.getModelId()))
                .workspace(workspace)
                .stateStore(stateStore)
                .skillRepository(skillRepositoryAgent)
                .skillFilter(skillFilter)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(trigger)
                        .keepMessages(keep)
                        .build())
                // 一期无沙箱：禁用 shell 工具，脚本类 skill 仅分发不执行（SPEC §1.1/风险 7）
                .disableShellTool()
                .build();
    }
}
