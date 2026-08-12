package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.dao.AgentSkillMapper;
import com.teamer.teapot.ai.core.model.AgentSkillBind;
import com.teamer.teapot.ai.core.model.dto.SkillSaveRequest;
import com.teamer.teapot.ai.core.model.vo.SkillDetailVO;
import com.teamer.teapot.ai.core.model.vo.SkillListVO;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 工坊（SPEC §8）：表单 ⇄ SKILL.md（frontmatter + body），
 * 落在 agentscope 库 agentscope_skills（MysqlSkillRepository 管理）。
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

    public SkillService(@Qualifier("skillRepositoryAdmin") MysqlSkillRepository skillRepositoryAdmin,
                        AgentSkillMapper agentSkillMapper,
                        AgentRegistry agentRegistry,
                        AuditService auditService) {
        this.skillRepositoryAdmin = skillRepositoryAdmin;
        this.agentSkillMapper = agentSkillMapper;
        this.agentRegistry = agentRegistry;
        this.auditService = auditService;
    }

    public List<SkillListVO> list() {
        return skillRepositoryAdmin.getAllSkills().stream()
                .sorted(Comparator.comparing(AgentSkill::getName))
                .map(skill -> {
                    SkillListVO vo = new SkillListVO();
                    vo.setName(skill.getName());
                    vo.setDescription(skill.getDescription());
                    vo.setSource(skill.getSource());
                    return vo;
                }).toList();
    }

    public SkillDetailVO detail(String name) {
        AgentSkill skill = skillRepositoryAdmin.getSkill(name);
        if (skill == null) {
            throw new BizException("Skill 不存在：" + name);
        }
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

    public void save(SkillSaveRequest request) {
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
