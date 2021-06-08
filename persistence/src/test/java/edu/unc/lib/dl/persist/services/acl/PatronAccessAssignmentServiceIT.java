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
import static edu.unc.lib.dl.acl.util.UserRole.canManage;
import static edu.unc.lib.dl.acl.util.UserRole.canViewMetadata;
import static edu.unc.lib.dl.acl.util.UserRole.canViewOriginals;
import static edu.unc.lib.dl.util.DateTimeUtil.formatDateToUTC;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyCollectionOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.jena.datatypes.xsd.XSDDateTime;
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
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.acl.util.RoleAssignment;
import edu.unc.lib.dl.acl.util.UserRole;
import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.ContentRootObject;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.RepositoryInitializer;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPaths;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.fedora.ServiceException;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.services.acl.PatronAccessAssignmentService.PatronAccessAssignmentRequest;
import edu.unc.lib.dl.rdf.CdrAcl;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.rdf.Prov;
import edu.unc.lib.dl.services.OperationsMessageSender;
import edu.unc.lib.dl.test.AclModelBuilder;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.JMSMessageUtil.CDRActions;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/cdr-client-container.xml")
})
public class PatronAccessAssignmentServiceIT {

    private static final String USER_PRINC = "user";
    private static final String GRP_PRINC = "adminGroup";

    private static final String origFilename = "original.txt";
    private static final String origMimetype = "text/plain";

    @Autowired
    private String baseAddress;
    @Mock
    private AccessControlService aclService;
    @Autowired
    private RepositoryObjectFactory repoObjFactory;
    @Autowired
    private RepositoryObjectLoader repoObjLoader;
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

    private PatronAccessAssignmentService patronService;

    private AgentPrincipals agent;
    private AccessGroupSet groups;
    private ContentRootObject contentRoot;
    private AdminUnit adminUnit;
    private CollectionObject collObj;

    @Before
    public void init() throws Exception {
        initMocks(this);
        TestHelper.setContentBase(baseAddress);

        groups = new AccessGroupSet(GRP_PRINC);
        agent = new AgentPrincipals(USER_PRINC, groups);

        patronService = new PatronAccessAssignmentService();
        patronService.setAclService(aclService);
        patronService.setOperationsMessageSender(operationsMessageSender);
        patronService.setRepositoryObjectLoader(repoObjLoader);
        patronService.setRepositoryObjectFactory(repoObjFactory);
        patronService.setTransactionManager(txManager);

        PID contentRootPid = RepositoryPaths.getContentRootPid();
        repoInitializer.initializeRepository();
        contentRoot = repoObjLoader.getContentRootObject(contentRootPid);
    }

