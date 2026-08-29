package com.teamer.teapot.ai.core.config;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.storage.OssClientManager;
import com.teamer.teapot.ai.core.storage.OssSkillRepository;
import com.teamer.teapot.ai.core.storage.RedisMemoryFilesystems;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.sandbox.agentrun.AgentRunHarnessSandboxJacksonModule;
import io.agentscope.extensions.sandbox.e2b.E2bHarnessSandboxJacksonModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import redis.clients.jedis.JedisPooled;

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

    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

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

    /**
     * 共享 Redis 客户端（SPEC §26 修订/§27）：会话存储或记忆路由任一启用才装配；
     * JedisPooled 懒连接，Bean 存在不代表可达。消费方按开关自行取用。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${teapot.ai.agentscope.redis.session-store:false}' == 'true'"
            + " or '${teapot.ai.agentscope.redis.memory-store:false}' == 'true'")
    public JedisPooled teapotJedisPooled(TeapotAiProperties properties) {
        TeapotAiProperties.Agentscope.Redis redis = properties.getAgentscope().getRedis();
        return new JedisPooled(redis.getHost(), redis.getPort(), null, redis.getPassword());
    }

    /**
     * 会话状态存储（SPEC §26 修订）：session-store=true 走 Redis（RedisAgentStateStore，
     * 不校验 sessionId 斜杠，无需 §22.5 宽校验补丁）；默认 MySQL（宽校验子类）。
     * 切换后端后旧存储中的会话历史不迁移。
     */
    @Bean
    public AgentStateStore agentStateStore(@Qualifier("agentscopeDataSource") DataSource ds,
                                           ObjectProvider<JedisPooled> jedisProvider,
                                           TeapotAiProperties properties) {
        TeapotAiProperties.Agentscope.Redis redis = properties.getAgentscope().getRedis();
        if (redis.isSessionStore()) {
            return RedisAgentStateStore.builder()
                    .jedisClient(jedisProvider.getObject())
                    .keyPrefix(redis.getSessionKeyPrefix())
                    .build();
        }
        return new LenientMysqlAgentStateStore(ds, properties.getAgentscope().isCreateIfNotExist());
    }

    /**
     * 记忆文件系统路由（SPEC §27）：MEMORY.md / memory/ 路由到 Redis 叠加层（Redis 写层 +
     * 本地只读基线）。仅非沙箱 Agent 生效（2.0.1 沙箱文件系统无路由挂载点）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "teapot.ai.agentscope.redis", name = "memory-store", havingValue = "true")
    public RedisMemoryFilesystems redisMemoryFilesystems(TeapotAiProperties properties,
                                                         JedisPooled teapotJedisPooled) {
        return new RedisMemoryFilesystems(properties, teapotJedisPooled);
    }

    /**
     * 存量本地记忆一次性迁移（SPEC §27）：仅当 migrate-legacy-memory=true 且 memory-store=true
     * 时，启动后扫描 workspace 把旧 MEMORY.md/memory/* 导入 Redis（create-if-absent 幂等，可重跑）。
     * 迁移后路由不再读磁盘，Redis 为记忆唯一来源；磁盘文件保留作只读归档。
     */
    @Bean
    @ConditionalOnProperty(prefix = "teapot.ai.agentscope.redis", name = "migrate-legacy-memory", havingValue = "true")
    public ApplicationRunner legacyMemoryMigrationRunner(RedisMemoryFilesystems filesystems,
                                                         TeapotAiProperties properties) {
        return args -> {
            java.nio.file.Path root = java.nio.file.Path.of(properties.getAgentscope().getWorkspaceRoot());
            int[] r = filesystems.migrateLegacyMemory(root);
            log.info("存量记忆迁移结果 migrated={} skipped={}", r[0], r[1]);
        };
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
