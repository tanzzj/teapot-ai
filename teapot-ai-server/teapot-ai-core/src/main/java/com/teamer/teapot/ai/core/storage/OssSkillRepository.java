package com.teamer.teapot.ai.core.storage;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.aliyun.sdk.service.oss2.models.ListObjectsV2Request;
import com.aliyun.sdk.service.oss2.models.ListObjectsV2Result;
import com.aliyun.sdk.service.oss2.models.ObjectSummary;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.OssConnection;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OSS Skill 仓库（第三 skill 来源）：zip 导入 → OSS 对象，按 Git 同款目录布局挂载为只读来源。
 *
 * <p>对象布局：{prefix}{skillName}/SKILL.md + {prefix}{skillName}/{resourcePath}。
 * 读路径带 TTL 缓存（Agent 每轮重建时命中缓存，避免每轮全量 List/Get）；
 * 写路径仅经 {@link #putSkill} / {@link #deleteSkill}（zip 导入/删除），不走 SDK 通用 save。
 * 读失败（凭证不齐/网络）按空集降级，不阻断对话装配（与 Git 来源一致）。
 */
@Slf4j
public class OssSkillRepository implements AgentSkillRepository {

    private static final String SKILL_FILE_NAME = "SKILL.md";
    /** skill 数量上限（防御异常 bucket） */
    private static final int MAX_SKILLS = 200;
    /** 单 skill 对象数上限 */
    private static final int MAX_ENTRIES_PER_SKILL = 100;
    /** 单资源体积上限（与 SkillService RESOURCE_MAX_BYTES 对齐） */
    private static final long MAX_RESOURCE_BYTES = 1024L * 1024;
    /** SKILL.md 体积上限（与 SkillService SKILL_MD_MAX_BYTES 对齐） */
    private static final long MAX_SKILL_MD_BYTES = 256L * 1024;

    private final OssClientManager ossClientManager;
    private final OssConnection ossConnection;
    private final TeapotAiProperties.SkillOss cfg;

    private volatile List<AgentSkill> cache = List.of();
    private volatile long cacheAt;
    private volatile Instant lastRefreshAt;
    private volatile boolean writeable = false;

    public OssSkillRepository(OssClientManager ossClientManager, OssConnection ossConnection,
                              TeapotAiProperties.SkillOss cfg) {
        this.ossClientManager = ossClientManager;
        this.ossConnection = ossConnection;
        this.cfg = cfg;
    }

    // ==================== 读路径（TTL 缓存） ====================

    @Override
    public List<AgentSkill> getAllSkills() {
        long ttlMillis = cfg.getCacheTtlSeconds() * 1000L;
        if (System.currentTimeMillis() - cacheAt > ttlMillis) {
            try {
                List<AgentSkill> loaded = load();
                cache = loaded;
                cacheAt = System.currentTimeMillis();
                lastRefreshAt = Instant.now();
            } catch (Exception e) {
                // 凭证不齐/网络失败：沿用旧缓存（首次为空集），不阻断对话装配
                log.warn("OSS skill 读取失败，按缓存兜底（size={}）", cache.size(), e);
                cacheAt = System.currentTimeMillis();
            }
        }
        return cache;
    }

    @Override
    public AgentSkill getSkill(String name) {
        return getAllSkills().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst().orElse(null);
    }

    @Override
    public List<String> getAllSkillNames() {
        return getAllSkills().stream().map(AgentSkill::getName).toList();
    }

    @Override
    public boolean skillExists(String name) {
        return getSkill(name) != null;
    }

    /** 强制重载缓存（zip 导入/删除后与手动刷新用） */
    public void refresh() {
        cacheAt = 0L;
        getAllSkills();
    }

    public Instant getLastRefreshAt() {
        return lastRefreshAt;
    }

    public String getBucket() {
        return ossConnection.getBucket();
    }

    public String getPrefix() {
        return normalizedPrefix();
    }

    private List<AgentSkill> load() {
        OSSClient client = ossClientManager.get();
        String bucket = ossConnection.getBucket();
        String prefix = normalizedPrefix();
        // 全量列出 prefix 下对象（分页）
        Map<String, ObjectSummary> objects = new LinkedHashMap<>();
        String token = null;
        do {
            ListObjectsV2Request.Builder rb = ListObjectsV2Request.newBuilder()
                    .bucket(bucket).prefix(prefix).maxKeys(1000L);
            if (token != null) {
                rb.continuationToken(token);
            }
            ListObjectsV2Result result = client.listObjectsV2(rb.build());
            if (result.contents() != null) {
                for (ObjectSummary summary : result.contents()) {
                    objects.put(summary.key(), summary);
                }
            }
            token = Boolean.TRUE.equals(result.isTruncated()) ? result.nextContinuationToken() : null;
        } while (token != null);
        // 按第一层目录分组（= skill 名）；prefix 直属散文件忽略
        Map<String, List<ObjectSummary>> bySkill = new TreeMap<>();
        for (ObjectSummary summary : objects.values()) {
            String rel = summary.key().substring(prefix.length());
            int slash = rel.indexOf('/');
            if (slash <= 0 || slash == rel.length() - 1) {
                continue;
            }
            bySkill.computeIfAbsent(rel.substring(0, slash), k -> new ArrayList<>()).add(summary);
        }
        List<AgentSkill> skills = new ArrayList<>();
        for (Map.Entry<String, List<ObjectSummary>> entry : bySkill.entrySet()) {
            if (skills.size() >= MAX_SKILLS) {
                log.warn("OSS skill 数量超上限 {}，后续忽略", MAX_SKILLS);
                break;
            }
            AgentSkill skill = loadSkill(client, bucket, prefix, entry.getKey(), entry.getValue());
            if (skill != null) {
                skills.add(skill);
            }
        }
        log.info("OSS skill 来源加载完成 bucket={} prefix={} count={}", bucket, prefix, skills.size());
        return skills;
    }

    private AgentSkill loadSkill(OSSClient client, String bucket, String prefix,
                                 String name, List<ObjectSummary> summaries) {
        if (summaries.size() > MAX_ENTRIES_PER_SKILL) {
            log.warn("OSS skill {} 对象数超上限 {}，跳过", name, MAX_ENTRIES_PER_SKILL);
            return null;
        }
        String skillMdKey = prefix + name + "/" + SKILL_FILE_NAME;
        ObjectSummary skillMdSummary = summaries.stream()
                .filter(s -> s.key().equals(skillMdKey))
                .findFirst().orElse(null);
        if (skillMdSummary == null) {
            log.warn("OSS skill {} 缺少 SKILL.md，跳过", name);
            return null;
        }
        String skillContent = readObject(client, bucket, skillMdSummary, MAX_SKILL_MD_BYTES);
        if (skillContent == null) {
            return null;
        }
        Map<String, String> resources = new LinkedHashMap<>();
        String dirPrefix = prefix + name + "/";
        for (ObjectSummary summary : summaries) {
            if (summary.key().equals(skillMdKey)) {
                continue;
            }
            String content = readObject(client, bucket, summary, MAX_RESOURCE_BYTES);
            if (content != null) {
                resources.put(summary.key().substring(dirPrefix.length()), content);
            }
        }
        // frontmatter 解析 name/description（与 SkillService 同规则）
        Map<String, String> frontmatter = parseFrontmatter(skillContent);
        String displayName = frontmatter.getOrDefault("name", name);
        String description = frontmatter.getOrDefault("description", "");
        return new AgentSkill(displayName, description, skillContent, resources, cfg.getSource());
    }

    /** 读对象为 UTF-8 文本；超限返回 null（告警跳过，不阻断其余 skill） */
    private String readObject(OSSClient client, String bucket, ObjectSummary summary, long maxBytes) {
        if (summary.size() != null && summary.size() > maxBytes) {
            log.warn("OSS 对象超体积上限，跳过 key={} size={} limit={}", summary.key(), summary.size(), maxBytes);
            return null;
        }
        try (GetObjectResult result = client.getObject(GetObjectRequest.newBuilder()
                .bucket(bucket).key(summary.key()).build());
             InputStream body = result.body()) {
            byte[] bytes = body.readAllBytes();
            if (bytes.length > maxBytes) {
                log.warn("OSS 对象超体积上限，跳过 key={} size={}", summary.key(), bytes.length);
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("OSS 对象读取失败 key={}", summary.key(), e);
            return null;
        }
    }

    // ==================== 写路径（zip 导入/删除专用） ====================

    /** 覆盖式写入 skill：先清空旧对象再逐个上传（避免旧 zip 残留文件） */
    public void putSkill(String name, Map<String, byte[]> files) {
        OSSClient client = ossClientManager.get();
        String bucket = ossConnection.getBucket();
        String dirPrefix = normalizedPrefix() + name + "/";
        deleteUnder(client, bucket, dirPrefix);
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String key = dirPrefix + entry.getKey();
            PutObjectRequest request = PutObjectRequest.newBuilder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(entry.getKey().endsWith(".md") ? "text/markdown" : "application/octet-stream")
                    .body(BinaryData.fromBytes(entry.getValue()))
                    .build();
            try {
                client.putObject(request);
            } catch (Exception e) {
                log.error("OSS skill 对象上传失败 key={}", key, e);
                throw new BizException("OSS skill 上传失败：" + e.getMessage());
            }
        }
        refresh();
        log.info("OSS skill 已写入 bucket={} dir={} files={}", bucket, dirPrefix, files.size());
    }

    /** 删除 skill：清空其前缀下全部对象 */
    public void deleteSkill(String name) {
        OSSClient client = ossClientManager.get();
        String bucket = ossConnection.getBucket();
        String dirPrefix = normalizedPrefix() + name + "/";
        int deleted = deleteUnder(client, bucket, dirPrefix);
        refresh();
        log.info("OSS skill 已删除 dir={} deleted={}", dirPrefix, deleted);
    }

    private int deleteUnder(OSSClient client, String bucket, String dirPrefix) {
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Request.Builder rb = ListObjectsV2Request.newBuilder()
                    .bucket(bucket).prefix(dirPrefix).maxKeys(1000L);
            if (token != null) {
                rb.continuationToken(token);
            }
            ListObjectsV2Result result = client.listObjectsV2(rb.build());
            if (result.contents() != null) {
                result.contents().forEach(s -> keys.add(s.key()));
            }
            token = Boolean.TRUE.equals(result.isTruncated()) ? result.nextContinuationToken() : null;
        } while (token != null);
        for (String key : keys) {
            try {
                client.deleteObject(DeleteObjectRequest.newBuilder().bucket(bucket).key(key).build());
            } catch (Exception e) {
                log.error("OSS 对象删除失败 key={}", key, e);
                throw new BizException("OSS 对象删除失败：" + e.getMessage());
            }
        }
        return keys.size();
    }

    // ==================== SDK 接口语义 ====================

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        throw new BizException("OSS 来源请通过 zip 导入写入（POST /api/skill/import）");
    }

    @Override
    public boolean delete(String name) {
        deleteSkill(name);
        return true;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("oss",
                nullToEmpty(ossConnection.getBucket()) + "/" + normalizedPrefix(), false);
    }

    @Override
    public String getSource() {
        return cfg.getSource();
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    // ==================== 工具 ====================

    private String normalizedPrefix() {
        String prefix = cfg.getPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "teapot-ai/skills/";
        }
        prefix = prefix.startsWith("/") ? prefix.substring(1) : prefix;
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /** 极简 frontmatter 解析：仅取 name/description 标量（SafeConstructor 限制），兼容 UTF-8 BOM */
    public static Map<String, String> parseFrontmatter(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        if (content == null) {
            return result;
        }
        if (content.startsWith("\ufeff")) {
            content = content.substring(1);
        }
        String text = content.stripLeading();
        if (!text.startsWith("---")) {
            return result;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return result;
        }
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(text.substring(3, end).strip());
            if (loaded instanceof Map<?, ?> map) {
                for (String key : new String[]{"name", "description"}) {
                    Object value = map.get(key);
                    if (value != null && !value.toString().isBlank()) {
                        result.put(key, value.toString().strip());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SKILL.md frontmatter 解析失败", e);
        }
        return result;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
