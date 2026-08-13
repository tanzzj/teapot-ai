package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.AgentSkillMapper;
import com.teamer.teapot.ai.core.model.AgentSkillBind;
import com.teamer.teapot.ai.core.model.dto.SkillSaveRequest;
import com.teamer.teapot.ai.core.model.vo.SkillDetailVO;
import com.teamer.teapot.ai.core.model.vo.SkillListVO;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 工坊（SPEC §8 + §15）：表单 ⇄ SKILL.md（frontmatter + body），
 * 落在 agentscope 库 agentscope_skills（MysqlSkillRepository 管理）；
 * Git 仓库为第二来源（只读，§15.8 双来源读、单来源写）。
 * 保存/删除后失效受影响 Agent 实例，下一轮对话生效。
 */
@Slf4j
@Service
public class SkillService {

    /** SKILL.md 体积上限（SPEC §8.2） */
    private static final int SKILL_MD_MAX_BYTES = 256 * 1024;
    /** 单资源体积上限（SPEC §8.2） */
    private static final int RESOURCE_MAX_BYTES = 1024 * 1024;

    private final MysqlSkillRepository skillRepositoryAdmin;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentRegistry agentRegistry;
    private final AuditService auditService;
    private final TeapotAiProperties properties;
    /** Git 来源（enabled=false 时缺席，SPEC §15.6） */
    private final ObjectProvider<GitSkillRepository> gitRepoProvider;
    /** 最近一次手动同步时间（进程内，SPEC §15.8 gitStatus） */
    private volatile Instant lastSyncAt;

    public SkillService(@Qualifier("skillRepositoryAdmin") MysqlSkillRepository skillRepositoryAdmin,
                        AgentSkillMapper agentSkillMapper,
                        AgentRegistry agentRegistry,
                        AuditService auditService,
                        TeapotAiProperties properties,
                        ObjectProvider<GitSkillRepository> gitRepoProvider) {
        this.skillRepositoryAdmin = skillRepositoryAdmin;
        this.agentSkillMapper = agentSkillMapper;
        this.agentRegistry = agentRegistry;
        this.auditService = auditService;
        this.properties = properties;
        this.gitRepoProvider = gitRepoProvider;
    }

    public List<SkillListVO> list() {
        // 双来源合并（SPEC §15.8）：同名去重 git 优先 + warn
        Map<String, SkillListVO> merged = new LinkedHashMap<>();
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        if (git != null) {
            gitSkillsSafe(git).forEach(skill -> merged.put(skill.getName(), toListVO(skill)));
        }
        for (AgentSkill skill : skillRepositoryAdmin.getAllSkills()) {
            if (merged.containsKey(skill.getName())) {
                log.warn("skill 同名冲突（git 优先展示）：{}，请通过改名或下线其一消除", skill.getName());
                continue;
            }
            merged.put(skill.getName(), toListVO(skill));
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(SkillListVO::getName))
                .toList();
    }

    public SkillDetailVO detail(String name) {
        // 与列表去重优先级一致：git 优先（SPEC §15.8），git 来源前端只读（§15.13）
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        AgentSkill skill = git == null ? null : gitSkillSafe(git, name);
        if (skill == null) {
            skill = skillRepositoryAdmin.getSkill(name);
        }
        if (skill == null) {
            throw new BizException("Skill 不存在：" + name);
        }
        return toDetailVO(skill);
    }

