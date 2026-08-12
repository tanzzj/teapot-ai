package com.teamer.teapot.ai.m0;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

import java.util.List;
import java.util.Map;

/**
 * M0 兼容性验证（SPEC §11.2 前置动作 2）：
 * AgentScope 2.0.1 在 MySQL 8.4 LTS 上 createIfNotExist 自动建表 + 读写往返。
 * 在服务器（与 DB 同机）执行：java -jar app.jar '&lt;teapot_ai 账号密码&gt;'
 * 输出 "M0 COMPAT CHECK: PASS" 即通过；异常堆栈则降级 SPEC 方案 B。
 */
public class M0CompatCheck {

    public static void main(String[] args) throws Exception {
        String password = args.length > 0 ? args[0] : System.getenv("TEAPOT_AI_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            System.err.println("usage: java -jar app.jar <teapot_ai db password>");
            System.exit(2);
        }
        HikariDataSource ds = createDs("agentscope", password);
        try {
            // 1. 会话状态存储：自动建表 + 写-读-删往返
            MysqlAgentStateStore store = new MysqlAgentStateStore(ds, true);
            String sessionId = "m0check:smoke-1";
            AgentState state = AgentState.builder()
                    .sessionId(sessionId).userId("m0check").summary("m0 smoke test").build();
            store.save(sessionId, "m0-agent", "agent-state", state);
            boolean exists = store.exists(sessionId, "m0-agent");
            var loaded = store.get(sessionId, "m0-agent", "agent-state", AgentState.class);
            System.out.println("[STATE] save ok, exists=" + exists
                    + ", load=" + (loaded.isPresent() ? "ok summary=" + loaded.get().getSummary() : "MISSING"));
            store.delete(sessionId, "m0-agent");
            store.close();

            // 2. Skill 仓库：自动建表 + 写-读-删往返
            MysqlSkillRepository repo = MysqlSkillRepository.builder(ds)
                    .createIfNotExist(true)
                    .writeable(true)
                    .build();
            AgentSkill skill = new AgentSkill(
                    "m0-check-skill", "M0 兼容性验证 skill", "# m0-check\nhello teapot-ai", Map.of());
            repo.save(List.of(skill), true);
            AgentSkill loadedSkill = repo.getSkill("m0-check-skill");
            System.out.println("[SKILL] save ok, load="
                    + (loadedSkill != null ? "ok name=" + loadedSkill.getName()
                    + " content=" + loadedSkill.getSkillContent().length() + "B" : "MISSING"));
            repo.delete("m0-check-skill");
            repo.close();

            boolean pass = exists && loaded.isPresent() && loadedSkill != null;
            System.out.println(pass ? "M0 COMPAT CHECK: PASS" : "M0 COMPAT CHECK: FAIL");
            if (!pass) {
                System.exit(1);
            }
        } finally {
            ds.close();
        }
    }

    private static HikariDataSource createDs(String db, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/" + db
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true");
        cfg.setUsername("teapot_ai");
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(4);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new HikariDataSource(cfg);
    }
}
