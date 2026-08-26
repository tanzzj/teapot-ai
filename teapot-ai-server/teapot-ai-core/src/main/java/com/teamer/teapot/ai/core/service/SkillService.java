package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.config.RootSkillAwareGitSkillRepository;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.AgentSkillMapper;
import com.teamer.teapot.ai.core.model.AgentSkillBind;
import com.teamer.teapot.ai.core.model.dto.SkillSaveRequest;
import com.teamer.teapot.ai.core.model.vo.SkillDetailVO;
import com.teamer.teapot.ai.core.model.vo.SkillListVO;
import com.teamer.teapot.ai.core.storage.OssSkillRepository;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    /** zip 导入限制：总体积 / 单文件 / 条目数 */
    private static final long ZIP_MAX_TOTAL_BYTES = 20L * 1024 * 1024;
    private static final long ZIP_MAX_ENTRY_BYTES = 2L * 1024 * 1024;
    private static final int ZIP_MAX_ENTRIES = 300;
    /** skill 名校验（与 SkillSaveRequest 一致） */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{2,64}$");
    /** zip 内路径白名单字符（zip-slip 防护第二道） */
    private static final Pattern PATH_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-/]{1,200}$");

    private final MysqlSkillRepository skillRepositoryAdmin;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentRegistry agentRegistry;
    private final AuditService auditService;
    private final TeapotAiProperties properties;
    /** Git 来源（enabled=false 时缺席，SPEC §15.6） */
    private final ObjectProvider<GitSkillRepository> gitRepoProvider;
    /** OSS 来源（enabled=false 时缺席：zip 导入 → OSS 对象挂载） */
    private final ObjectProvider<OssSkillRepository> ossRepoProvider;
    /** 最近一次手动同步时间（进程内，SPEC §15.8 gitStatus） */
    private volatile Instant lastSyncAt;

    public SkillService(@Qualifier("skillRepositoryAdmin") MysqlSkillRepository skillRepositoryAdmin,
                        AgentSkillMapper agentSkillMapper,
                        AgentRegistry agentRegistry,
                        AuditService auditService,
                        TeapotAiProperties properties,
                        ObjectProvider<GitSkillRepository> gitRepoProvider,
                        ObjectProvider<OssSkillRepository> ossRepoProvider) {
        this.skillRepositoryAdmin = skillRepositoryAdmin;
        this.agentSkillMapper = agentSkillMapper;
        this.agentRegistry = agentRegistry;
        this.auditService = auditService;
        this.properties = properties;
        this.gitRepoProvider = gitRepoProvider;
        this.ossRepoProvider = ossRepoProvider;
    }

    public List<SkillListVO> list() {
        // 多来源合并（SPEC §15.8 扩展）：同名去重优先级 git > oss > mysql + warn
        Map<String, SkillListVO> merged = new LinkedHashMap<>();
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        if (git != null) {
            gitSkillsSafe(git).forEach(skill -> merged.put(skill.getName(), toListVO(skill)));
        }
        OssSkillRepository oss = ossRepoProvider.getIfAvailable();
        if (oss != null) {
            for (AgentSkill skill : ossSkillsSafe(oss)) {
                if (merged.putIfAbsent(skill.getName(), toListVO(skill)) != null) {
                    log.warn("skill 同名冲突（git 优先展示）：{}，请通过改名或下线其一消除", skill.getName());
                }
            }
        }
        for (AgentSkill skill : skillRepositoryAdmin.getAllSkills()) {
            if (merged.containsKey(skill.getName())) {
                log.warn("skill 同名冲突（git/oss 优先展示）：{}，请通过改名或下线其一消除", skill.getName());
                continue;
            }
            merged.put(skill.getName(), toListVO(skill));
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(SkillListVO::getName))
                .toList();
    }

    public SkillDetailVO detail(String name) {
        // 与列表去重优先级一致：git > oss（SPEC §15.8 扩展），git/oss 来源前端只读
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        AgentSkill skill = git == null ? null : gitSkillSafe(git, name);
        if (skill == null) {
            OssSkillRepository oss = ossRepoProvider.getIfAvailable();
            skill = oss == null ? null : ossSkillsSafe(oss).stream()
                    .filter(s -> s.getName().equals(name)).findFirst().orElse(null);
        }
        if (skill == null) {
            skill = mysqlSkillSafe(name);
        }
        if (skill == null) {
            throw new BizException("Skill 不存在：" + name);
        }
        return toDetailVO(skill);
    }

    public void save(SkillSaveRequest request) {
        // 同名守卫（SPEC §15.8 扩展）：与 Git/OSS 来源同名拒绝，修改分别走 PR / zip 重导入
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        if (git != null && git.skillExists(request.getName())) {
            throw new BizException("与 Git 仓库 skill 同名，请走 Git PR 流程修改：" + request.getName());
        }
        OssSkillRepository oss = ossRepoProvider.getIfAvailable();
        if (oss != null && oss.skillExists(request.getName())) {
            throw new BizException("与 OSS 来源 skill 同名，请重新导入 zip 修改：" + request.getName());
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
        AgentSkill skill = mysqlSkillSafe(name);
        if (skill == null) {
            // OSS 来源：平台托管（zip 导入），可删（清空对象前缀）
            OssSkillRepository oss = ossRepoProvider.getIfAvailable();
            if (oss != null && oss.skillExists(name)) {
                try {
                    oss.deleteSkill(name);
                } catch (BizException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BizException("OSS skill 删除失败：" + e.getMessage());
                }
                agentSkillMapper.deleteBySkillName(name);
                invalidateBoundAgents(name);
                auditService.log("skill.delete", name, "source=oss");
                return;
            }
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

    /** OSS 来源状态：bucket/前缀/skill 数/最近刷新时间 */
    public Map<String, Object> ossStatus() {
        OssSkillRepository oss = ossRepoProvider.getIfAvailable();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", oss != null);
        status.put("bucket", oss == null ? null : oss.getBucket());
        status.put("prefix", oss == null ? null : oss.getPrefix());
        status.put("skillCount", oss == null ? 0 : ossSkillsSafe(oss).size());
        status.put("lastRefreshAt",
                oss == null || oss.getLastRefreshAt() == null ? null : oss.getLastRefreshAt().toString());
        return status;
    }

    /** 手动刷新 OSS 来源缓存（zip 导入/删除已自动刷新，此接口供凭证变更后强制重载） */
    public Map<String, Object> ossRefresh() {
        OssSkillRepository oss = ossRepoProvider.getIfAvailable();
        if (oss == null) {
            throw new BizException("OSS Skill 来源未启用（teapot.ai.skill-oss.enabled=false）");
        }
        oss.refresh();
        int count = ossSkillsSafe(oss).size();
        auditService.log("skill.oss.refresh", oss.getBucket() + "/" + oss.getPrefix(), "skillCount=" + count);
        return ossStatus();
    }

    /**
     * zip 导入（双落点）：target=oss 写 OSS 对象（同名覆盖，先清旧对象）；
     * target=mysql 存平台库（同名 upsert）。支持单 skill（根级 SKILL.md）与多 skill（首层目录各含 SKILL.md）布局。
     */
    public Map<String, Object> importSkill(MultipartFile file, String target) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请上传非空 zip 文件");
        }
        String normalizedTarget = target == null || target.isBlank() ? "oss" : target.trim().toLowerCase();
        if (!normalizedTarget.equals("oss") && !normalizedTarget.equals("mysql")) {
            throw new BizException("target 仅支持 oss / mysql");
        }
        boolean toOss = normalizedTarget.equals("oss");
        OssSkillRepository oss = ossRepoProvider.getIfAvailable();
        if (toOss && oss == null) {
            throw new BizException("OSS Skill 来源未启用（teapot.ai.skill-oss.enabled=false）");
        }
        Map<String, Map<String, byte[]>> skills = splitSkills(stripCommonRoot(readZip(file)));
        if (skills.isEmpty()) {
            throw new BizException("zip 中未找到 SKILL.md：支持单 skill（根级 SKILL.md）或多 skill（首层目录各含 SKILL.md）");
        }
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        List<String> imported = new ArrayList<>();
        for (Map.Entry<String, Map<String, byte[]>> entry : skills.entrySet()) {
            String name = entry.getKey();
            Map<String, byte[]> files = entry.getValue();
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw new BizException("skill 名不合法（" + name + "）：仅允许字母数字下划线中划线，2-64 位");
            }
            if (git != null && git.skillExists(name)) {
                throw new BizException("与 Git 仓库 skill 同名，请走 Git PR 流程：" + name);
            }
            String content = stripBom(new String(files.get("SKILL.md"), StandardCharsets.UTF_8));
            checkLimit("SKILL.md", files.get("SKILL.md").length, SKILL_MD_MAX_BYTES);
            String description = OssSkillRepository.parseFrontmatter(content).getOrDefault("description", "");
            if (toOss) {
                if (mysqlSkillExists(name)) {
                    throw new BizException("平台库已有同名 skill「" + name + "」，请先删除或改选落库平台库");
                }
                oss.putSkill(name, files);
            } else {
                if (oss != null && oss.skillExists(name)) {
                    throw new BizException("OSS 来源已有同名 skill「" + name + "」，请重新导入 zip 修改");
                }
                Map<String, String> resources = new LinkedHashMap<>();
                for (Map.Entry<String, byte[]> f : files.entrySet()) {
                    if ("SKILL.md".equals(f.getKey())) {
                        continue;
                    }
                    checkLimit("资源 " + f.getKey(), f.getValue().length, RESOURCE_MAX_BYTES);
                    resources.put(f.getKey(), stripBom(new String(f.getValue(), StandardCharsets.UTF_8)));
                }
                AgentSkill skill = new AgentSkill(name, description.isBlank() ? name : description,
                        content, resources, "platform");
                if (!skillRepositoryAdmin.save(List.of(skill), true)) {
                    throw new BizException("Skill 保存失败：" + name);
                }
            }
            invalidateBoundAgents(name);
            imported.add(name);
        }
        auditService.log("skill.import", String.join(",", imported), "target=" + normalizedTarget);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", normalizedTarget);
        result.put("imported", imported);
        return result;
    }

    /**
     * 任意 Git 仓库导入：临时 clone 到系统临时目录，读出全部 skill 后按 zip 导入同款
     * 同名守卫与落点规则入库，结束即删除临时目录（不落盘常驻）。
     */
    public Map<String, Object> importFromGit(String url, String branch, String target) {
        if (url == null || url.isBlank()) {
            throw new BizException("请输入 Git 仓库地址");
        }
        String trimmedUrl = url.trim();
        if (!(trimmedUrl.startsWith("https://") || trimmedUrl.startsWith("http://")
                || trimmedUrl.startsWith("git@"))) {
            throw new BizException("仓库地址仅支持 https:// 或 git@ 形式");
        }
        String trimmedBranch = branch == null || branch.isBlank() ? null : branch.trim();
        String normalizedTarget = target == null || target.isBlank() ? "mysql" : target.trim().toLowerCase();
        if (!normalizedTarget.equals("oss") && !normalizedTarget.equals("mysql")) {
            throw new BizException("target 仅支持 oss / mysql");
        }
        boolean toOss = normalizedTarget.equals("oss");
        OssSkillRepository oss = ossRepoProvider.getIfAvailable();
        if (toOss && oss == null) {
            throw new BizException("OSS Skill 来源未启用（teapot.ai.skill-oss.enabled=false）");
        }
        GitSkillRepository configuredGit = gitRepoProvider.getIfAvailable();
        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("teapot-skill-git-import-");
        } catch (IOException e) {
            throw new BizException("临时目录创建失败：" + e.getMessage());
        }
        List<AgentSkill> skills;
        RootSkillAwareGitSkillRepository repo = new RootSkillAwareGitSkillRepository(
                trimmedUrl, trimmedBranch, tmpDir, "git", false, null);
        try {
            skills = repo.getAllSkills();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("任意 Git 仓库导入失败 url={}", maskRemoteUrl(trimmedUrl), e);
            throw new BizException("Git 仓库拉取失败：" + e.getMessage()
                    + "（请确认地址可公开访问、分支存在，且仓库内含 SKILL.md）");
        } finally {
            try {
                repo.close();
            } catch (Exception e) {
                log.warn("临时 Git 仓库关闭失败", e);
            }
            deleteRecursivelyQuiet(tmpDir);
        }
        if (skills == null || skills.isEmpty()) {
            throw new BizException("仓库中未找到 SKILL.md：需位于根级或首层子目录");
        }
        List<String> imported = new ArrayList<>();
        for (AgentSkill skill : skills) {
            String name = skill.getName();
            if (name == null || !NAME_PATTERN.matcher(name).matches()) {
                throw new BizException("skill 名不合法（" + name + "）：仅允许字母数字下划线中划线，2-64 位");
            }
            if (configuredGit != null && configuredGit.skillExists(name)) {
                throw new BizException("与 Git 来源 skill 同名，请走 Git PR 流程：" + name);
            }
            String description = skill.getDescription() == null ? "" : skill.getDescription();
            if (toOss) {
                if (mysqlSkillExists(name)) {
                    throw new BizException("平台库已有同名 skill「" + name + "」，请先删除或改选落库平台库");
                }
                Map<String, byte[]> files = new LinkedHashMap<>();
                files.put("SKILL.md", skill.getSkillContent().getBytes(StandardCharsets.UTF_8));
                skill.getResources().forEach((path, content) ->
                        files.put(path, content.getBytes(StandardCharsets.UTF_8)));
                oss.putSkill(name, files);
            } else {
                if (oss != null && oss.skillExists(name)) {
                    throw new BizException("OSS 来源已有同名 skill「" + name + "」，请重新导入 zip 修改");
                }
                AgentSkill platformSkill = new AgentSkill(name, description.isBlank() ? name : description,
                        skill.getSkillContent(), skill.getResources(), "platform");
                if (!skillRepositoryAdmin.save(List.of(platformSkill), true)) {
                    throw new BizException("Skill 保存失败：" + name);
                }
            }
            invalidateBoundAgents(name);
            imported.add(name);
        }
        auditService.log("skill.git.import", maskRemoteUrl(trimmedUrl),
                "target=" + normalizedTarget + ", imported=" + String.join(",", imported));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", normalizedTarget);
        result.put("imported", imported);
        return result;
    }

    /** 递归删除临时 clone 目录（失败仅告警，tmp 由系统兜底回收） */
    private void deleteRecursivelyQuiet(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("临时文件删除失败：{}", p);
                    }
                });
            }
        } catch (IOException e) {
            log.warn("临时目录清理失败：{}", dir, e);
        }
    }

    /** 读 zip 条目（zip-slip 防护：拒 .. / 绝对路径 / 白名单外字符；体积与条目数上限） */
    private LinkedHashMap<String, byte[]> readZip(MultipartFile file) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) {
                    continue;
                }
                String path = normalizeZipPath(ze.getName());
                if (path == null) {
                    continue;
                }
                if (entries.size() >= ZIP_MAX_ENTRIES) {
                    throw new BizException("zip 条目数超上限（" + ZIP_MAX_ENTRIES + "）");
                }
                byte[] bytes = zis.readNBytes((int) ZIP_MAX_ENTRY_BYTES + 1);
                if (bytes.length > ZIP_MAX_ENTRY_BYTES) {
                    throw new BizException("单文件超上限（2MB）：" + path);
                }
                total += bytes.length;
                if (total > ZIP_MAX_TOTAL_BYTES) {
                    throw new BizException("zip 总体积超上限（20MB）");
                }
                if (entries.put(path, bytes) != null) {
                    throw new BizException("zip 内路径重复：" + path);
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException("zip 解析失败：" + e.getMessage());
        }
        if (entries.isEmpty()) {
            throw new BizException("zip 内无有效文件");
        }
        return entries;
    }

    /** zip 路径规范化：反斜杠转正、剔首斜杠、拒 ..；跳过 macOS 杂物 */
    private String normalizeZipPath(String raw) {
        String path = raw.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isBlank() || path.contains("..")
                || path.startsWith("__MACOSX/") || path.endsWith(".DS_Store")) {
            return null;
        }
        if (!PATH_PATTERN.matcher(path).matches()) {
            log.warn("zip 路径含非法字符，跳过：{}", raw);
            return null;
        }
        return path;
    }

    /** 剥离公共单一根目录（my-skill/... 或 skills/... 包裹层） */
    private LinkedHashMap<String, byte[]> stripCommonRoot(LinkedHashMap<String, byte[]> entries) {
        String root = null;
        for (String path : entries.keySet()) {
            int slash = path.indexOf('/');
            if (slash <= 0) {
                return entries;
            }
            String top = path.substring(0, slash);
            if (root == null) {
                root = top;
            } else if (!root.equals(top)) {
                return entries;
            }
        }
        LinkedHashMap<String, byte[]> stripped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            stripped.put(e.getKey().substring(root.length() + 1), e.getValue());
        }
        return stripped;
    }

    /** 按布局拆分为 skill 集：根级 SKILL.md = 单 skill（名取 frontmatter）；否则首层目录各一个 */
    private Map<String, Map<String, byte[]>> splitSkills(LinkedHashMap<String, byte[]> entries) {
        Map<String, Map<String, byte[]>> skills = new LinkedHashMap<>();
        if (entries.containsKey("SKILL.md")) {
            String content = new String(entries.get("SKILL.md"), StandardCharsets.UTF_8);
            String name = OssSkillRepository.parseFrontmatter(content).get("name");
            if (name == null || name.isBlank()) {
                throw new BizException("单 skill zip 的 SKILL.md frontmatter 必须声明 name");
            }
            skills.put(name, entries);
            return skills;
        }
        Map<String, Map<String, byte[]>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            int slash = e.getKey().indexOf('/');
            if (slash <= 0) {
                continue;
            }
            grouped.computeIfAbsent(e.getKey().substring(0, slash), k -> new LinkedHashMap<>())
                    .put(e.getKey().substring(slash + 1), e.getValue());
        }
        for (Map.Entry<String, Map<String, byte[]>> g : grouped.entrySet()) {
            if (!g.getValue().containsKey("SKILL.md")) {
                log.warn("zip 目录 {} 无 SKILL.md，跳过", g.getKey());
                continue;
            }
            String content = new String(g.getValue().get("SKILL.md"), StandardCharsets.UTF_8);
            String fmName = OssSkillRepository.parseFrontmatter(content).get("name");
            String name = fmName != null && !fmName.isBlank() ? fmName : g.getKey();
            if (skills.containsKey(name)) {
                throw new BizException("zip 内 skill 名重复：" + name);
            }
            skills.put(name, g.getValue());
        }
        return skills;
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

    /** OSS 全量读取降级：凭证不齐/网络失败返回空集，不影响其他来源 */
    private List<AgentSkill> ossSkillsSafe(OssSkillRepository oss) {
        try {
            List<AgentSkill> skills = oss.getAllSkills();
            return skills == null ? List.of() : skills;
        } catch (Exception e) {
            log.warn("OSS skill 读取失败，按空集处理", e);
            return List.of();
        }
    }

    /** 平台库单读降级：SDK getSkill 缺失时抛异常（非 null），统一转 null */
    private AgentSkill mysqlSkillSafe(String name) {
        try {
            return skillRepositoryAdmin.getSkill(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** 平台库存在性：SDK getSkill 缺失时抛异常（非 null），以异常判定 */
    private boolean mysqlSkillExists(String name) {
        return mysqlSkillSafe(name) != null;
    }

    /** 剥 UTF-8 BOM（Windows 工具链生成的 SKILL.md 常见） */
    private static String stripBom(String s) {
        return s != null && s.startsWith("\ufeff") ? s.substring(1) : s;
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
