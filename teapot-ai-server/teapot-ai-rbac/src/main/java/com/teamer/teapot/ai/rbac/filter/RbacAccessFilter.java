package com.teamer.teapot.ai.rbac.filter;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.rbac.config.RbacProperties;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import com.teamer.teapot.ai.rbac.util.RbacPathMatcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 资源-角色匹配过滤器（SPEC §5.1 第 2 条：老 RBACFilter.isAuthenticated 算法原样保留）。
 * 用户角色与资源所需角色取交集；URI 通配匹配；未匹配到资源默认拒绝。
 */
@Slf4j
public class RbacAccessFilter extends OncePerRequestFilter {

    private final RbacProperties properties;

    public RbacAccessFilter(RbacProperties properties) {
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
        TeapotUser user = ContextUtil.getUserFromContext();
        if (user == null) {
            // 理论上已被 RbacAuthFilter 拦截，防御性处理
            FilterResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Result.CODE_UNAUTHORIZED, "未登录或登录已过期");
            return;
        }
        List<String> userRoles = user.getRoleList();
        boolean allowed = properties.getResourceList().stream()
                .anyMatch(entry -> userRoles.contains(entry.getRoleId())
                        && entry.getResource().stream()
                        .anyMatch(pattern -> RbacPathMatcher.matches(pattern, uri)));
        if (!allowed) {
            log.info("RBAC 拒绝 uri={} user={} roles={}", uri, user.getUserId(), userRoles);
            FilterResponseWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                    Result.CODE_FORBIDDEN, "无权限访问该资源");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isPermitted(String uri) {
        return properties.getPermitList().stream().anyMatch(p -> RbacPathMatcher.matches(p, uri));
    }
}
