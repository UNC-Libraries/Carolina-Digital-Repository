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

import java.security.Principal;
import java.util.Enumeration;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper which provides a proxied user as the remote user for the request.
 *
 * @author bbpennel
 */
public class ProxiedUserRequestWrapper extends HttpServletRequestWrapper {
    private static final Logger log = LoggerFactory.getLogger(ProxiedUserRequestWrapper.class);

    public static final String PROXY_USER = "BXC_PROXY_USER";

    private String remoteUser;

    /**
     * Construct wrapper
     */
    public ProxiedUserRequestWrapper(HttpServletRequest request) {
        super(request);
        remoteUser = request.getRemoteUser();
        log.error("First with remote user '{}'", remoteUser);
//        if (remoteUser == null) {
            remoteUser = request.getHeader(PROXY_USER);
//        }
        log.error("Initialized with remote user '{}'", remoteUser);
        Enumeration<String> headers = request.getHeaderNames();
        while (headers.hasMoreElements()) {
            String name = headers.nextElement();
            log.error("Header {}: {}", name, request.getHeader(name));
        }
    }

    @Override
    public String getRemoteUser() {
        log.error("Returning remote user {}", remoteUser);
        return remoteUser;
    }

    @Override
    public Principal getUserPrincipal() {
        final String user = remoteUser;

        return new Principal() {
            @Override
            public String getName() {
                return user;
            }
        };
    }
}
