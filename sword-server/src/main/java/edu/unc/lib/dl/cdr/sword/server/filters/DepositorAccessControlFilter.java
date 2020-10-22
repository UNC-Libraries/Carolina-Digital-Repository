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
package edu.unc.lib.dl.cdr.sword.server.filters;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.acl.filter.StoreUserAccessControlFilter;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AccessPrincipalConstants;
import edu.unc.lib.dl.cdr.sword.server.SwordConfigurationImpl;

/**
 * Extension of basic access control filter which specifically handles sword depositors, adding in the
 * <depositor-namespace>:<user-name> group and the generic groups
 *
 * @author bbpennel
 *
 */
public class DepositorAccessControlFilter extends StoreUserAccessControlFilter {
    private static final Logger log = LoggerFactory.getLogger(DepositorAccessControlFilter.class);
    private static final String DEPOSITOR_ROLE = "sword-depositor";
    private SwordConfigurationImpl swordConfig;

    @Override
    protected AccessGroupSet getUserGroups(HttpServletRequest request) {
        log.debug("Getting groups from depositor filter");
        if (request.isUserInRole(FORWARDING_ROLE)) {
            return this.getForwardedGroups(request);
        } else if (request.isUserInRole(DEPOSITOR_ROLE)) {
            return this.getDepositorGroups(request);
        } else {
            return this.getGrouperGroups(request);
        }
    }

    protected AccessGroupSet getDepositorGroups(HttpServletRequest request) {
        String user = request.getRemoteUser();
        log.debug("SWORD depositor user {} logged in", user);
        AccessGroupSet accessGroups = new AccessGroupSet();
        accessGroups.addAccessGroup(AccessPrincipalConstants.PUBLIC_PRINC);
        if (user != null) {
            accessGroups.addAccessGroup(swordConfig.getDepositorNamespace() + request.getRemoteUser());
            accessGroups.addAccessGroup(AccessPrincipalConstants.AUTHENTICATED_PRINC);
        }
        return accessGroups;
    }

    public void setSwordConfig(SwordConfigurationImpl swordConfig) {
        this.swordConfig = swordConfig;
    }
}
