package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.model.MCPConfigDO;
import com.teamer.teapot.ai.core.service.MCPConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置接口（/api/mcp-config）：
 * CRUD + toggle 全部 admin（RBAC yml 未向 developer/viewer 放开）；
 * registry 轻量名单供 Agent 配置下拉（developer/viewer 可读）。
 */
@RestController
@RequestMapping("/api/mcp-config")
public class MCPController {

    private final MCPConfigService mcpConfigService;

    public MCPController(MCPConfigService mcpConfigService) {
        this.mcpConfigService = mcpConfigService;
    }

    /** 配置列表（仅 admin） */
    @GetMapping("/list")
    public Result<Map<String, Object>> list() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (MCPConfigDO row : mcpConfigService.list()) {
            Map<String, Object> item = toView(row);
            records.add(item);
        }
        result.put("records", records);
        return Result.ok(result);
    }

    /** 新建配置 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody MCPConfigDO record) {
        mcpConfigService.create(record);
        return list();
    }

    /** 更新配置 */
    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody MCPConfigDO record) {
        mcpConfigService.update(record);
        return list();
    }

    /** 删除配置 */
    @DeleteMapping("/{name}")
    public Result<Map<String, Object>> delete(@PathVariable("name") String name) {
        mcpConfigService.delete(name);
        return list();
    }

    /** 切换启用/禁用 */
    @PatchMapping("/toggle/{name}")
    public Result<Map<String, Object>> toggle(@PathVariable("name") String name,
                                              @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.getOrDefault("enabled", true);
        mcpConfigService.toggle(name, enabled);
        return list();
    }

    /** 轻量名单（developer/viewer 可读）：仅名称/transport/enabled，供 Agent 配置下拉选择 */
    @GetMapping("/registry")
    public Result<List<Map<String, Object>>> registry() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (MCPConfigDO row : mcpConfigService.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getName());
            item.put("transport", row.getTransport());
            item.put("enabled", row.getEnabled());
            item.put("description", row.getDescription());
            records.add(item);
        }
        return Result.ok(records);
    }

    /** DO → 视图 Map（args/env/headers 反序列化为结构化数据回传前端） */
    private Map<String, Object> toView(MCPConfigDO row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", row.getName());
        item.put("transport", row.getTransport());
        item.put("command", row.getCommand());
        item.put("args", MCPConfigService.parseArgs(row.getArgs()));
        item.put("env", MCPConfigService.parseMap(row.getEnv()));
        item.put("url", row.getUrl());
        item.put("headers", MCPConfigService.parseMap(row.getHeaders()));
        item.put("enabled", row.getEnabled());
        item.put("description", row.getDescription());
        item.put("remark", row.getRemark());
        item.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        return item;
    }
}
