package com.teamer.teapot.ai.core.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.model.AgentFeature;
import com.teamer.teapot.ai.core.model.MCPConfigDO;
import com.teamer.teapot.ai.core.service.MCPConfigService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 配置查询工具（Agent 工具，runtime.enableMcpConfig 开关挂载，见 {@link McpConfigToolMiddleware}）：
 * 只读查询本 Agent 的 MCP Server 配置与系统可用记录；
 * 凭据安全：env/headers 只回显键名不回显值，args/url 原样返回（与平台脱敏口径一致）。
 */
@Slf4j
public class McpConfigTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String agentKey;
    private final MCPConfigService mcpConfigService;
    /** 本 Agent 的 mcp 命名空间配置（装配时快照）；可能为 null */
    private final AgentFeature.MCP agentMcp;

    public McpConfigTools(String agentKey, MCPConfigService mcpConfigService, AgentFeature.MCP agentMcp) {
        this.agentKey = agentKey;
        this.mcpConfigService = mcpConfigService;
        this.agentMcp = agentMcp;
    }

    /** 列出本 Agent 已配置的 MCP Server 与系统可用记录概览 */
    @Tool(name = "list_mcp_servers",
            description = "List the MCP servers configured for this agent and the available system MCP records. "
                    + "Returns JSON with 'agentServers' (configured for this agent) and 'systemRecords' (reusable records).",
            readOnly = true, concurrencySafe = true)
    public String listMcpServers() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentKey", agentKey);
        List<Map<String, Object>> agentServers = new ArrayList<>();
        if (agentMcp != null && agentMcp.isEnabled() && agentMcp.getServers() != null) {
            for (AgentFeature.MCP.Server srv : agentMcp.getServers()) {
                Map<String, Object> item = new LinkedHashMap<>();
                if (srv.getRecord() != null && !srv.getRecord().isBlank()) {
                    item.put("source", "record");
                    item.put("record", srv.getRecord());
                } else {
                    item.put("source", "inline");
                    item.put("transport", srv.getTransport());
                    if (srv.getCommand() != null) {
                        item.put("command", srv.getCommand());
                    }
                    if (srv.getUrl() != null) {
                        item.put("url", srv.getUrl());
                    }
                }
                if (srv.getDescription() != null) {
                    item.put("description", srv.getDescription());
                }
                agentServers.add(item);
            }
        }
        result.put("agentServers", agentServers);
        List<Map<String, Object>> systemRecords = new ArrayList<>();
        for (MCPConfigDO row : mcpConfigService.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getName());
            item.put("transport", row.getTransport());
            item.put("enabled", Boolean.TRUE.equals(row.getEnabled()));
            if (row.getDescription() != null && !row.getDescription().isBlank()) {
                item.put("description", row.getDescription());
            }
            systemRecords.add(item);
        }
        result.put("systemRecords", systemRecords);
        return toJson(result);
    }

    /** 查询单条系统 MCP 记录详情（env/headers 仅回键名） */
    @Tool(name = "get_mcp_server",
            description = "Get details of one system MCP record by name (transport, command/args or url, "
                    + "env/header key names without values, description).",
            readOnly = true, concurrencySafe = true)
    public String getMcpServer(
            @ToolParam(name = "name", description = "System MCP record name, see list_mcp_servers")
                    String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("name 不能为空");
        }
        MCPConfigDO row = mcpConfigService.getByName(name.trim());
        if (row == null) {
            throw new BizException("MCP 记录不存在：" + name);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", row.getName());
        item.put("transport", row.getTransport());
        item.put("enabled", Boolean.TRUE.equals(row.getEnabled()));
        if (row.getCommand() != null) {
            item.put("command", row.getCommand());
        }
        List<String> args = MCPConfigService.parseArgs(row.getArgs());
        if (!args.isEmpty()) {
            item.put("args", args);
        }
        Map<String, String> env = MCPConfigService.parseMap(row.getEnv());
        if (!env.isEmpty()) {
            item.put("envKeys", new ArrayList<>(env.keySet()));
        }
        if (row.getUrl() != null) {
            item.put("url", row.getUrl());
        }
        Map<String, String> headers = MCPConfigService.parseMap(row.getHeaders());
        if (!headers.isEmpty()) {
            item.put("headerKeys", new ArrayList<>(headers.keySet()));
        }
        if (row.getDescription() != null && !row.getDescription().isBlank()) {
            item.put("description", row.getDescription());
        }
        return toJson(item);
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BizException("MCP 配置序列化失败：" + e.getMessage());
        }
    }
}
