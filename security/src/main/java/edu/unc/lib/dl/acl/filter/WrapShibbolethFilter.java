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

import java.io.IOException;

import javax.annotation.PostConstruct;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter which wraps the request to provide access to shibboleth authentication properties,
 * which may be from spoofed sources if enabled for testing purposes.
 *
 * @author bbpennel
 *
 */
public class WrapShibbolethFilter extends OncePerRequestFilter implements ServletContextAware {
    private static final Logger log = LoggerFactory.getLogger(WrapShibbolethFilter.class);

    private boolean spoofEnabled = false;
    private String spoofEmailSuffix = "@localhost";

    @PostConstruct
    public void init() {
        if (spoofEnabled) {
            log.warn("****Warning: Application started with spoofing filter enabled****");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (spoofEnabled) {
            filterChain.doFilter(new SpoofShibbolethRequestWrapper(request, spoofEmailSuffix), response);
        } else {
            filterChain.doFilter(new ProxiedUserRequestWrapper(request), response);
        }
    }

    public boolean isSpoofEnabled() {
        return spoofEnabled;
    }

    public void setSpoofEnabled(boolean spoofEnabled) {
        this.spoofEnabled = spoofEnabled;
    }

    public void setSpoofEmailSuffix(String spoofEmailSuffix) {
        this.spoofEmailSuffix = spoofEmailSuffix;
    }
}
