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

import static edu.unc.lib.dl.acl.util.AccessPrincipalConstants.AUTHENTICATED_PRINC;
import static edu.unc.lib.dl.acl.util.AccessPrincipalConstants.PUBLIC_PRINC;
import static edu.unc.lib.dl.httpclient.HttpClientUtil.FORWARDED_MAIL_HEADER;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.GroupsThreadStore;
import edu.unc.lib.dl.httpclient.HttpClientUtil;

/**
 * Filter which retrieves the users shibboleth and grouper session information in order to construct their profile as
 * needed.
 *
 * @author bbpennel
 *
 */
public class StoreUserAccessControlFilter extends OncePerRequestFilter implements ServletContextAware {
    private static final Logger log = LoggerFactory.getLogger(StoreUserAccessControlFilter.class);

    protected static String FORWARDING_ROLE = "group-forwarding";

    private boolean retainGroupsThreadStore;

    @Override
    public void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException,
            ServletException {
        log.debug("In StoreUserAccessControlFilter");
        // Skip processing static content
        if (!req.getServletPath().startsWith("/static/")) {
            storeUserGroupData(req);
        }
        try {
            chain.doFilter(req, res);
        } finally {
            // Clear out group store no matter what happens
            if (!retainGroupsThreadStore) {
                GroupsThreadStore.clearStore();
            }
        }
    }

    protected void storeUserGroupData(HttpServletRequest request) {
        try {
            GroupsThreadStore.storeUsername(request.getRemoteUser());

            String email = getEmailAddress(request);
            GroupsThreadStore.storeEmail(email);

            AccessGroupSet accessGroups = getUserGroups(request);
            GroupsThreadStore.storeGroups(accessGroups);

            AgentPrincipals principals = GroupsThreadStore.getAgentPrincipals();
            request.setAttribute("accessGroupSet", principals.getPrincipals());

            if (log.isDebugEnabled()) {
                log.debug("Setting cdr groups for request processing thread: {}",
                        GroupsThreadStore.getGroupString());
            }
        } catch (Exception e) {
            log.debug("Error while retrieving the users profile", e);
        }
    }

    protected AccessGroupSet getUserGroups(HttpServletRequest request) {
        if (request.isUserInRole(FORWARDING_ROLE)) {
            return this.getForwardedGroups(request);
        } else {
            return this.getGrouperGroups(request);
        }
    }

    protected AccessGroupSet getForwardedGroups(HttpServletRequest request) {
        String forwardedGroups = request.getHeader(HttpClientUtil.FORWARDED_GROUPS_HEADER);
        if (log.isDebugEnabled()) {
            log.debug("Forwarding user {} logged in with forwarded groups {}",
                    GroupsThreadStore.getUsername(), forwardedGroups);
        }
        if (forwardedGroups == null) {
            return new AccessGroupSet();
        }

        if (forwardedGroups.trim().length() > 0) {
            return new AccessGroupSet(forwardedGroups);
        }
        return new AccessGroupSet();
    }

    protected AccessGroupSet getGrouperGroups(HttpServletRequest request) {
        String shibGroups = request.getHeader(HttpClientUtil.SHIBBOLETH_GROUPS_HEADER);
        AccessGroupSet accessGroups = null;
        String userName = GroupsThreadStore.getUsername();
        if (log.isDebugEnabled()) {
            log.debug("Normal user " + userName + " logged in with groups " + shibGroups);
        }

        if (shibGroups == null || shibGroups.trim().length() == 0) {
            accessGroups = new AccessGroupSet();
        } else {
            accessGroups = new AccessGroupSet(shibGroups);
        }

        accessGroups.addAccessGroup(PUBLIC_PRINC);
        if (userName != null && userName.length() > 0) {
            accessGroups.addAccessGroup(AUTHENTICATED_PRINC);
        }

        return accessGroups;
    }

    private String getEmailAddress(HttpServletRequest request) {
        String email = request.getHeader("mail");
        if (email == null && request.isUserInRole(FORWARDING_ROLE)) {
            email = request.getHeader(FORWARDED_MAIL_HEADER);
        }
        if (email != null) {
            if (email.endsWith("_UNC")) {
                email = email.substring(0, email.length() - 4);
            }
        }
        return email;
    }

    /**
     * @param retainGroupsThreadStore the retainGroupsThreadStore to set
     */
    public void setRetainGroupsThreadStore(boolean retainGroupsThreadStore) {
        this.retainGroupsThreadStore = retainGroupsThreadStore;
    }
}
