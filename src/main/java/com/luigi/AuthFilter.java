package com.luigi;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class AuthFilter implements Filter {

    // ── Rate limiting ──────────────────────────────────────────────
    // Bucket: ip:bucket -> [windowStartMs, count]
    private static final ConcurrentHashMap<String, long[]> RATE_MAP = new ConcurrentHashMap<>();
    private static final int RATE_ASK_PER_MIN = 20;   // /api/ask (costosa)
    private static final int RATE_API_PER_MIN  = 90;   // tutti gli altri /api/*

    private boolean isRateLimited(String ip, String bucket, int limit) {
        String key = ip + ":" + bucket;
        long now = System.currentTimeMillis();

        // Lazy cleanup per evitare memory leak
        if (RATE_MAP.size() > 8000) {
            RATE_MAP.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        }

        RATE_MAP.compute(key, (k, v) -> {
            if (v == null || now - v[0] > 60_000) return new long[]{now, 1};
            v[1]++;
            return v;
        });

        long[] data = RATE_MAP.get(key);
        return data != null && data[1] > limit;
    }

    // ── Security headers ────────────────────────────────────────────
    private static void addSecurityHeaders(HttpServletResponse res) {
        res.setHeader("X-Frame-Options",           "DENY");
        res.setHeader("X-Content-Type-Options",    "nosniff");
        res.setHeader("X-XSS-Protection",          "1; mode=block");
        res.setHeader("Referrer-Policy",           "strict-origin-when-cross-origin");
        res.setHeader("Permissions-Policy",        "camera=(), microphone=(), geolocation=()");
        res.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline' cdn.jsdelivr.net; " +
                "style-src 'self' 'unsafe-inline' cdn.jsdelivr.net; " +
                "img-src 'self' data: blob:; " +
                "connect-src 'self'; " +
                "object-src 'none'; " +
                "base-uri 'self'; " +
                "form-action 'self'");
    }

    // ── Filter ─────────────────────────────────────────────────────
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;

        addSecurityHeaders(res);

        String uri       = req.getRequestURI();
        String clientIp  = getClientIp(req);
        HttpSession session    = req.getSession(false);
        boolean isLoggedIn     = (session != null && session.getAttribute("user") != null);

        // Rate limiting solo sugli endpoint API
        if (uri.startsWith("/api/")) {
            String bucket = uri.equals("/api/ask") ? "ask" : "api";
            int    limit  = uri.equals("/api/ask") ? RATE_ASK_PER_MIN : RATE_API_PER_MIN;
            if (isRateLimited(clientIp, bucket, limit)) {
                res.setStatus(429);
                res.setContentType("text/plain;charset=UTF-8");
                res.getWriter().write("Troppe richieste. Riprova tra un minuto.");
                return;
            }
        }

        // Rotte pubbliche
        boolean isPublic = uri.equals("/login.html")
                || uri.equals("/login")
                || uri.startsWith("/auth/")
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/icons/")
                || uri.equals("/manifest.json")
                || uri.equals("/sw.js");

        if (uri.equals("/admin.html") && (!isLoggedIn || !"admin".equals(session.getAttribute("role")))) {
            res.sendRedirect("/login.html");
            return;
        }

        if (isLoggedIn || isPublic) {
            chain.doFilter(request, response);
        } else {
            res.sendRedirect("/login.html");
        }
    }

    private static String getClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
