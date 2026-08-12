package com.teamer.teapot.ai.rbac.filter;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.rbac.config.RbacProperties;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import com.teamer.teapot.ai.rbac.service.JwtService;
import com.teamer.teapot.ai.rbac.util.RbacPathMatcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器（SPEC §5.1：演进自老 RBACLoginFilter/RBACFilter 的 isAuthenticated 语义）。
 * 解析 Bearer accessToken → 组装 TeapotUser → ContextUtil.setUp；链尾 cleanUp。
 */
@Slf4j
public class RbacAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final RbacProperties properties;

    public RbacAuthFilter(JwtService jwtService, RbacProperties properties) {
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (isPermitted(uri)) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            FilterResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Result.CODE_UNAUTHORIZED, "未登录或登录已过期");
            return;
        }
        DecodedJWT jwt;
        try {
            jwt = jwtService.verify(authorization.substring(7));
        } catch (Exception e) {
            log.debug("JWT 校验失败 uri={} cause={}", uri, e.getMessage());
            FilterResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Result.CODE_UNAUTHORIZED, "未登录或登录已过期");
            return;
        }
        if (!JwtService.isType(jwt, JwtService.TYPE_ACCESS)) {
            FilterResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Result.CODE_UNAUTHORIZED, "token 类型错误");
            return;
        }
        TeapotUser user = new TeapotUser();
        user.setUserId(jwt.getClaim("uid").asString());
        user.setUsername(jwt.getClaim("uname").asString());
        user.setRoles(jwt.getClaim("roles").asString());
        ContextUtil.setUp(user);
        try {
            chain.doFilter(request, response);
        } finally {
            ContextUtil.cleanUp();
        }
    }

    private boolean isPermitted(String uri) {
        return properties.getPermitList().stream().anyMatch(p -> RbacPathMatcher.matches(p, uri));
    }
}
