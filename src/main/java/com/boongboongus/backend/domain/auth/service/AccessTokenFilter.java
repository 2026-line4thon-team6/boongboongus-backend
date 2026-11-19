package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.domain.auth.repository.AccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
class AccessTokenFilter extends OncePerRequestFilter {
    private final ServiceTokens tokens;
    private final JsonMapper json;
    private final AccountRepository accounts;

    AccessTokenFilter(ServiceTokens tokens, JsonMapper json, AccountRepository accounts) {
        this.tokens = tokens;
        this.json = json;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        boolean expired = false;
        if (bearer) {
            String userId = null;
            try {
                userId = tokens.accessSubject(authorization.substring(7));
            } catch (ServiceTokens.ExpiredAccessToken exception) {
                expired = true;
            }
            if (userId != null && !accounts.isDeleted(userId)) {
                String subject = userId;
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override
                    public Principal getUserPrincipal() {
                        return () -> subject;
                    }
                }, response);
                return;
            }
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (("/api/v1/me".equals(path) && List.of("GET", "HEAD", "PATCH", "DELETE").contains(request.getMethod()))
                || (List.of("GET", "HEAD").contains(request.getMethod()) && path.matches("/api/v1/jobs/[^/]+"))
                || ("POST".equals(request.getMethod()) && path.matches("/api/v1/jobs/[^/]+/cancel"))
                || (List.of("GET", "HEAD").contains(request.getMethod()) && "/api/v1/me/playlists".equals(path))
                || (List.of("PUT", "DELETE").contains(request.getMethod()) && path.matches("/api/v1/me/playlists/[^/]+"))
                || ("PATCH".equals(request.getMethod()) && path.matches("/api/v1/playlists/[^/]+/tracks"))
                || (List.of("GET", "HEAD", "POST", "PUT").contains(request.getMethod())
                        && ("/api/v1/game-sessions".equals(path) || path.startsWith("/api/v1/game-sessions/")))
                || (List.of("POST", "GET", "HEAD").contains(request.getMethod()) && "/api/v1/radio/episodes".equals(path))
                || (List.of("GET", "HEAD").contains(request.getMethod())
                        && path.matches("/api/v1/radio/episodes/[^/]+"))
                || ("POST".equals(request.getMethod()) && "/api/v1/playlists".equals(path))
                || (List.of("GET", "HEAD").contains(request.getMethod()) && path.matches("/api/v1/playlists/[^/]+"))
                || ("/api/v1/notifications".equals(path) && List.of("GET", "HEAD").contains(request.getMethod()))
                || ("PATCH".equals(request.getMethod()) && path.matches("/api/v1/notifications/[^/]+/read"))
                || ("/api/v1/couple-invites".equals(path) && "POST".equals(request.getMethod()))
                || ("DELETE".equals(request.getMethod()) && path.matches("/api/v1/couple-invites/[^/]+"))
                || (List.of("PUT", "DELETE").contains(request.getMethod())
                        && path.matches("/api/v1/devices/[^/]+/push-token"))
                || ("/api/v1/couple-invites/me".equals(path) && List.of("GET", "HEAD").contains(request.getMethod()))
                || ("POST".equals(request.getMethod())
                        && List.of("/api/v1/couples", "/api/v1/couples/restore").contains(path))
                || ("/api/v1/couples/me/mascot-name".equals(path) && "PATCH".equals(request.getMethod()))
                || ("/api/v1/couples/me".equals(path) && List.of("GET", "HEAD", "PATCH", "DELETE").contains(request.getMethod()))
                || ("POST".equals(request.getMethod()) && ("/api/v1/media/uploads".equals(path)
                        || path.matches("/api/v1/media/[^/]+/complete")))
                || (List.of("GET", "HEAD").contains(request.getMethod())
                        && path.matches("/api/v1/media/[^/]+/download-url"))
                || ("DELETE".equals(request.getMethod()) && path.matches("/api/v1/media/[^/]+"))
                || (List.of("GET", "HEAD").contains(request.getMethod())
                        && (path.matches("/api/v1/drives/[^/]+/card")
                            || path.matches("/api/v1/cards/[^/]+(/share)?")
                            || List.of("/api/v1/records", "/api/v1/records/map", "/api/v1/records/calendar").contains(path)))
                || ("DELETE".equals(request.getMethod()) && path.matches("/api/v1/cards/[^/]+"))
                || ("PATCH".equals(request.getMethod()) && path.matches("/api/v1/cards/[^/]+/draft"))
                || ("PUT".equals(request.getMethod()) && path.matches("/api/v1/cards/[^/]+/reflections/me"))
                || ("POST".equals(request.getMethod()) && path.matches("/api/v1/cards/[^/]+/complete"))
                || ("/api/v1/me/onboarding".equals(path) && "PUT".equals(request.getMethod()))
                || ("POST".equals(request.getMethod())
                        && List.of("/api/v1/auth/reauth", "/api/v1/auth/reauth/authorizations").contains(path))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(), ErrorResponse.of(
                    expired ? "ACCESS_TOKEN_EXPIRED" : "AUTHENTICATION_REQUIRED",
                    expired ? "액세스 토큰이 만료되었습니다." : "로그인이 필요합니다.", List.of()));
            return;
        }
        chain.doFilter(request, response);
    }
}
