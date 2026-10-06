package com.haoyu.inbound.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /api/internal/** carries the writes that belong to other systems or to operators: carrier
 * milestones, warehouse receipts, purchase orders, shipping, refresh jobs. nginx does not route it
 * from the internet at all; this check is the second layer, so a misrouted request still gets a 404.
 * Without a configured token the internal API is simply off.
 */
@Configuration
class InternalApiInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    static final String HEADER = "X-Internal-Token";

    private final byte[] token;

    InternalApiInterceptor(AppProperties props) {
        this.token = props.internalToken() == null || props.internalToken().isBlank()
                ? null : props.internalToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/internal/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String presented = request.getHeader(HEADER);
        boolean ok = token != null && presented != null
                && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));   // constant-time compare
        if (!ok) response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return ok;
    }
}
