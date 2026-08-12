package com.teamer.teapot.ai.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Teapot AI 平台配置（前缀 teapot.ai，SPEC §13）。
 */
@Data
@ConfigurationProperties(prefix = "teapot.ai")
public class TeapotAiProperties {

    private Agentscope agentscope = new Agentscope();

    /** 管理台模型下拉白名单（provider:model，yml 维护不改代码，SPEC §6.4） */
    private List<String> modelPresets = new ArrayList<>();

    /** CORS 允许来源（SPEC §14.4，前端 dev server / 站点域名） */
    private List<String> corsAllowedOrigins = new ArrayList<>();

    @Data
    public static class Agentscope {
        private Datasource datasource = new Datasource();
        /** 每 agent 独立 workspace 的根目录 */
        private String workspaceRoot = "./workspace";
        private boolean createIfNotExist = true;

        @Data
        public static class Datasource {
            private String url;
            private String username;
            private String password;
        }
    }
}
