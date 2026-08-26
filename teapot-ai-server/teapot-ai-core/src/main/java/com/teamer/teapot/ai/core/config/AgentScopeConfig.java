package com.teamer.teapot.ai.core.config;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.storage.OssClientManager;
import com.teamer.teapot.ai.core.storage.OssSkillRepository;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.sandbox.agentrun.AgentRunHarnessSandboxJacksonModule;
import io.agentscope.extensions.sandbox.e2b.E2bHarnessSandboxJacksonModule;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * 数据源与 AgentScope 基础设施装配（SPEC §6.2/§8.1）：
 * 业务库（@Primary，MyBatis 默认使用） + agentscope 库独立连接池
 * + MysqlAgentStateStore + 双 SkillRepository（管理可写 / Agent 只读）。
 * 注：自定义 DataSource Bean 会关闭 Spring Boot 数据源自动配置，
 * 故业务库必须显式声明并标 @Primary。
 */
@Configuration
@EnableConfigurationProperties(TeapotAiProperties.class)
public class AgentScopeConfig {

    /**
     * SandboxState 多态反序列化（SPEC §16.7 / 风险 17）：全局 JsonCodec 注册 AgentRun/E2B 子类型，
     * 否则跨 call 沙箱状态恢复失败。启动期一次性设置，幂等。
     */
    @PostConstruct
    void registerSandboxJacksonModule() {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        codec.getObjectMapper().registerModule(new AgentRunHarnessSandboxJacksonModule());
        codec.getObjectMapper().registerModule(new E2bHarnessSandboxJacksonModule());
        JsonUtils.setJsonCodec(codec);
    }

    /** 业务库 teapot_ai（spring.datasource.*） */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties teapotDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(destroyMethod = "close")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties teapotDataSourceProperties) {
        return teapotDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
    }

    @Bean(destroyMethod = "close")
    public DataSource agentscopeDataSource(TeapotAiProperties properties) {
        TeapotAiProperties.Agentscope.Datasource cfg = properties.getAgentscope().getDatasource();
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("agentscope-pool");
        ds.setJdbcUrl(cfg.getUrl());
        ds.setUsername(cfg.getUsername());
        ds.setPassword(cfg.getPassword());
        ds.setMaximumPoolSize(8);
        ds.setMinimumIdle(2);
        return ds;
    }

    /** 会话状态存储（createIfNotExist 自动建表，SPEC §6.2；宽校验子类允许沙箱 slot 带斜杠，§22.5） */
    @Bean
    public MysqlAgentStateStore agentStateStore(@Qualifier("agentscopeDataSource") DataSource ds,
                                                TeapotAiProperties properties) {
        return new LenientMysqlAgentStateStore(ds, properties.getAgentscope().isCreateIfNotExist());
    }

    /** 平台管理实例：writeable=true，Skill 工坊 CRUD 专用（SPEC §8.1） */
    @Bean
    public MysqlSkillRepository skillRepositoryAdmin(@Qualifier("agentscopeDataSource") DataSource ds,
                                                     TeapotAiProperties properties) {
        return MysqlSkillRepository.builder(ds)
                .createIfNotExist(properties.getAgentscope().isCreateIfNotExist())
                .writeable(true)
                .build();
    }

    /** 注入 Agent 的实例：writeable=false（官方生产清单：Agent 只读，写回走管理台） */
    @Bean
    public MysqlSkillRepository skillRepositoryAgent(@Qualifier("agentscopeDataSource") DataSource ds,
                                                     TeapotAiProperties properties) {
        return MysqlSkillRepository.builder(ds)
                .createIfNotExist(properties.getAgentscope().isCreateIfNotExist())
                .writeable(false)
                .build();
    }

    /**
     * Git Skill 仓库（第二 skill 来源，SPEC §15.6）：enabled=false 时不装配，零副作用；
     * 消费方一律 ObjectProvider 注入。remote-url 空则 fail-fast，避免静默降级。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "teapot.ai.skill-git", name = "enabled", havingValue = "true")
    public GitSkillRepository gitSkillRepository(TeapotAiProperties properties) {
        TeapotAiProperties.SkillGit cfg = properties.getSkillGit();
        if (cfg.getRemoteUrl() == null || cfg.getRemoteUrl().isBlank()) {
            throw new BizException("teapot.ai.skill-git.remote-url 未配置：Git Skill 已启用但仓库地址为空");
        }
        // 兜底子类：官方扫描只认 skillsRoot 下第一层子目录，
        // 根级 SKILL.md（单 skill 仓库布局）由 RootSkillAwareGitSkillRepository 补充识别
        return new RootSkillAwareGitSkillRepository(cfg.getRemoteUrl(), cfg.getBranch(),
                Path.of(cfg.getLocalPath()), cfg.getSource(), cfg.isAutoSync(),
                // 仓内 skill 目录根（如 .qoder/skills）；空串/null 由官方归一为自动探测
                cfg.getSkillsRoot());
    }

    /**
     * OSS Skill 来源（第三 skill 来源）：enabled=false 时不装配，零副作用；
     * 消费方一律 ObjectProvider 注入。凭证不齐时读路径按空集降级（仓库自身处理）。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "teapot.ai.skill-oss", name = "enabled", havingValue = "true")
    public OssSkillRepository ossSkillRepository(OssClientManager ossClientManager,
                                                 OssConnection ossConnection,
                                                 TeapotAiProperties properties) {
        return new OssSkillRepository(ossClientManager, ossConnection, properties.getSkillOss());
    }
}
