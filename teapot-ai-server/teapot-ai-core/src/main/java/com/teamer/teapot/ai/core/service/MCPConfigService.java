package com.teamer.teapot.ai.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.dao.MCPConfigMapper;
import com.teamer.teapot.ai.core.model.MCPConfigDO;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置管理：
 * 支持 stdio（本地进程）/ streamable_http / sse（远程服务）三种传输协议；
 * args/env/headers 以 JSON 字符串存取。
 */
@Slf4j
@Service
public class MCPConfigService {

    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_STREAMABLE_HTTP = "streamable_http";
    public static final String TRANSPORT_SSE = "sse";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MCPConfigMapper mcpConfigMapper;
    private final AuditService auditService;

    public MCPConfigService(MCPConfigMapper mcpConfigMapper, AuditService auditService) {
        this.mcpConfigMapper = mcpConfigMapper;
        this.auditService = auditService;
    }

    /** 全部记录 */
    public List<MCPConfigDO> list() {
        return mcpConfigMapper.selectAll();
    }

    /** 按名取记录；不存在返回 null */
    public MCPConfigDO getByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return mcpConfigMapper.selectByName(name);
    }

    /** 新建记录：name 唯一 + transport 合法 + 按类型校验必填字段 */
    @Transactional(rollbackFor = Exception.class)
    public void create(MCPConfigDO record) {
        validateName(record.getName());
        if (mcpConfigMapper.selectByName(record.getName()) != null) {
            throw new BizException("MCP 配置名已存在：" + record.getName());
        }
        normalizeTransport(record);
        validateByTransport(record);
        if (record.getEnabled() == null) {
            record.setEnabled(true);
        }
        record.setUpdatedBy(operator());
        mcpConfigMapper.insert(record);
        auditService.log("mcp.config.create", record.getName(), "transport=" + record.getTransport());
    }

    /** 更新记录：null 字段保持原值 */
    @Transactional(rollbackFor = Exception.class)
    public void update(MCPConfigDO record) {
        validateName(record.getName());
        MCPConfigDO existing = mcpConfigMapper.selectByName(record.getName());
        if (existing == null) {
            throw new BizException("MCP 配置不存在：" + record.getName());
        }
        if (record.getTransport() != null) {
            normalizeTransport(record);
        }
        // 合并后校验
        String mergedTransport = notBlank(record.getTransport()) ? record.getTransport() : existing.getTransport();
        String mergedCommand = notBlank(record.getCommand()) ? record.getCommand() : existing.getCommand();
        String mergedUrl = notBlank(record.getUrl()) ? record.getUrl() : existing.getUrl();
        if (TRANSPORT_STDIO.equals(mergedTransport) && !notBlank(mergedCommand)) {
            throw new BizException("stdio 类型必须提供启动命令");
        }
        if ((TRANSPORT_STREAMABLE_HTTP.equals(mergedTransport) || TRANSPORT_SSE.equals(mergedTransport)) && !notBlank(mergedUrl)) {
            throw new BizException("HTTP/SSE 类型必须提供 URL");
        }
        record.setUpdatedBy(operator());
        mcpConfigMapper.updateByName(record);
        auditService.log("mcp.config.update", record.getName(), null);
    }

    /** 删除记录 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String name) {
        if (mcpConfigMapper.deleteByName(name) == 0) {
            throw new BizException("MCP 配置不存在：" + name);
        }
        auditService.log("mcp.config.delete", name, null);
    }

    /** 切换启用/禁用 */
    @Transactional(rollbackFor = Exception.class)
    public void toggle(String name, Boolean enabled) {
        MCPConfigDO existing = mcpConfigMapper.selectByName(name);
        if (existing == null) {
            throw new BizException("MCP 配置不存在：" + name);
        }
        mcpConfigMapper.toggleEnabled(name, enabled, operator());
        auditService.log("mcp.config.toggle", name, "enabled=" + enabled);
    }

    /** 按传输协议校验必填字段 */
    private static void validateByTransport(MCPConfigDO record) {
        if (TRANSPORT_STDIO.equals(record.getTransport())) {
            if (!notBlank(record.getCommand())) {
                throw new BizException("stdio 类型必须提供启动命令");
            }
        } else if (TRANSPORT_STREAMABLE_HTTP.equals(record.getTransport()) || TRANSPORT_SSE.equals(record.getTransport())) {
            if (!notBlank(record.getUrl())) {
                throw new BizException("HTTP/SSE 类型必须提供 URL");
            }
        }
    }

    /** 规范化 transport 值 */
    private static void normalizeTransport(MCPConfigDO record) {
        String t = record.getTransport() == null ? "" : record.getTransport().trim().toLowerCase();
        if (!TRANSPORT_STDIO.equals(t) && !TRANSPORT_STREAMABLE_HTTP.equals(t) && !TRANSPORT_SSE.equals(t)) {
            throw new BizException("transport 非法，可选值：stdio / streamable_http / sse");
        }
        record.setTransport(t);
    }

    private static void validateName(String name) {
        if (!notBlank(name) || name.trim().length() > 64) {
            throw new BizException("MCP 配置名必填且不超过 64 字符");
        }
    }

    /** JSON 字符串 → List<String>（args 反序列化） */
    public static List<String> parseArgs(String json) {
        if (!notBlank(json)) return new ArrayList<>();
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("MCP args JSON 解析失败，返回空列表：{}", json);
            return new ArrayList<>();
        }
    }

    /** JSON 字符串 → Map<String,String>（env/headers 反序列化） */
    public static Map<String, String> parseMap(String json) {
        if (!notBlank(json)) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("MCP JSON 解析失败，返回空 Map：{}", json);
            return Map.of();
        }
    }

    /** List/Map → JSON 字符串（序列化入库） */
    public static String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return JSON.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String operator() {
        return ContextUtil.currentUserId() == null ? "system" : ContextUtil.currentUserId();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
