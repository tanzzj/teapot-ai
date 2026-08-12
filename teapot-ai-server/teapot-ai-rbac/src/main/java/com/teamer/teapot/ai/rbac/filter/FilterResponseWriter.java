package com.teamer.teapot.ai.rbac.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamer.teapot.ai.common.model.Result;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 过滤器统一 JSON 响应输出（Result 包装）。
 */
final class FilterResponseWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FilterResponseWriter() {
    }

    static void write(HttpServletResponse response, int httpStatus, int code, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(Result.fail(code, message)));
    }
}
