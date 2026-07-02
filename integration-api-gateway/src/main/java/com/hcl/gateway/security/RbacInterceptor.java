package com.hcl.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class RbacInterceptor implements HandlerInterceptor {

    private static final String ROLE_HEADER = "X-IVP-Role";
    private static final String ROLE_COOKIE = "IVP_ROLE";

    private final boolean enabled;
    private final SecurityRole defaultRole;

    public RbacInterceptor(
            @Value("${security.rbac.enabled}") boolean enabled,
            @Value("${security.rbac.default-role}") String defaultRole) {
        this.enabled = enabled;
        this.defaultRole = SecurityRole.from(defaultRole, SecurityRole.VIEWER);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!enabled) {
            return true;
        }
        SecurityRole required = requiredRole(request);
        if (required == null) {
            return true;
        }
        SecurityRole actual = SecurityRole.from(firstText(request.getHeader(ROLE_HEADER), cookieRole(request)), defaultRole);
        if (actual.includes(required)) {
            response.setHeader("X-IVP-Effective-Role", actual.name());
            return true;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("text/plain");
        response.getWriter().write("FORBIDDEN: " + required.name() + " role required");
        return false;
    }

    private SecurityRole requiredRole(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        if (path.startsWith("/intelligence/intent")) {
            return SecurityRole.VIEWER;
        }
        if (path.startsWith("/intelligence/execute") || path.startsWith("/intelligence/replay")) {
            return SecurityRole.OPERATOR;
        }
        if (path.equals("/intelligence/audits")
                || (path.startsWith("/intelligence/") && path.endsWith("/audit"))) {
            return SecurityRole.ADMIN;
        }
        if (path.startsWith("/intelligence/")) {
            return SecurityRole.VIEWER;
        }
        if (path.equals("/execute/executeAll") || (path.startsWith("/execute/") && path.endsWith("/stop"))) {
            return SecurityRole.OPERATOR;
        }
        if (path.startsWith("/execute/")) {
            return SecurityRole.VIEWER;
        }
        if ("POST".equalsIgnoreCase(method) && path.startsWith("/execute")) {
            return SecurityRole.OPERATOR;
        }
        return null;
    }

    private String cookieRole(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie != null && ROLE_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
