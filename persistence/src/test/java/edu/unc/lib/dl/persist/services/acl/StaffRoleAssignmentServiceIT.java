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
package edu.unc.lib.dl.persist.services.acl;

import static edu.unc.lib.dl.acl.util.AccessPrincipalConstants.AUTHENTICATED_PRINC;
import static edu.unc.lib.dl.acl.util.AccessPrincipalConstants.PUBLIC_PRINC;
import static edu.unc.lib.dl.acl.util.UserRole.canAccess;
import static edu.unc.lib.dl.acl.util.UserRole.canManage;
import static edu.unc.lib.dl.acl.util.UserRole.canViewOriginals;
import static edu.unc.lib.dl.acl.util.UserRole.unitOwner;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.exception.InvalidAssignmentException;
import edu.unc.lib.dl.acl.fcrepo4.InheritedAclFactory;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.acl.util.RoleAssignment;
import edu.unc.lib.dl.acl.util.UserRole;
import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.ContentRootObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.RepositoryInitializer;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fcrepo4.RepositoryPaths;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.fedora.ServiceException;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.rdf.CdrAcl;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.rdf.Prov;
import edu.unc.lib.dl.services.OperationsMessageSender;
import edu.unc.lib.dl.test.AclModelBuilder;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.JMSMessageUtil.CDRActions;

