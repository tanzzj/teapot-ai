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

    /** Git Skill 仓库（第二 skill 来源，SPEC §15.5） */
    private SkillGit skillGit = new SkillGit();

    /** OSS Skill 来源（第三 skill 来源：zip 导入 → OSS 对象挂载） */
    private SkillOss skillOss = new SkillOss();

    /** 沙箱全局配置（SPEC §16.5 修订：e2b / agent-run 双链路；凭证走 t_sys_config） */
    private Sandbox sandbox = new Sandbox();

    /** 图片存储策略（SPEC §20：base64 / oss；凭证走 t_sys_config） */
    private Storage storage = new Storage();

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
        /** 消息级落盘开关（SPEC-checkpoint-persist §3.2，键 teapot.ai.agentscope.checkpoint-per-message）：
         * 阶段入口 checkpoint；平台级可靠性策略，关闭仅用于压测对比 */
        private boolean checkpointPerMessage = true;
        /** Redis 存储拆分（SPEC §26 修订/§27）：会话状态与记忆文件系统各自独立开关 */
        private Redis redis = new Redis();

        @Data
        public static class Datasource {
            private String url;
            private String username;
            private String password;
        }

        @Data
        public static class Redis {
            private String host = "127.0.0.1";
            private int port = 6379;
            private String password;
            /** 会话状态存储（SPEC §26 修订）：true = Redis，false = MySQL（默认） */
            private boolean sessionStore = false;
            /** 记忆文件系统路由（SPEC §27）：true = MEMORY.md/memory/ 落 Redis，false = 纯本地 */
            private boolean memoryStore = false;
            /** 存量本地记忆一次性迁移（SPEC §27）：true 且 memory-store=true 时，启动扫描 workspace 导入 Redis（幂等） */
            private boolean migrateLegacyMemory = false;
            private String sessionKeyPrefix = "teapot:session:";
            private String memoryKeyPrefix = "teapot:memory:";
        }
    }

    /** Git Skill 仓库配置（SPEC §15.5；鉴权复用系统 git 配置） */
    @Data
    public static class SkillGit {
        /** 功能开关：false 时 Bean 不装配，零副作用 */
        private boolean enabled = false;
        /** 远程仓库地址（SSH/HTTPS）；私有仓凭证仅经系统 git 配置，不写此处 */
        private String remoteUrl;
        private String branch = "main";
        /** 本地 clone 路径（可再生，不纳入备份） */
        private String localPath;
        /** 列表/详情展示的 source 标识 */
        private String source = "git";
        /** 读操作轻量 ls-remote，HEAD 变化才 pull */
        private boolean autoSync = true;
        /** 仓内 skill 目录根（相对仓库根，如 .qoder/skills）；空 = 自动探测（skills/ 子目录或仓库根）。
         *  官方扫描只认 skillsRoot 下第一层子目录，嵌套布局（如 .qoder/skills/<name>/SKILL.md）必须显式指定 */
        private String skillsRoot;
    }

    /** OSS Skill 来源配置：凭证复用全局 OSS 链路（OssConnection：env > 激活记录 > 旧单键） */
    @Data
    public static class SkillOss {
        /** 功能开关：false 时 Bean 不装配，zip 导入入口同步关闭 */
        private boolean enabled = false;
        /** skill 对象 key 前缀，布局 {prefix}{skillName}/SKILL.md + 资源 */
        private String prefix = "teapot-ai/skills/";
        /** 列表/详情展示的 source 标识 */
        private String source = "oss";
        /** 读缓存 TTL（秒）：Agent 每轮重建走缓存，zip 导入/删除与手动刷新强制重载 */
        private long cacheTtlSeconds = 60;
    }

    /**
     * 沙箱全局配置（SPEC §16.5 修订）：e2b / agent-run 双链路均为配置项。
     * link 选择首选链路；首选未启用或凭证不齐时自动回落另一链路（AgentRegistry 路由）。
     * 凭证与连接参数在 t_sys_config（可被 env 覆盖），此处仅行为开关与默认值。
     */
    @Data
    public static class Sandbox {
        /** 首选链路：e2b / agentrun（大小写不敏感） */
        private String link = "e2b";
        private E2b e2b = new E2b();
        private Agentrun agentrun = new Agentrun();

        @Data
        public static class E2b {
            /** 链路开关：false 时即使凭证齐备也不走 E2B */
            private boolean enabled = true;
            /** envd connect 编解码：阿里云兼容端点仅支持 JSON（PROTO 会 400） */
            private String codec = "JSON";
            /** 全局默认工作区根（feature 未指定时用） */
            private String defaultWorkspaceRoot = "/home/user/workspace";
            /** 全局默认闲置超时（秒），feature 未指定时用 */
            private int defaultIdleTimeoutSeconds = 1800;
            /** 本地快照存放目录（LOCAL_SNAPSHOT 持久化用，服务器本地路径） */
            private String snapshotPath = "./workspace/sandbox-snapshots";
        }

        @Data
        public static class Agentrun {
            /** 链路开关：false 时即使凭证齐备也不走 AgentRun MCP */
            private boolean enabled = true;
            /** 本地快照存放目录（LOCAL_SNAPSHOT 持久化用，服务器本地路径） */
            private String snapshotPath = "./workspace/sandbox-snapshots";
            /** 全局默认工作区根（feature 未指定时用） */
            private String defaultWorkspaceRoot = "/home/agentscope/workspace";
            /** 全局默认闲置超时（秒），feature 未指定时用 */
            private int defaultIdleTimeoutSeconds = 1800;
        }
    }

    /**
     * 图片存储行为开关（SPEC §20.4）：策略与凭证在 t_sys_config（可被 env 覆盖），
     * 此处仅 yml 行为开关与默认值。
     */
    @Data
    public static class Storage {
        private Oss oss = new Oss();
        /** 头像承载 OSS 记录名（SPEC §23：引用 t_storage_config.name） */
        private String avatarRecord = "oss-cn-beijing.aliyuncs.com";
        /** 头像对象 key 前缀（与对话图片分开存放） */
        private String avatarKeyPrefix = "teapot-ai/avatars/";

        @Data
        public static class Oss {
            /** 总开关：false 时即使凭证齐备也不启用 OSS 策略 */
            private boolean enabled = true;
            /** 对象 key 前缀默认值（t_sys_config oss.key_prefix 可覆盖） */
            private String keyPrefix = "teapot-ai/chat-images/";
        }
    }
}
