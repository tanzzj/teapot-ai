package com.teamer.teapot.ai.rbac.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * RBAC 配置（SPEC §5.2 yml 形态，与老 rbac: 前缀同构）。
 */
@Data
@ConfigurationProperties(prefix = "rbac")
public class RbacProperties {

    /** 免鉴权白名单（URI 通配） */
    private List<String> permitList = new ArrayList<>();

    /** roleId → 资源（URI pattern）列表 */
    private List<ResourceEntry> resourceList = new ArrayList<>();

    private Jwt jwt = new Jwt();

    @Data
    public static class ResourceEntry {
        private String roleId;
        /** yml 中单值或列表均可绑定 */
        private List<String> resource = new ArrayList<>();
    }

    @Data
    public static class Jwt {
        /** 环境变量注入（RBAC_JWT_SECRET），不入库不入 git */
        private String secret;
        private Duration accessTokenTtl = Duration.ofHours(2);
        private Duration refreshTokenTtl = Duration.ofDays(7);
    }
}
