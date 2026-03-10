package com.dataprocessing.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects any request missing the X-Account-ID header with 400.
 * Actuator paths are excluded via WebConfig.
 */
@Component
public class AccountIdInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String accountId = request.getHeader("X-Account-ID");
        if (accountId == null || accountId.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "X-Account-ID header is required");
            return false;
        }
        request.setAttribute("accountId", accountId);
        return true;
    }
}
