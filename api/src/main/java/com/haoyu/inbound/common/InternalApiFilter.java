package com.haoyu.inbound.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.http.server.RequestPath;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * /api/internal/** carries the writes that belong to other systems or to operators: carrier
 * milestones, warehouse receipts, purchase orders, shipping, refresh jobs. nginx does not route it
 * from the internet at all; this filter is the second layer. It runs before Spring MVC routes the
 * request, so without the token every method on every internal path gets the same bare 404 - a GET
 * on a POST-only route cannot reveal the route through a 405. The path is checked twice and either
 * match counts: with the same PathPattern matching Spring MVC routes with (segments decoded as UTF-8,
 * ';' parameters ignored), and as a plain prefix of the path decoded as UTF-8 with ';' parameters
 * removed and repeated slashes merged. Neither depends on the request's declared charset, which a
 * caller controls. Without a configured token the internal API is simply off.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class InternalApiFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Token";
    private static final PathPattern INTERNAL = PathPatternParser.defaultInstance.parse("/api/internal/**");
    private static final UrlPathHelper PATHS = new UrlPathHelper() {                 // removes ;params by default
        @Override
        protected String determineEncoding(HttpServletRequest request) {
            return "UTF-8";                     // never the body charset from Content-Type: Spring routes with UTF-8
        }
    };

    private final byte[] token;

    InternalApiFilter(AppProperties props) {
        this.token = props.internalToken() == null || props.internalToken().isBlank()
                ? null : props.internalToken().getBytes(StandardCharsets.UTF_8);
    }

    static boolean isInternal(String path) {
        String p = path.replaceAll("/{2,}", "/");
        return p.equals("/api/internal") || p.startsWith("/api/internal/");
    }

    static boolean isInternal(HttpServletRequest request) {
        try {
            boolean routed = INTERNAL.matches(RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication());
            return routed || isInternal(PATHS.getPathWithinApplication(request));
        } catch (RuntimeException unparseable) {
            return true;                        // fail closed: a path we cannot read is treated as internal
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isInternal(request) && !authorised(request)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authorised(HttpServletRequest request) {
        String presented = request.getHeader(HEADER);
        return token != null && presented != null
                && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));   // constant-time compare
    }
}
