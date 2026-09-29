package com.nms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.common.Hashing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every {@code /api/**} request by its {@code X-API-Key}. Missing, unknown, and inactive keys
 * get {@code 401} and nothing further is processed. The key itself is never logged.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiClientRepository apiClients;
    private final ObjectMapper objectMapper;

    ApiKeyAuthFilter(ApiClientRepository apiClients, ObjectMapper objectMapper) {
        this.apiClients = apiClients;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        Optional<String> sourceSystem = key == null || key.isBlank()
                ? Optional.empty()
                : apiClients.findActiveSourceSystem(Hashing.sha256Hex(key));
        if (sourceSystem.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Missing or invalid API key");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), problem);
            return;
        }
        request.setAttribute(SourceSystemContext.ATTRIBUTE, sourceSystem.get());
        chain.doFilter(request, response);
    }
}
