package com.gogo.travel.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Sa-Token 全局配置：
 * 1. 路由鉴权拦截器（/api/auth/login 和 OPTIONS 预检请求放行）
 * 2. 通过 CorsFilter（Servlet Filter 级）处理 CORS，
 *    确保 OPTIONS 预检请求在触发 sa-token 鉴权之前就已放行
 *
 * @author Hollis
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    /** CORS 预检请求缓存时间（秒） */
    private static final long CORS_MAX_AGE_SECONDS = 3600L;

    /**
     * 使用 CorsFilter（Servlet Filter 级别）处理 CORS。
     * CorsFilter 在 Spring MVC 拦截器之前运行，确保浏览器 OPTIONS 预检请求
     * 不会触达 sa-token 鉴权逻辑，直接返回 200 + CORS 响应头。
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(false);
        config.setMaxAge(CORS_MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            SaRouter.match("/**")
                    .notMatch("/api/auth/login")
                    .check(r -> StpUtil.checkLogin());
        }) {
            @Override
            public boolean preHandle(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Object handler) throws Exception {
                // OPTIONS 预检请求无 Token，直接放行（CorsFilter 已处理完毕）
                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    return true;
                }
                return super.preHandle(request, response, handler);
            }

        }).addPathPatterns("/**");
    }
}
