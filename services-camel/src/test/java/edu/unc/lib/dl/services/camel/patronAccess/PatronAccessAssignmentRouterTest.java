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
package edu.unc.lib.dl.services.camel.patronAccess;

import static edu.unc.lib.dl.acl.util.AccessPrincipalConstants.AUTHENTICATED_PRINC;
import static edu.unc.lib.dl.acl.util.UserRole.canViewMetadata;
import static java.util.Arrays.asList;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import org.apache.camel.BeanInject;
import org.apache.camel.test.spring.CamelSpringTestSupport;
import org.jgroups.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.RoleAssignment;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.services.acl.PatronAccessAssignmentService;
import edu.unc.lib.dl.persist.services.acl.PatronAccessAssignmentService.PatronAccessAssignmentRequest;
import edu.unc.lib.dl.persist.services.acl.PatronAccessDetails;
import edu.unc.lib.dl.persist.services.acl.PatronAccessOperationSender;

/**
 * @author bbpennel
 */
public class PatronAccessAssignmentRouterTest extends CamelSpringTestSupport {
    private static final String USER = "someone";
    private static final String PRINCIPALS = "my:special:group;everyone;authenticated";

    @BeanInject(value = "patronAccessAssignmentService")
    private PatronAccessAssignmentService patronAccessAssignmentService;

    @BeanInject(value = "patronAccessOperationSender")
    private PatronAccessOperationSender patronAccessOperationSender;

    @Captor
    private ArgumentCaptor<PatronAccessAssignmentRequest> requestCaptor;

    @Before
    public void init() throws Exception {
        initMocks(this);
    }

    @Override
    protected AbstractApplicationContext createApplicationContext() {
        return new ClassPathXmlApplicationContext("spring-test/jms-context.xml", "/patron-access-test-context.xml");
    }

    @Test
    public void validMessageTest() throws Exception {
        AgentPrincipals agent = new AgentPrincipals(USER, new AccessGroupSet(PRINCIPALS));
        PID pid = PIDs.get(UUID.randomUUID().toString());
        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(AUTHENTICATED_PRINC, canViewMetadata)));

        PatronAccessAssignmentRequest request = new PatronAccessAssignmentRequest(agent, pid, accessDetails);
        patronAccessOperationSender.sendUpdateRequest(request);

        verify(patronAccessAssignmentService, timeout(1000)).updatePatronAccess(requestCaptor.capture());
        PatronAccessAssignmentRequest received = requestCaptor.getValue();

        assertEquals(pid, received.getTargetPid());
        assertEquals(agent.getPrincipals(), received.getAgent().getPrincipals());
        assertEquals(accessDetails.getRoles(), received.getAccessDetails().getRoles());
    }

    @Test
    public void insufficientPermissionsTest() throws Exception {
        AgentPrincipals agent = new AgentPrincipals(USER, new AccessGroupSet(PRINCIPALS));
        PID pid = PIDs.get(UUID.randomUUID().toString());
        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(AUTHENTICATED_PRINC, canViewMetadata)));

        when(patronAccessAssignmentService.updatePatronAccess(any(PatronAccessAssignmentRequest.class)))
                .thenThrow(new AccessRestrictionException());

        PatronAccessAssignmentRequest request = new PatronAccessAssignmentRequest(agent, pid, accessDetails);
        patronAccessOperationSender.sendUpdateRequest(request);

        verify(patronAccessAssignmentService, timeout(1000)).updatePatronAccess(requestCaptor.capture());
    }
}