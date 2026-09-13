package com.medcloud.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.common.api.ApiError;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.identity.application.AccountAccessPolicy;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "/api/auth/login", "/actuator/health", "/error");

    private final JwtService jwtService;
    private final AccountAccessPolicy accountAccessPolicy;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            AccountAccessPolicy accountAccessPolicy,
            ObjectMapper objectMapper
    ) {
        this.jwtService = jwtService;
        this.accountAccessPolicy = accountAccessPolicy;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PUBLIC_ENDPOINTS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                CurrentUser user = jwtService.parse(header.substring(7));
                accountAccessPolicy.requireActive(user);
                var authority = new SimpleGrantedAuthority("ROLE_" + user.role().name());
                var authentication = new UsernamePasswordAuthenticationToken(user, header.substring(7), List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (BusinessException exception) {
                SecurityContextHolder.clearContext();
                writeAccessError(request, response, exception.getCode());
                return;
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private void writeAccessError(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
            throws IOException {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ApiError(
                code.name(), code.message(), requestId == null ? "unknown" : requestId.toString(), Map.of()));
    }
}