    @Test(expected = AccessRestrictionException.class)
    public void insufficientPermissions() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        doThrow(new AccessRestrictionException()).when(aclService)
            .assertHasAccess(anyString(), eq(pid), any(AccessGroupSet.class), eq(Permission.changePatronAccess));

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, canViewOriginals)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));
    }

    @Test(expected = AccessRestrictionException.class)
    public void insufficientPermissionsNewFolder() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        doThrow(new AccessRestrictionException()).when(aclService)
                .assertHasAccess(anyString(), eq(pid), any(AccessGroupSet.class), eq(Permission.ingest));

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, canViewOriginals)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails)
                .withFolderCreation(true));
    }

    @Test(expected = ServiceException.class)
    public void setStaffRolesFailure() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, canManage)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));
    }

    @Test(expected = InvalidAssignmentException.class)
    public void assignToBinaryObject() throws Exception {
        createCollectionInUnit(null);
        WorkObject work = repoObjFactory.createWorkObject(null);
        collObj.addMember(work);
        URI contentUri = Files.createTempFile("test", ".txt").toUri();
        FileObject fileObj = work.addDataFile(contentUri, origFilename, origMimetype, null, null);
        BinaryObject binObj = fileObj.getOriginalFile();

        PID pid = binObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, canViewOriginals)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));
    }

    @Test(expected = InvalidAssignmentException.class)
    public void assignRolesToAdminUnit() throws Exception {
        createCollectionInUnit(null);
        PID pid = adminUnit.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, canViewMetadata)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));
    }

    @Test
    public void assignNewRoles() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, canViewMetadata),
                new RoleAssignment(AUTHENTICATED_PRINC, canViewOriginals)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertHasAssignment(PUBLIC_PRINC, canViewMetadata, target);
        assertHasAssignment(AUTHENTICATED_PRINC, canViewOriginals, target);

        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, PUBLIC_PRINC + ": " + canViewMetadata.getPropertyString());
        assertEventWithDetail(eventDetails, AUTHENTICATED_PRINC + ": " + canViewOriginals.getPropertyString());

        assertMessageSent(pid);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingAgent() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, canViewMetadata)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(null, pid, accessDetails));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingTarget() throws Exception {
        createCollectionInUnit(null);
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, canViewMetadata)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, null, accessDetails));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingAccessDetails() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingRole() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, null)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingPrinicipal() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(null, canViewMetadata)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankPrinicipal() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment("  ", null)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));
    }

    @Test
    public void revokeRole() throws Exception {
        createCollectionInUnit(new AclModelBuilder("Collection with patron roles")
                .addCanViewOriginals(PUBLIC_PRINC)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(PUBLIC_PRINC, UserRole.none)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertHasAssignment(PUBLIC_PRINC, UserRole.none, target);

        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, PUBLIC_PRINC + ": " + UserRole.none.getPropertyString());

        assertMessageSent(pid);
    }

    @Test
    public void overwriteRoles() throws Exception {
        createCollectionInUnit(new AclModelBuilder("Collection with patron roles")
                .addCanViewOriginals(PUBLIC_PRINC)
                .addCanViewOriginals(AUTHENTICATED_PRINC)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(AUTHENTICATED_PRINC, canViewMetadata)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertNoAssignment(PUBLIC_PRINC, target);
        assertHasAssignment(AUTHENTICATED_PRINC, canViewMetadata, target);

        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, AUTHENTICATED_PRINC + ": " + canViewMetadata.getPropertyString());
        assertFalse(eventDetails.get(0).contains(PUBLIC_PRINC));

        assertMessageSent(pid);
    }

    @Test
    public void clearExistingRoles() throws Exception {
        createCollectionInUnit(new AclModelBuilder("Collection with patron role")
                .addCanViewOriginals(PUBLIC_PRINC)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertNoAssignment(PUBLIC_PRINC, target);

        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, "No roles assigned");

        assertMessageSent(pid);
    }

    @Test
    public void addNewEmbargo() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        Date embargoUntil = getYearsInTheFuture(1).getTime();
        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setEmbargo(embargoUntil);

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);

        assertNoRoles(target);

        assertHasEmbargo(embargoUntil, target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, "Set an embargo that will expire " + formatDateToUTC(embargoUntil));

        assertMessageSent(pid);
    }

    @Test(expected = InvalidAssignmentException.class)
    public void addExpiredEmbargo() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        Date embargoUntil = new Date(0);
        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setEmbargo(embargoUntil);

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));
    }

    @Test
    public void replaceEmbargo() throws Exception {
        Calendar originalEmbargo = getYearsInTheFuture(1);
        createCollectionInUnit(new AclModelBuilder("Collection with embargo")
                .addEmbargoUntil(originalEmbargo)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        Date embargoUntil = getYearsInTheFuture(2).getTime();
        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setEmbargo(embargoUntil);

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);

        assertNoRoles(target);

        assertHasEmbargo(embargoUntil, target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, "Embargo expiration date changed from "
                + formatDateToUTC(originalEmbargo.getTime())
                + " to " + formatDateToUTC(embargoUntil));

        assertMessageSent(pid);
    }

    @Test
    public void removeEmbargo() throws Exception {
        Calendar originalEmbargo = getYearsInTheFuture(1);
        createCollectionInUnit(new AclModelBuilder("Collection with embargo")
                .addEmbargoUntil(originalEmbargo)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);

        assertNoRoles(target);

        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, "Embargo expiration date changed from "
                + formatDateToUTC(originalEmbargo.getTime())
                + " to no embargo");

        assertMessageSent(pid);
    }

    @Test
    public void addNewEmbargoAndRole() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        Date embargoUntil = getYearsInTheFuture(1).getTime();
        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setEmbargo(embargoUntil);
        accessDetails.setRoles(asList(
                new RoleAssignment(AUTHENTICATED_PRINC, canViewMetadata)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);

        assertHasAssignment(AUTHENTICATED_PRINC, canViewMetadata, target);

        assertHasEmbargo(embargoUntil, target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(2, eventDetails.size());
        assertEventWithDetail(eventDetails, AUTHENTICATED_PRINC + ": " + canViewMetadata.getPropertyString());
        assertEventWithDetail(eventDetails, "Set an embargo that will expire " + formatDateToUTC(embargoUntil));

        assertMessageSent(pid);
    }

    @Test
    public void skipAddEmbargo() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        Date embargoUntil = getYearsInTheFuture(1).getTime();
        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setEmbargo(embargoUntil);

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails)
                .withSkipEmbargo(true));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);

        assertNoRoles(target);

        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(0, eventDetails.size());

        assertMessageNotSent(pid);
    }

    @Test
    public void skipRemoveEmbargo() throws Exception {
        Calendar originalEmbargo = getYearsInTheFuture(1);
        createCollectionInUnit(new AclModelBuilder("Collection with embargo")
                .addEmbargoUntil(originalEmbargo)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails)
                .withSkipEmbargo(true));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);

        assertNoRoles(target);
        assertHasEmbargo(originalEmbargo.getTime(), target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(0, eventDetails.size());

        assertMessageNotSent(pid);
    }

    @Test
    public void rolesUpdatedWhenSkippingEmbargo() throws Exception {
        createCollectionInUnit(new AclModelBuilder("Collection")
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(new RoleAssignment(AUTHENTICATED_PRINC, canViewOriginals)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails)
                .withSkipEmbargo(true));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertHasAssignment(AUTHENTICATED_PRINC, canViewOriginals, target);
        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, AUTHENTICATED_PRINC + ": " + canViewOriginals.getPropertyString());

        assertMessageSent(pid);
    }

    @Test
    public void skipOverwritingRoles() throws Exception {
        createCollectionInUnit(new AclModelBuilder("Collection with patron roles")
                .addCanViewOriginals(PUBLIC_PRINC)
                .addCanViewOriginals(AUTHENTICATED_PRINC)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails)
                .withSkipRoles(true));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertHasAssignment(PUBLIC_PRINC, canViewOriginals, target);
        assertHasAssignment(AUTHENTICATED_PRINC, canViewOriginals, target);

        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(0, eventDetails.size());

        assertMessageNotSent(pid);
    }

    @Test
    public void embargoUpdatedWhenSkippingRoles() throws Exception {
        createCollectionInUnit(new AclModelBuilder("Collection with patron roles")
                .addCanViewOriginals(AUTHENTICATED_PRINC)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        Date embargoUntil = getYearsInTheFuture(1).getTime();
        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setEmbargo(embargoUntil);
        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails)
                .withSkipRoles(true));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertHasAssignment(AUTHENTICATED_PRINC, canViewOriginals, target);

        assertHasEmbargo(embargoUntil, target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, "Set an embargo that will expire " + formatDateToUTC(embargoUntil));

        assertMessageSent(pid);
    }

    @Test
    public void skipRolesAndEmbargoes() throws Exception {
        Calendar originalEmbargo = getYearsInTheFuture(1);
        createCollectionInUnit(new AclModelBuilder("Collection with patron and embargo")
                .addCanViewOriginals(AUTHENTICATED_PRINC)
                .addEmbargoUntil(originalEmbargo)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails)
                .withSkipRoles(true)
                .withSkipEmbargo(true));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertHasAssignment(AUTHENTICATED_PRINC, canViewOriginals, target);

        assertHasEmbargo(originalEmbargo.getTime(), target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(0, eventDetails.size());

        assertMessageNotSent(pid);
    }

    @Test
    public void makeNoChangesToObjectWithNoPatronAccess() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();

        String opId = patronService.updatePatronAccess(
                new PatronAccessAssignmentRequest(agent, pid, accessDetails));
        assertNull(opId);

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);

        assertNoRoles(target);
        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(0, eventDetails.size());

        assertMessageNotSent(pid);
    }

    @Test
    public void makeNoChangesToObjectWithPatronAccess() throws Exception {
        Calendar originalEmbargo = getYearsInTheFuture(1);
        createCollectionInUnit(new AclModelBuilder("Collection with role and embargo")
                .addCanViewMetadata(AUTHENTICATED_PRINC)
                .addEmbargoUntil(originalEmbargo)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setEmbargo(originalEmbargo.getTime());
        accessDetails.setRoles(asList(
                new RoleAssignment(AUTHENTICATED_PRINC, canViewMetadata)));

        String opId = patronService.updatePatronAccess(
                new PatronAccessAssignmentRequest(agent, pid, accessDetails));
        assertNull(opId);

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);

        assertHasAssignment(AUTHENTICATED_PRINC, canViewMetadata, target);
        assertHasEmbargo(originalEmbargo.getTime(), target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(0, eventDetails.size());

        assertMessageNotSent(pid);
    }

    @Test
    public void addPatronRolesToResourceWithStaffRoles() throws Exception {
        createCollectionInUnit(new AclModelBuilder("Collection with patron roles")
                .addCanManage(GRP_PRINC)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(AUTHENTICATED_PRINC, canViewMetadata)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertHasAssignment(AUTHENTICATED_PRINC, canViewMetadata, target);
        assertHasAssignment(GRP_PRINC, canManage, target);

        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, AUTHENTICATED_PRINC + ": " + canViewMetadata.getPropertyString());

        assertMessageSent(pid);
    }

    @Test(expected = InvalidAssignmentException.class)
    public void addPatronRoleToStaffPrincipal() throws Exception {
        createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        PatronAccessDetails accessDetails = new PatronAccessDetails();
        accessDetails.setRoles(asList(
                new RoleAssignment(GRP_PRINC, canViewMetadata)));

        patronService.updatePatronAccess(new PatronAccessAssignmentRequest(agent, pid, accessDetails));
    }

    private void createCollectionInUnit(Model collModel) {
        adminUnit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(adminUnit);
        collObj = repoObjFactory.createCollectionObject(collModel);
        adminUnit.addMember(collObj);
    }

    private void assertHasAssignment(String princ, UserRole role, RepositoryObject obj) {
        Resource resc = obj.getResource();
        assertTrue("Expected role " + role.name() + " was not assigned for " + princ,
                resc.hasProperty(role.getProperty(), princ));
    }

    private void assertNoAssignment(String princ, RepositoryObject obj) {
        Resource resc = obj.getResource();
        assertFalse("No roles expected for " + princ,
                obj.getModel().contains(resc, null, princ));
    }

    private void assertNoEmbargo(RepositoryObject obj) {
        Resource resc = obj.getResource();
        assertFalse("Unexpect embargo assigned to " + obj.getPid().getId(),
                resc.hasProperty(CdrAcl.embargoUntil));
    }

    private void assertHasEmbargo(Date expectedEmbargo, RepositoryObject obj) {
        Resource resc = obj.getResource();
        Statement embargoStmt = resc.getProperty(CdrAcl.embargoUntil);
        assertNotNull("Embargo was expected by not found", embargoStmt);
        Date assigned = ((XSDDateTime) embargoStmt.getLiteral().getValue()).asCalendar().getTime();
        assertEquals("Embargo did not match expected value",
                expectedEmbargo, assigned);
    }

    private void assertMessageSent(PID pid) {
        verify(operationsMessageSender).sendOperationMessage(
                eq(USER_PRINC), eq(CDRActions.EDIT_ACCESS_CONTROL), pidListCaptor.capture());
        assertTrue(pidListCaptor.getValue().contains(pid));
    }

    private void assertMessageNotSent(PID pid) {
        verify(operationsMessageSender, never()).sendOperationMessage(
                anyString(), any(CDRActions.class), anyCollectionOf(PID.class));
    }

    private void assertEventWithDetail(List<String> eventDetails, String expected) {
        assertTrue("No event with expected detail '" + expected + "'",
                eventDetails.stream().anyMatch(d -> d.contains(expected)));
    }

    private List<String> getEventDetails(RepositoryObject repoObj) {
        List<String> details = new ArrayList<>();

        Model eventsModel = repoObj.getPremisLog().getEventsModel();
        Resource objResc = eventsModel.getResource(repoObj.getPid().getRepositoryPath());
        StmtIterator it = eventsModel.listStatements(null, Prov.used, objResc);
        while (it.hasNext()) {
            Statement stmt = it.next();
            Resource eventResc = stmt.getSubject();

            assertTrue("Event type was not set",
                    eventResc.hasProperty(RDF.type, Premis.PolicyAssignment));
            Resource agentResc = eventResc.getPropertyResourceValue(Premis.hasEventRelatedAgentImplementor);
            assertEquals(AgentPids.forPerson(USER_PRINC).getRepositoryPath(), agentResc.getURI());
            details.add(eventResc.getProperty(Premis.note).getString());
        }

        return details;
    }

    private void assertNoRoles(RepositoryObject obj) {
        Resource resc = obj.getResource();
        StmtIterator it = resc.listProperties();
        while (it.hasNext()) {
            Statement stmt = it.next();
            UserRole role = UserRole.getRoleByProperty(stmt.getPredicate().getURI());
            if (role != null) {
                fail("No roles should be assigned, but " + role + " for " + stmt.getString() + " was present");
            }
        }
    }

    private Calendar getYearsInTheFuture(int numYears) {
        Date dt = new Date();
        Calendar c = Calendar.getInstance();
        c.setTime(dt);
        c.add(Calendar.DATE, 365 * numYears);
        return c;
    }
}
