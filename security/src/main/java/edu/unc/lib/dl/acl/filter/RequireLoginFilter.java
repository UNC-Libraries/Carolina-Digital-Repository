/**
 * Copyright 2008 The University of North Carolina at Chapel Hill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.unc.lib.dl.acl.filter;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Http Filter which requires that the connection be made by an authenticated user
 *
 * @author bbpennel
 *
 */
public class RequireLoginFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequireLoginFilter.class);

    private String notLoggedInUrl;
    private boolean forwardRequest;

    @Override
    public void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String user = request.getRemoteUser();
        if (isBlank(user)) {
            if (forwardRequest) {
                RequestDispatcher dispatcher = request.getRequestDispatcher(notLoggedInUrl);
                dispatcher.forward(request, response);
            } else {
                response.sendRedirect(notLoggedInUrl);
            }
            return;
        } else {
            log.debug("User logged in as {}", user);
            chain.doFilter(request, response);
        }
    }

    public void setNotLoggedInUrl(String notLoggedInUrl) {
        this.notLoggedInUrl = notLoggedInUrl;
    }

    public void setForwardRequest(boolean forwardRequest) {
        this.forwardRequest = forwardRequest;
    }
}