package com.teamer.teapot.ai.core.config;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 兼容"单 skill 仓库"布局的 Git Skill 仓库（SPEC §15.6 修订）。
 *
 * <p>官方 {@link GitSkillRepository} 扫描只认 skillsRoot 下第一层子目录
 * （子目录内含 SKILL.md），不认根级 SKILL.md。而 Claude/Qoder 生态的
 * 单 skill 仓库惯例是 SKILL.md 直接放仓库根（配 references/ 等资源目录）。
 *
 * <p>本类在官方读路径之上做兜底：读操作先走官方扫描，结果之外再检查
 * 有效 skillsRoot 下的根级 SKILL.md，命中则按同名去重后并入。
 * 写操作沿用官方只读语义。
 */
@Slf4j
public class RootSkillAwareGitSkillRepository extends GitSkillRepository {

    private static final String SKILL_FILE_NAME = "SKILL.md";

    /** 与构造入参一致的本地 clone 路径（官方未暴露 getter，自留一份） */
    private final Path localPath;
    /** 仓内 skill 目录根（相对仓库根）；null/空 = 自动探测 */
    private final String skillsRoot;

    public RootSkillAwareGitSkillRepository(String remoteUrl, String branch, Path localPath,
                                            String source, boolean autoSync, String skillsRoot) {
        super(remoteUrl, branch, localPath, source, autoSync, skillsRoot);
        this.localPath = localPath;
        this.skillsRoot = skillsRoot == null || skillsRoot.isBlank() ? null : skillsRoot.trim();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        List<AgentSkill> skills;
        try {
            skills = new ArrayList<>(super.getAllSkills());
        } catch (RuntimeException e) {
            // skillsRoot 配置失效等异常不阻断兜底扫描
            log.warn("Git skill 官方扫描失败，尝试根级 SKILL.md 兜底：{}", e.getMessage());
            skills = new ArrayList<>();
        }
        AgentSkill rootSkill = loadRootSkill();
        if (rootSkill != null
                && skills.stream().noneMatch(s -> rootSkill.getName().equals(s.getName()))) {
            skills.add(rootSkill);
        }
        return skills;
    }

    @Override
    public List<String> getAllSkillNames() {
        List<String> names;
        try {
            names = new ArrayList<>(super.getAllSkillNames());
        } catch (RuntimeException e) {
            log.warn("Git skill 官方扫描失败，尝试根级 SKILL.md 兜底：{}", e.getMessage());
            names = new ArrayList<>();
        }
        AgentSkill rootSkill = loadRootSkill();
        if (rootSkill != null && !names.contains(rootSkill.getName())) {
            names.add(rootSkill.getName());
        }
        names.sort(String::compareTo);
        return names;
    }

    @Override
    public AgentSkill getSkill(String name) {
        RuntimeException officialFailure = null;
        try {
            AgentSkill skill = super.getSkill(name);
            if (skill != null) {
                return skill;
            }
        } catch (RuntimeException e) {
            officialFailure = e;
        }
        AgentSkill rootSkill = loadRootSkill();
        if (rootSkill != null && rootSkill.getName().equals(name)) {
            return rootSkill;
        }
        if (officialFailure != null) {
            throw officialFailure;
        }
        throw new IllegalArgumentException("Skill directory does not exist for skill name: " + name);
    }

    @Override
    public boolean skillExists(String skillName) {
        try {
            if (super.skillExists(skillName)) {
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("Git skill 官方探测失败，尝试根级 SKILL.md 兜底：{}", e.getMessage());
        }
        AgentSkill rootSkill = loadRootSkill();
        return rootSkill != null && rootSkill.getName().equals(skillName);
    }

    /**
     * 加载有效 skillsRoot 下的根级 SKILL.md（单 skill 仓库布局）。
     * 未命中或加载失败返回 null，绝不抛出（兜底路径不得放大故障面）。
     */
    private AgentSkill loadRootSkill() {
        try {
            Path root = resolveEffectiveRoot();
            if (root == null || !Files.isRegularFile(root.resolve(SKILL_FILE_NAME))) {
                return null;
            }
            return SkillFileSystemHelper.loadSkillFromDirectory(root, getSource());
        } catch (Exception e) {
            log.warn("根级 SKILL.md 兜底加载失败：{}", e.getMessage());
            return null;
        }
    }

    /** 复刻官方 skillsPath 解析：显式 skillsRoot &gt; skills/ 子目录 &gt; 仓库根 */
    private Path resolveEffectiveRoot() {
        if (localPath == null || !Files.isDirectory(localPath)) {
            return null;
        }
        if (skillsRoot != null) {
            Path explicit = localPath.resolve(skillsRoot);
            return Files.isDirectory(explicit) ? explicit : null;
        }
        Path skillsSub = localPath.resolve("skills");
        return Files.isDirectory(skillsSub) ? skillsSub : localPath;
    }
}
