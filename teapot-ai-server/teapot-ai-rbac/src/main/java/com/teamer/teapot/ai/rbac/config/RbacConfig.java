package com.teamer.teapot.ai.rbac.config;

import com.teamer.teapot.ai.rbac.filter.RbacAccessFilter;
import com.teamer.teapot.ai.rbac.filter.RbacAuthFilter;
import com.teamer.teapot.ai.rbac.service.JwtService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RBAC 装配（SPEC §5）：启用配置属性 + 注册双过滤器（先认证后授权）。
 */
@Configuration
@EnableConfigurationProperties(RbacProperties.class)
public class RbacConfig {

    @Bean
    public FilterRegistrationBean<RbacAuthFilter> rbacAuthFilter(JwtService jwtService,
                                                                 RbacProperties properties) {
        FilterRegistrationBean<RbacAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new RbacAuthFilter(jwtService, properties));
        bean.addUrlPatterns("/api/*", "/agui/*");
        bean.setOrder(10);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RbacAccessFilter> rbacAccessFilter(RbacProperties properties) {
        FilterRegistrationBean<RbacAccessFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new RbacAccessFilter(properties));
        bean.addUrlPatterns("/api/*", "/agui/*");
        bean.setOrder(11);
        return bean;
    }
}
