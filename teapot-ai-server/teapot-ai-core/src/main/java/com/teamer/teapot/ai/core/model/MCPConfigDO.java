package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MCP Server 配置记录（表 t_mcp_config）。
 * transport=stdio 消费 command/args/env；streamable_http/sse 消费 url/headers。
 * args/env/headers 以 JSON 字符串存储。
 */
@Data
public class MCPConfigDO implements Serializable {

    private Long id;
    /** MCP server 名称（唯一标识） */
    private String name;
    /** 传输协议：stdio / streamable_http / sse */
    private String transport;
    /** stdio 启动命令 */
    private String command;
    /** stdio 命令参数（JSON 数组字符串） */
    private String args;
    /** 环境变量（JSON 对象字符串） */
    private String env;
    /** HTTP/SSE 远程 URL */
    private String url;
    /** HTTP 请求头（JSON 对象字符串） */
    private String headers;
    /** 是否启用 */
    private Boolean enabled;
    /** 描述 */
    private String description;
    private String remark;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