/**
 *
 * @author bbpennel
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/cdr-client-container.xml"),
    @ContextConfiguration("/spring-test/staff-role-service-container.xml"),
})
public class StaffRoleAssignmentServiceIT {

    private static final String USER_PRINC = "user";
    private static final String GRP_PRINC = "group";
    private static final Calendar TOMORROW = GregorianCalendar.from(ZonedDateTime.now().plusDays(1));

    @Autowired
    private String baseAddress;
    @Mock
    private AccessControlService aclService;
    @Autowired
    private RepositoryObjectFactory repoObjFactory;
    @Autowired
    private RepositoryObjectLoader repoObjLoader;
    @Autowired
    private InheritedAclFactory aclFactory;
    @Autowired
    private RepositoryPIDMinter pidMinter;
    @Mock
    private OperationsMessageSender operationsMessageSender;
    @Autowired
    private RepositoryObjectTreeIndexer treeIndexer;
    @Autowired
    private TransactionManager txManager;
    @Autowired
    private RepositoryInitializer repoInitializer;
    @Captor
    private ArgumentCaptor<List<PID>> pidListCaptor;

    private AgentPrincipals agent;
    private AccessGroupSet groups;

    private StaffRoleAssignmentService roleService;

    private ContentRootObject contentRoot;

    @Before
    public void init() throws Exception {
        initMocks(this);
        TestHelper.setContentBase(baseAddress);

        groups = new AccessGroupSet(GRP_PRINC);
        agent = new AgentPrincipals(USER_PRINC, groups);

        roleService = new StaffRoleAssignmentService();
        roleService.setAclFactory(aclFactory);
        roleService.setAclService(aclService);
        roleService.setOperationsMessageSender(operationsMessageSender);
        roleService.setRepositoryObjectLoader(repoObjLoader);
        roleService.setRepositoryObjectFactory(repoObjFactory);
        roleService.setTransactionManager(txManager);

        PID contentRootPid = RepositoryPaths.getContentRootPid();
        repoInitializer.initializeRepository();
        contentRoot = repoObjLoader.getContentRootObject(contentRootPid);
    }

    @Test(expected = AccessRestrictionException.class)
    public void testInsufficientPermissions() throws Exception {
        PID pid = pidMinter.mintContentPid();

        doThrow(new AccessRestrictionException()).when(aclService)
            .assertHasAccess(anyString(), eq(pid), any(AccessGroupSet.class), eq(Permission.assignStaffRoles));

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(USER_PRINC, canAccess)));

        roleService.updateRoles(agent, pid, assignments);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNoAgent() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(pid, null);
        contentRoot.addMember(unit);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(USER_PRINC, canAccess)));

        roleService.updateRoles(null, pid, assignments);
    }

    @Test
    public void testEmptyAssignmentSet() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(pid, null);
        contentRoot.addMember(unit);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>();

        roleService.updateRoles(agent, pid, assignments);

        AdminUnit updated = repoObjLoader.getAdminUnit(pid);

        assertNoStaffRoles(updated);

        assertMessageSent(pid);

        String eventDetail = assertEventCreatedAndGetDetail(updated);
        assertThat(eventDetail, containsString("No roles assigned"));
    }

    @Test
    public void testEmptyAssignmentForObjectWithRoles() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(pid,
                new AclModelBuilder("Collection with existing acls")
                .addCanManage(GRP_PRINC)
                .addEmbargoUntil(TOMORROW)
                .model);
        unit.addMember(coll);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>();

        roleService.updateRoles(agent, pid, assignments);

        CollectionObject updated = repoObjLoader.getCollectionObject(pid);

        assertNoStaffRoles(updated);
        // Verify that non-staff role acl was not cleared
        assertEmbargoPresent(updated);

        assertMessageSent(pid);

        String eventDetail = assertEventCreatedAndGetDetail(updated);
        assertThat(eventDetail, containsString("No roles assigned"));
    }

    @Test
    public void testSetUnitOwnerForAdminUnit() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(pid, null);
        contentRoot.addMember(unit);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(USER_PRINC, unitOwner)));

        roleService.updateRoles(agent, pid, assignments);

        AdminUnit updated = repoObjLoader.getAdminUnit(pid);

        assertHasAssignment(USER_PRINC, unitOwner, updated);

        assertMessageSent(pid);

        String eventDetail = assertEventCreatedAndGetDetail(updated);
        assertThat(eventDetail, containsString(unitOwner.name() + ": " + USER_PRINC));
    }

    @Test(expected = InvalidAssignmentException.class)
    public void testSetUnitOwnerForCollection() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(pid, null);
        unit.addMember(coll);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(USER_PRINC, unitOwner)));

        roleService.updateRoles(agent, pid, assignments);
    }

    @Test(expected = InvalidAssignmentException.class)
    public void testSetCanManageForFolder() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(null);
        unit.addMember(coll);
        FolderObject folder = repoObjFactory.createFolderObject(pid, null);
        coll.addMember(folder);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(USER_PRINC, canManage)));

        roleService.updateRoles(agent, pid, assignments);
    }

    @Test(expected = InvalidAssignmentException.class)
    public void testSetCanManageForWork() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(null);
        unit.addMember(coll);
        WorkObject work = repoObjFactory.createWorkObject(pid, null);
        coll.addMember(work);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(USER_PRINC, canManage)));

        roleService.updateRoles(agent, pid, assignments);
    }

    @Test
    public void testOverrideRoleAssignments() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(pid,
                new AclModelBuilder("Admin Unit Replace")
                .addCanManage(GRP_PRINC)
                .model);
        contentRoot.addMember(unit);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(USER_PRINC, canManage)));

        roleService.updateRoles(agent, pid, assignments);

        AdminUnit updated = repoObjLoader.getAdminUnit(pid);

        assertHasAssignment(USER_PRINC, canManage, updated);
        assertNoAssignment(GRP_PRINC, canManage, updated);

        assertMessageSent(pid);

        String eventDetail = assertEventCreatedAndGetDetail(updated);
        assertThat(eventDetail, containsString(canManage.name() + ": " + USER_PRINC));
    }

    @Test(expected = InvalidAssignmentException.class)
    public void testPrincipalWithMultipleRoles() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(pid, null);
        unit.addMember(coll);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(USER_PRINC, canManage),
                new RoleAssignment(USER_PRINC, canAccess)));

        roleService.updateRoles(agent, pid, assignments);
    }

    @Test
    public void testRoleWithMultiplePrincipals() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(pid, null);
        unit.addMember(coll);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(USER_PRINC, canManage),
                new RoleAssignment(GRP_PRINC, canManage)));

        roleService.updateRoles(agent, pid, assignments);

        CollectionObject updated = repoObjLoader.getCollectionObject(pid);

        assertHasAssignment(USER_PRINC, canManage, updated);
        assertHasAssignment(GRP_PRINC, canManage, updated);

        assertMessageSent(pid);

        String eventDetail = assertEventCreatedAndGetDetail(updated);
        assertThat(eventDetail, containsString(
                canManage.name() + ": " + GRP_PRINC + ", " + USER_PRINC));
    }

    @Test
    public void testWithInheritedRole() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(
                new AclModelBuilder("Admin Unit With Owner")
                .addUnitOwner(USER_PRINC)
                .addCanManage("another")
                .model);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(pid, null);
        unit.addMember(coll);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(GRP_PRINC, canManage)));

        roleService.updateRoles(agent, pid, assignments);

        CollectionObject updated = repoObjLoader.getCollectionObject(pid);

        assertHasAssignment(GRP_PRINC, canManage, updated);
        // Inherited unit owner should not appear on the child
        assertNoAssignment(USER_PRINC, unitOwner, updated);

        assertMessageSent(pid);

        // Inherited and local roles should be logged
        String eventDetail = assertEventCreatedAndGetDetail(updated);
        assertThat(eventDetail, containsString(
                canManage.name() + ": another, " + GRP_PRINC));
        assertThat(eventDetail, containsString(
                unitOwner.name() + ": " + USER_PRINC));
    }

    @Test
    public void testWithPatronRoles() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(pid,
                new AclModelBuilder("Collection with patrons")
                .addCanViewOriginals(AUTHENTICATED_PRINC)
                .model);
        unit.addMember(coll);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(GRP_PRINC, canManage)));

        roleService.updateRoles(agent, pid, assignments);

        CollectionObject updated = repoObjLoader.getCollectionObject(pid);

        assertHasAssignment(GRP_PRINC, canManage, updated);
        // Ensure that existing patron role was not impacted
        assertHasAssignment(AUTHENTICATED_PRINC, canViewOriginals, updated);

        assertMessageSent(pid);

        String eventDetail = assertEventCreatedAndGetDetail(updated);
        assertThat(eventDetail, containsString(
                canManage.name() + ": " + GRP_PRINC));
        // Must not contain the patron assignment
        assertThat(eventDetail, not(containsString(AUTHENTICATED_PRINC)));
    }

    @Test(expected = InvalidAssignmentException.class)
    public void testAssignStaffRoleToPatronPrincipal() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(pid, null);
        unit.addMember(coll);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(PUBLIC_PRINC, canManage)));

        roleService.updateRoles(agent, pid, assignments);
    }

    @Test(expected = ServiceException.class)
    public void testAssignPatron() throws Exception {
        PID pid = pidMinter.mintContentPid();
        AdminUnit unit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(unit);
        CollectionObject coll = repoObjFactory.createCollectionObject(pid, null);
        unit.addMember(coll);
        treeIndexer.indexAll(baseAddress);

        Set<RoleAssignment> assignments = new HashSet<>(asList(
                new RoleAssignment(PUBLIC_PRINC, canViewOriginals)));

        roleService.updateRoles(agent, pid, assignments);
    }

    private void assertNoStaffRoles(ContentObject obj) {
        List<UserRole> staffRoles = UserRole.getStaffRoles();

        Resource resc = obj.getResource();
        StmtIterator it = resc.listProperties();
        while (it.hasNext()) {
            Statement stmt = it.next();
            UserRole role = UserRole.getRoleByProperty(stmt.getPredicate().getURI());
            if (role != null) {
                assertFalse("No staff roles should be set, but " + role + " present",
                        staffRoles.contains(role));
            }
        }
    }

    private void assertHasAssignment(String princ, UserRole role, ContentObject obj) {
        Resource resc = obj.getResource();
        assertTrue("Expected role " + role.name() + " was not assigned for " + princ,
                resc.hasProperty(role.getProperty(), princ));
    }

    private void assertNoAssignment(String princ, UserRole role, ContentObject obj) {
        Resource resc = obj.getResource();
        assertFalse("Unexpected role " + role.name() + " was assigned for " + princ,
                resc.hasProperty(role.getProperty(), princ));
    }

    private void assertMessageSent(PID pid) {
        verify(operationsMessageSender).sendOperationMessage(
                eq(USER_PRINC), eq(CDRActions.EDIT_ACCESS_CONTROL), pidListCaptor.capture());
        assertTrue(pidListCaptor.getValue().contains(pid));
    }

    private String assertEventCreatedAndGetDetail(ContentObject repoObj) {
        Model eventsModel = repoObj.getPremisLog().getEventsModel();
        Resource objResc = eventsModel.getResource(repoObj.getPid().getRepositoryPath());
        List<Resource> eventRescs = eventsModel.listResourcesWithProperty(Prov.used, objResc).toList();
        Resource eventResc = eventRescs.get(0);
        assertTrue("Event type was not set",
                eventResc.hasProperty(RDF.type, Premis.PolicyAssignment));
        Resource agentResc = eventResc.getPropertyResourceValue(Premis.hasEventRelatedAgentImplementor);
        assertEquals(AgentPids.forPerson(USER_PRINC).getRepositoryPath(), agentResc.getURI());
        String eventDetail = eventResc.getProperty(Premis.note).getString();
        assertThat(eventDetail, containsString("Staff roles for item set to:"));
        return eventDetail;
    }

    private void assertEmbargoPresent(ContentObject obj) {
        Resource resc = obj.getResource();
        assertTrue("Embargo was not present", resc.hasProperty(CdrAcl.embargoUntil));
    }
}
