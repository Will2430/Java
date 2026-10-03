package com.capturetotext.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps every response with the name of the instance that served it. In
 * Kubernetes HOSTNAME is the Pod name, so `curl -I` against the Service shows
 * which replica kube-proxy picked -- the only way to see load balancing from
 * the client side.
 */
@Component
public class ServedByFilter extends OncePerRequestFilter {

    private final String instance = System.getenv().getOrDefault("HOSTNAME", "local");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Served-By", instance);
        chain.doFilter(request, response);
    }
}
