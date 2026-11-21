package com.wenghong.notifyflow.identity.security;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyAuthenticationService service;

    public ApiKeyAuthenticationFilter(ApiKeyAuthenticationService service) {
        this.service = service;
    }


    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain

    ) throws ServletException, IOException
    {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if(header == null || !header.regionMatches(true, 0, "Bearer ", 0,7))
        {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        var principal = service.authenticate(header.substring(7));

        if(principal.isEmpty())
        {
             response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal.get(), null, List.of(new SimpleGrantedAuthority("EVENT_PUBLISH")));

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        try{
            filterChain.doFilter(request, response);
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

}
