package com.teamer.teapot.ai.core.agentscope;

/**
 * MCP 配置查询中间件（runtime.enableMcpConfig 开关）：
 * 提供 list_mcp_servers / get_mcp_server 只读工具，并向 system prompt 注入用法说明。
 */
public class McpConfigToolMiddleware implements ToolProvidedMiddleware {

    private static final String USAGE = """
            ## MCP 配置查询能力
            你具备查询 MCP（Model Context Protocol）Server 配置的只读工具：
            - list_mcp_servers：查看本 Agent 已配置的 MCP Server，以及系统中可引用的 MCP 记录清单。
            - get_mcp_server：按记录名查看某条系统 MCP 记录的详情（凭据类字段只返回键名，不返回值）。
            当用户询问"配置了哪些 MCP / MCP 连接信息"等问题时，使用上述工具如实回答；不要编造配置内容。""";

    private final McpConfigTools tools;

    public McpConfigToolMiddleware(McpConfigTools tools) {
        this.tools = tools;
    }

    @Override
    public Object providedTools() {
        return tools;
    }

    @Override
    public String toolUsageDescription() {
        return USAGE;
    }
}