    public void save(SkillSaveRequest request) {
        // 同名守卫（SPEC §15.8）：与 Git 来源同名拒绝，修改走 PR 流程
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        if (git != null && git.skillExists(request.getName())) {
            throw new BizException("与 Git 仓库 skill 同名，请走 Git PR 流程修改：" + request.getName());
        }
        String content = assembleSkillMd(request);
        checkLimit("SKILL.md", content.getBytes(StandardCharsets.UTF_8).length, SKILL_MD_MAX_BYTES);
        Map<String, String> resources = new LinkedHashMap<>();
        if (request.getResources() != null) {
            for (SkillSaveRequest.SkillResourceItem item : request.getResources()) {
                checkLimit("资源 " + item.getPath(),
                        item.getContent().getBytes(StandardCharsets.UTF_8).length, RESOURCE_MAX_BYTES);
                if (resources.put(item.getPath(), item.getContent()) != null) {
                    throw new BizException("资源路径重复：" + item.getPath());
                }
            }
        }
        AgentSkill skill = new AgentSkill(request.getName(), request.getDescription(),
                content, resources, "platform");
        boolean saved = skillRepositoryAdmin.save(List.of(skill), true);
        if (!saved) {
            throw new BizException("Skill 保存失败：" + request.getName());
        }
        // 失效绑定该 skill 的 Agent 实例（下一轮对话加载新内容）
        invalidateBoundAgents(request.getName());
        auditService.log("skill.save", request.getName(), "resources=" + resources.size());
    }

    public void delete(String name) {
        AgentSkill skill = skillRepositoryAdmin.getSkill(name);
        if (skill == null) {
            GitSkillRepository git = gitRepoProvider.getIfAvailable();
            if (git != null && git.skillExists(name)) {
                throw new BizException("Git 来源 skill 不可在平台删除，请走 Git PR 流程：" + name);
            }
            throw new BizException("Skill 不存在：" + name);
        }
        boolean deleted = skillRepositoryAdmin.delete(name);
        if (!deleted) {
            throw new BizException("Skill 删除失败：" + name);
        }
        // 级联解绑所有 Agent（SPEC §8.3 delete）
        agentSkillMapper.deleteBySkillName(name);
        invalidateBoundAgents(name);
        auditService.log("skill.delete", name, null);
    }

    /** 预览生成的 SKILL.md（不落库，SPEC §8.3 preview） */
    public String preview(SkillSaveRequest request) {
        return assembleSkillMd(request);
    }

    /** Git 来源状态（SPEC §15.8/§15.9）：remote 脱敏（剥 userinfo） */
    public Map<String, Object> gitStatus() {
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        TeapotAiProperties.SkillGit cfg = properties.getSkillGit();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", git != null);
        status.put("remoteMasked", maskRemoteUrl(cfg.getRemoteUrl()));
        status.put("branch", cfg.getBranch());
        status.put("skillCount", git == null ? 0 : gitSkillsSafe(git).size());
        status.put("lastSyncAt", lastSyncAt == null ? null : lastSyncAt.toString());
        return status;
    }

    /** 手动同步（SPEC §15.9）：repo.sync() + 记录 lastSyncAt + 审计 skill.git.sync */
    public Map<String, Object> gitSync() {
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        if (git == null) {
            throw new BizException("Git Skill 未启用（teapot.ai.skill-git.enabled=false）");
        }
        git.sync();
        lastSyncAt = Instant.now();
        int count = gitSkillsSafe(git).size();
        auditService.log("skill.git.sync",
                maskRemoteUrl(properties.getSkillGit().getRemoteUrl()), "skillCount=" + count);
        return gitStatus();
    }

    /** 表单 → frontmatter + body（与种子数据格式一致） */
    private String assembleSkillMd(SkillSaveRequest request) {
        Map<String, String> frontmatter = new LinkedHashMap<>();
        frontmatter.put("name", request.getName());
        frontmatter.put("description", request.getDescription());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        // SafeConstructor 限制仅解析基础标量/容器，杜绝任意对象构造
        String yamlText = new Yaml(new SafeConstructor(new LoaderOptions()),
                new org.yaml.snakeyaml.representer.Representer(options), options).dump(frontmatter);
        return "---\n" + yamlText + "---\n\n" + request.getInstructions().strip() + "\n";
    }

