package com.teamer.teapot.ai.core.config;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

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

    /** 会话状态存储（createIfNotExist 自动建表，SPEC §6.2） */
    @Bean
    public MysqlAgentStateStore agentStateStore(@Qualifier("agentscopeDataSource") DataSource ds,
                                                TeapotAiProperties properties) {
        return new MysqlAgentStateStore(ds, properties.getAgentscope().isCreateIfNotExist());
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
}
