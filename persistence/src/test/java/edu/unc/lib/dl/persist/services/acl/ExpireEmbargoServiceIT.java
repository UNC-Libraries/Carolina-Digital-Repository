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

import static edu.unc.lib.dl.util.DateTimeUtil.formatDateToUTC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyCollectionOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

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

import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.ContentRootObject;
import edu.unc.lib.dl.fcrepo4.RepositoryInitializer;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPaths;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.rdf.CdrAcl;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.rdf.Prov;
import edu.unc.lib.dl.services.OperationsMessageSender;
import edu.unc.lib.dl.sparql.SparqlQueryService;
import edu.unc.lib.dl.test.AclModelBuilder;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.JMSMessageUtil;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
        @ContextConfiguration("/spring-test/cdr-client-container.xml")
})
public class ExpireEmbargoServiceIT {

    @Autowired
    private String baseAddress;
    @Autowired
    private RepositoryObjectLoader repoObjLoader;
    @Autowired
    private RepositoryObjectFactory repoObjFactory;
    @Mock
    private OperationsMessageSender operationsMessageSender;
    @Autowired
    private TransactionManager txManager;
    @Autowired
    private SparqlQueryService sparqlQueryService;
    @Autowired
    private RepositoryObjectTreeIndexer treeIndexer;
    @Autowired
    private RepositoryInitializer repoInitializer;
    @Captor
    private ArgumentCaptor<List<PID>> pidListCaptor;

    private ExpireEmbargoService service;

    private ContentRootObject contentRoot;

    @Before
    public void init() throws Exception {
        initMocks(this);
        TestHelper.setContentBase(baseAddress);

        service = new ExpireEmbargoService();
        service.setOperationsMessageSender(operationsMessageSender);
        service.setRepositoryObjectLoader(repoObjLoader);
        service.setRepositoryObjectFactory(repoObjFactory);
        service.setTransactionManager(txManager);
        service.setSparqlQueryService(sparqlQueryService);

        PID contentRootPid = RepositoryPaths.getContentRootPid();
        repoInitializer.initializeRepository();
        contentRoot = repoObjLoader.getContentRootObject(contentRootPid);
    }

    @Test
    public void expireSingleEmbargoTest() throws Exception {
        Calendar embargoUntil = getDayFromNow(-1);

        CollectionObject collObj = createCollectionInUnit(new AclModelBuilder("Collection with embargo")
                .addEmbargoUntil(embargoUntil)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        service.expireEmbargoes();

        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertNoEmbargo(target);

        List<String> eventDetails = getEventDetails(target);
        assertEquals(1, eventDetails.size());
        assertEventWithDetail(eventDetails, "Expired an embargo which ended " +
                formatDateToUTC(embargoUntil.getTime()));

        assertMessageSent(pid);
    }


    @Test
    public void expireMultipleEmbargoesTest() throws Exception {
        Calendar embargoUntil = getDayFromNow(-1);
        // create first embargoed collection
        CollectionObject collObj1 = createCollectionInUnit(new AclModelBuilder("Collection with embargo")
                .addEmbargoUntil(embargoUntil)
                .model);
        PID pid1 = collObj1.getPid();
        // create second embargoed collection
        CollectionObject collObj2 = createCollectionInUnit(new AclModelBuilder("Another collection with embargo")
                .addEmbargoUntil(embargoUntil)
                .model);
        PID pid2 = collObj2.getPid();
        treeIndexer.indexAll(baseAddress);

        service.expireEmbargoes();

        RepositoryObject target1 = repoObjLoader.getRepositoryObject(pid1);
        RepositoryObject target2 = repoObjLoader.getRepositoryObject(pid2);
        assertNoEmbargo(target1);
        assertNoEmbargo(target2);

        List<String> eventDetails1 = getEventDetails(target1);
        List<String> eventDetails2 = getEventDetails(target2);
        assertEquals(1, eventDetails1.size());
        assertEquals(1, eventDetails2.size());

        assertEventWithDetail(eventDetails1, "Expired an embargo which ended " +
                formatDateToUTC(embargoUntil.getTime()));
        assertEventWithDetail(eventDetails2, "Expired an embargo which ended " +
                formatDateToUTC(embargoUntil.getTime()));

        assertMessageSent(pid1);
        assertMessageSent(pid2);
    }

    @Test
    public void expireNoEmbargoesTest() throws Exception {
        CollectionObject collObj = createCollectionInUnit(null);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        service.expireEmbargoes();

        // collection was not created with an embargo and should not have one
        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertNoEmbargo(target);

        // no event should have been created since no embargoes were expired
        List<String> eventDetails = getEventDetails(target);
        assertEquals(0, eventDetails.size());

        assertMessageNotSent(pid);
    }

    @Test
    public void doNotExpireFutureEmbargoesTest() throws Exception {
        Calendar embargoUntil = getDayFromNow(1);

        CollectionObject collObj = createCollectionInUnit(new AclModelBuilder("Collection with embargo")
                .addEmbargoUntil(embargoUntil)
                .model);
        PID pid = collObj.getPid();
        treeIndexer.indexAll(baseAddress);

        service.expireEmbargoes();

        // collection was not created with an embargo and should not have one
        RepositoryObject target = repoObjLoader.getRepositoryObject(pid);
        assertEmbargo(target);

        // no event should have been created since no embargoes were expired
        List<String> eventDetails = getEventDetails(target);
        assertEquals(0, eventDetails.size());

        assertMessageNotSent(pid);
    }

    private Calendar getDayFromNow(int daysFromNow) {
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, daysFromNow);
        return cal;
    }

    private CollectionObject createCollectionInUnit(Model collModel) {
        AdminUnit adminUnit = repoObjFactory.createAdminUnit(null);
        contentRoot.addMember(adminUnit);
        CollectionObject collObj = repoObjFactory.createCollectionObject(collModel);
        adminUnit.addMember(collObj);
        return collObj;
    }

    private void assertNoEmbargo(RepositoryObject obj) {
        Resource resc = obj.getResource();
        assertFalse("Unexpected embargo assigned to " + obj.getPid().getId(),
                resc.hasProperty(CdrAcl.embargoUntil));
    }

    private void assertEmbargo(RepositoryObject obj) {
        Resource resc = obj.getResource();
        assertTrue("No embargo assigned to " + obj.getPid().getId(),
                resc.hasProperty(CdrAcl.embargoUntil));
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
                    eventResc.hasProperty(RDF.type, Premis.Dissemination));
            Resource execAgent = eventResc.getProperty(Premis.hasEventRelatedAgentExecutor).getResource();
            assertEquals(AgentPids.forSoftware(SoftwareAgent.embargoExpirationService).getRepositoryPath(),
                    execAgent.getURI());
            details.add(eventResc.getProperty(Premis.note).getString());
        }

        return details;
    }

    private void assertEventWithDetail(List<String> eventDetails, String expected) {
        assertTrue("No event with expected detail '" + expected + "'",
                eventDetails.stream().anyMatch(d -> d.contains(expected)));
    }

    private void assertMessageSent(PID pid) {
        verify(operationsMessageSender).sendOperationMessage(
                eq(SoftwareAgent.embargoExpirationService.getFullname()),
                eq(JMSMessageUtil.CDRActions.EDIT_ACCESS_CONTROL),
                pidListCaptor.capture());
        assertTrue(pidListCaptor.getValue().contains(pid));
    }

    private void assertMessageNotSent(PID pid) {
        verify(operationsMessageSender, never()).sendOperationMessage(
                anyString(), any(JMSMessageUtil.CDRActions.class), anyCollectionOf(PID.class));
    }
}
