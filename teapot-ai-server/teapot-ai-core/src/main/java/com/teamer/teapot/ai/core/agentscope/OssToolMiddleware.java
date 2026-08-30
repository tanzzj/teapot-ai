package com.teamer.teapot.ai.core.agentscope;

/**
 * OSS 文件能力中间件（runtime.enableOssFile 开关）：
 * 提供 upload_file / download_file 工具，并向 system prompt 注入 OSS 文件能力用法说明。
 */
public class OssToolMiddleware implements ToolProvidedMiddleware {

    private static final String USAGE = """
            ## OSS 文件能力
            你具备基于阿里云 OSS 的文件上传/下载工具：
            - upload_file：将工作区文件上传到 OSS，返回可公开访问的直链。当用户要求分享、导出文件，或需要某个生成文件的下载地址时，应使用该工具并把返回的 URL 回复给用户。
            - download_file：将外部文件（http(s) URL 或 OSS 对象 key）下载到工作区，供后续处理使用。
            注意：两个工具的文件路径均相对你的工作区根目录；上传单文件上限 20MB，下载单文件上限 50MB。""";

    private final OssFileTools tools;

    public OssToolMiddleware(OssFileTools tools) {
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