    /** SKILL.md → frontmatter + body（detail 解析回表单） */
    private ParsedSkill parseSkillMd(String content) {
        ParsedSkill parsed = new ParsedSkill();
        if (content == null) {
            return parsed;
        }
        String text = content.stripLeading();
        if (!text.startsWith("---")) {
            parsed.body = content;
            return parsed;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            parsed.body = content;
            return parsed;
        }
        String yamlText = text.substring(3, end).strip();
        parsed.body = text.substring(end + 4).strip();
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(yamlText);
            if (loaded instanceof Map<?, ?> map) {
                Object description = map.get("description");
                parsed.description = description == null ? null : description.toString();
            }
        } catch (Exception e) {
            log.warn("SKILL.md frontmatter 解析失败，使用列值兜底", e);
        }
        return parsed;
    }

    private SkillListVO toListVO(AgentSkill skill) {
        SkillListVO vo = new SkillListVO();
        vo.setName(skill.getName());
        vo.setDescription(skill.getDescription());
        vo.setSource(skill.getSource());
        return vo;
    }

    private SkillDetailVO toDetailVO(AgentSkill skill) {
        SkillDetailVO vo = new SkillDetailVO();
        vo.setName(skill.getName());
        vo.setSource(skill.getSource());
        vo.setSkillContent(skill.getSkillContent());
        // SKILL.md 解析回表单（SPEC §8.3 detail）
        ParsedSkill parsed = parseSkillMd(skill.getSkillContent());
        vo.setDescription(parsed.description != null ? parsed.description : skill.getDescription());
        vo.setInstructions(parsed.body);
        List<SkillDetailVO.ResourceItem> resources = new ArrayList<>();
        Map<String, String> resourceMap = skill.getResources();
        if (resourceMap != null) {
            resourceMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        SkillDetailVO.ResourceItem item = new SkillDetailVO.ResourceItem();
                        item.setPath(entry.getKey());
                        item.setContent(entry.getValue());
                        resources.add(item);
                    });
        }
        vo.setResources(resources);
        return vo;
    }

    /** Git 全量读取降级（SPEC §15.11）：clone/网络失败返回空集，不影响平台来源 */
    private List<AgentSkill> gitSkillsSafe(GitSkillRepository git) {
        try {
            List<AgentSkill> skills = git.getAllSkills();
            return skills == null ? List.of() : skills;
        } catch (Exception e) {
            log.warn("Git skill 仓库读取失败，按空集处理", e);
            return List.of();
        }
    }

    private AgentSkill gitSkillSafe(GitSkillRepository git, String name) {
        try {
            return git.getSkill(name);
        } catch (Exception e) {
            log.warn("Git skill 读取失败 name={}", name, e);
            return null;
        }
    }

    /** remote 脱敏（SPEC §15.12）：剥离 userinfo（PAT/账号），scp 形式剥 user@ */
    static String maskRemoteUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int schemeIdx = url.indexOf("://");
        if (schemeIdx > 0) {
            String tail = url.substring(schemeIdx + 3);
            int atIdx = tail.indexOf('@');
            int slashIdx = tail.indexOf('/');
            if (atIdx >= 0 && (slashIdx < 0 || atIdx < slashIdx)) {
                return url.substring(0, schemeIdx + 3) + tail.substring(atIdx + 1);
            }
            return url;
        }
        // scp 形式 git@host:path
        int atIdx = url.indexOf('@');
        return atIdx >= 0 ? url.substring(atIdx + 1) : url;
    }

    private void invalidateBoundAgents(String skillName) {
        List<AgentSkillBind> binds = agentSkillMapper.selectBySkillName(skillName);
        for (AgentSkillBind bind : binds) {
            agentRegistry.invalidate(bind.getAgentKey());
        }
    }

    private void checkLimit(String label, int bytes, int limit) {
        if (bytes > limit) {
            throw new BizException(label + " 超出体积上限（当前 " + bytes + " 字节，上限 " + limit + " 字节）");
        }
    }

    private static class ParsedSkill {
        private String description;
        private String body;
    }
}
