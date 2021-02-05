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
package edu.unc.lib.dl.persist.services.destroy;

import static edu.unc.lib.dl.fcrepo4.RepositoryPaths.getContentRootPid;
import static edu.unc.lib.dl.sparql.SparqlUpdateHelper.createSparqlReplace;
import static edu.unc.lib.dl.util.IndexingActionType.DELETE_SOLR_TREE;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.CDR_MESSAGE_NS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.http.HttpStatus;
import org.apache.jena.rdf.model.Model;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoOperationFailedException;
import org.fcrepo.client.FcrepoResponse;
import org.jdom2.Document;
import org.jdom2.Element;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.ContentRootObject;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.RepositoryInitializer;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.fedora.ServiceException;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.persist.services.edit.EditTitleService;
import edu.unc.lib.dl.persist.services.storage.StorageLocationManagerImpl;
import edu.unc.lib.dl.rdf.PcdmModels;
import edu.unc.lib.dl.services.IndexingMessageSender;
import edu.unc.lib.dl.services.MessageSender;
import edu.unc.lib.dl.sparql.SparqlUpdateService;
import edu.unc.lib.dl.test.AclModelBuilder;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.IndexingActionType;
import edu.unc.lib.dl.util.ResourceType;

/**
 * @author bbpennel
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml"),
    @ContextConfiguration("/spring-test/acl-service-context.xml"),
    @ContextConfiguration("/spring-test/destroy-completely-it-context.xml")
})
public class DestroyObjectsCompletelyJobIT {
    private final static String LOC1_ID = "loc1";
    private static final String USER_NAME = "user";
    private static final String USER_GROUPS = "edu:lib:staff_grp";

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    @Autowired
    private String baseAddress;
    @Autowired
    private RepositoryObjectFactory repoObjFactory;
    @Autowired
    private RepositoryObjectLoader repoObjLoader;
    @Autowired
    private FcrepoClient fcrepoClient;
    @Autowired
    private AccessControlService aclService;
    @Autowired
    private RepositoryInitializer repoInitializer;
    @Mock
    private IndexingMessageSender indexingMessageSender;
    @Mock
    private MessageSender binaryDestroyedMessageSender;

    @Autowired
    private StorageLocationManagerImpl locationManager;
    @Autowired
    private BinaryTransferService transferService;

    @Autowired
    private EditTitleService editTitleService;

    @Captor
    private ArgumentCaptor<Document> docCaptor;

    private AgentPrincipals agent;

    @Autowired
    private SparqlUpdateService sparqlUpdateService;
    @Autowired
    private Model queryModel;
    private RepositoryObjectTreeIndexer treeIndexer;

    private ContentRootObject contentRoot;
    private AdminUnit adminUnit;
    private CollectionObject collection;

    private DestroyObjectsCompletelyJob job;

    @Before
    public void init() throws Exception {
        initMocks(this);
        TestHelper.setContentBase(baseAddress);

        AccessGroupSet testPrincipals = new AccessGroupSet(USER_GROUPS);
        agent = new AgentPrincipals(USER_NAME, testPrincipals);

        createContentTree();

        treeIndexer = new RepositoryObjectTreeIndexer(queryModel, fcrepoClient);
    }

    private void createContentTree() throws Exception {
        PID contentRootPid = getContentRootPid();
        repoInitializer.initializeRepository();
        contentRoot = repoObjLoader.getContentRootObject(contentRootPid);

        adminUnit = repoObjFactory.createAdminUnit(new AclModelBuilder("Unit")
                .addUnitOwner(agent.getUsernameUri())
                .model);
        contentRoot.addMember(adminUnit);

        collection = repoObjFactory.createCollectionObject(null);
        adminUnit.addMember(collection);
    }

    private void initializeJob(PID... objsToDestroy) {
        DestroyObjectsRequest request = new DestroyObjectsRequest("jobid", agent,
                Arrays.stream(objsToDestroy).map(PID::getQualifiedId).toArray(String[]::new));
        job = new DestroyObjectsCompletelyJob(request);
        job.setRepoObjFactory(repoObjFactory);
        job.setRepoObjLoader(repoObjLoader);
        job.setFcrepoClient(fcrepoClient);
        job.setAclService(aclService);
        job.setBinaryTransferService(transferService);
        job.setStorageLocationManager(locationManager);
        job.setIndexingMessageSender(indexingMessageSender);
        job.setBinaryDestroyedMessageSender(binaryDestroyedMessageSender);
    }

    @Test
    public void destroyBinaryObject() throws Exception {
        WorkObject work = repoObjFactory.createWorkObject(null);
        collection.addMember(work);

        FileObject file = addFileToWork(work);

        treeIndexer.indexAll(baseAddress);

        PID originalPid = file.getOriginalFile().getPid();
        File originalFile = new File(file.getOriginalFile().getContentUri());

        initializeJob(originalPid);

        try {
            job.run();
            fail("Must throw ServiceException");
        } catch (ServiceException e) {
            // expected
            assertTrue(e.getMessage().contains("Refusing to destroy object"));
        }
        assertTrue(originalFile.exists());

        verify(indexingMessageSender, never()).sendIndexingOperation(anyString(),
                any(PID.class), any(IndexingActionType.class));
        verify(binaryDestroyedMessageSender, never()).sendMessage(any(Document.class));
    }

    @Test
    public void destroyDescribedWork() throws Exception {
        WorkObject work = repoObjFactory.createWorkObject(null);
        collection.addMember(work);

        editTitleService.editTitle(agent, work.getPid(), "first title");
        editTitleService.editTitle(agent, work.getPid(), "second title");

        FileObject file = addFileToWork(work);
        BinaryObject originalObj = file.getOriginalFile();
        File originalFile = new File(originalObj.getContentUri());

        treeIndexer.indexAll(baseAddress);

        PID modsPid = DatastreamPids.getMdDescriptivePid(work.getPid());
        File modsFile = new File(repoObjLoader.getBinaryObject(modsPid).getContentUri());
        PID historyPid = DatastreamPids.getDatastreamHistoryPid(modsPid);
        File historyFile = new File(repoObjLoader.getBinaryObject(historyPid).getContentUri());

        assertTrue(originalFile.exists());
        assertTrue(modsFile.exists());
        assertTrue(historyFile.exists());

        initializeJob(work.getPid());

        job.run();

        assertObjectRemoved(work);
        assertObjectRemoved(file);

        assertFalse(originalFile.exists());
        assertFalse(modsFile.exists());
        assertFalse(historyFile.exists());

        verify(indexingMessageSender).sendIndexingOperation(anyString(), eq(work.getPid()), eq(DELETE_SOLR_TREE));

        verify(binaryDestroyedMessageSender, times(2)).sendMessage(docCaptor.capture());
        List<Document> binMsgs = docCaptor.getAllValues();
        assertMessagePresent(binMsgs, work.getPid(), ResourceType.Work, null, modsFile, historyFile);
        assertMessagePresent(binMsgs, originalObj.getPid(), ResourceType.File, "text/plain", originalFile);

    }

    // Ensure that an invalid membership relation can't result in destroying admin units
    @Test
    public void destroyContainerWithAdminUnitMember() throws Exception {
        FolderObject folder = repoObjFactory.createFolderObject(null);
        collection.addMember(folder);

        AdminUnit unit2 = repoObjFactory.createAdminUnit(null);

        // Force the admin unit as a member of the folder
        String updateString = createSparqlReplace(unit2.getPid().getRepositoryPath(),
                PcdmModels.memberOf, folder.getResource());
        sparqlUpdateService.executeUpdate(unit2.getPid().getRepositoryPath(), updateString);

        treeIndexer.indexAll(baseAddress);

        initializeJob(folder.getPid());

        try {
            job.run();
            fail("Must throw ServiceException");
        } catch (ServiceException e) {
            assertTrue(e.getMessage().contains("Refusing to destroy object"));
        }
        assertTrue(repoObjFactory.objectExists(adminUnit.getUri()));
        assertTrue(repoObjFactory.objectExists(folder.getUri()));

        verify(indexingMessageSender, never()).sendIndexingOperation(anyString(),
                any(PID.class), any(IndexingActionType.class));
        verify(binaryDestroyedMessageSender, never()).sendMessage(any(Document.class));
    }

    @Test
    public void destroyContentRoot() throws Exception {
        treeIndexer.indexAll(baseAddress);

        initializeJob(contentRoot.getPid());

        try {
            job.run();
            fail("Must throw ServiceException");
        } catch (ServiceException e) {
            assertTrue(e.getMessage().contains("Refusing to destroy object"));
        }
        assertTrue(repoObjFactory.objectExists(contentRoot.getUri()));

        verify(indexingMessageSender, never()).sendIndexingOperation(anyString(),
                any(PID.class), any(IndexingActionType.class));
        verify(binaryDestroyedMessageSender, never()).sendMessage(any(Document.class));
    }

    @Test
    public void destroyMultiple() throws Exception {
        FolderObject folder = repoObjFactory.createFolderObject(null);
        collection.addMember(folder);

        FolderObject folder2 = repoObjFactory.createFolderObject(null);
        collection.addMember(folder2);

        WorkObject work = repoObjFactory.createWorkObject(null);
        folder2.addMember(work);

        FileObject file = addFileToWork(work);
        BinaryObject originalObj = file.getOriginalFile();
        File originalFile = new File(originalObj.getContentUri());

        treeIndexer.indexAll(baseAddress);

        assertTrue(repoObjFactory.objectExists(folder.getUri()));
        assertTrue(repoObjFactory.objectExists(folder2.getUri()));
        assertTrue(repoObjFactory.objectExists(work.getUri()));
        assertTrue(repoObjFactory.objectExists(file.getUri()));

        initializeJob(folder.getPid(), folder2.getPid());

        job.run();

        assertObjectRemoved(folder);
        assertObjectRemoved(folder2);
        assertObjectRemoved(work);
        assertObjectRemoved(file);
        assertFalse(originalFile.exists());

        verify(indexingMessageSender).sendIndexingOperation(anyString(), eq(folder.getPid()), eq(DELETE_SOLR_TREE));
        verify(indexingMessageSender).sendIndexingOperation(anyString(), eq(folder2.getPid()), eq(DELETE_SOLR_TREE));

        verify(binaryDestroyedMessageSender, times(4)).sendMessage(docCaptor.capture());
        List<Document> binMsgs = docCaptor.getAllValues();
        assertMessagePresent(binMsgs, work.getPid(), ResourceType.Work, null);
        assertMessagePresent(binMsgs, folder.getPid(), ResourceType.Folder, null);
        assertMessagePresent(binMsgs, folder2.getPid(), ResourceType.Folder, null);
        assertMessagePresent(binMsgs, originalObj.getPid(), ResourceType.File, "text/plain", originalFile);
    }

    @Test
    public void destroyWorkInsufficientPermissions() throws Exception {
        AccessGroupSet testPrincipals = new AccessGroupSet("please");
        agent = new AgentPrincipals("letmein", testPrincipals);

        WorkObject work = repoObjFactory.createWorkObject(null);
        collection.addMember(work);

        treeIndexer.indexAll(baseAddress);

        initializeJob(work.getPid());

        try {
            job.run();
            fail("Must throw AccessRestrictionException");
        } catch (AccessRestrictionException e) {
            // expected
        }
        assertTrue(repoObjFactory.objectExists(work.getUri()));

        verify(indexingMessageSender, never()).sendIndexingOperation(anyString(),
                any(PID.class), any(IndexingActionType.class));
        verify(binaryDestroyedMessageSender, never()).sendMessage(any(Document.class));
    }

    private FileObject addFileToWork(WorkObject work) throws Exception {
        String bodyString = "Content";
        String mimetype = "text/plain";
        Path storagePath = Paths.get(locationManager.getStorageLocationById(LOC1_ID).getNewStorageUri(work.getPid()));
        Files.createDirectories(storagePath);
        File contentFile = Files.createTempFile(storagePath, "file", ".txt").toFile();
        String filename = contentFile.getName();
        FileUtils.writeStringToFile(contentFile, bodyString, "UTF-8");
        return work.addDataFile(contentFile.toPath().toUri(), filename, mimetype, null, null);
    }

    private void assertObjectRemoved(RepositoryObject repoObj) throws Exception {
        try (FcrepoResponse resp = fcrepoClient.head(repoObj.getUri()).perform()) {
            fail("Expected object to not be found, but received response " + resp.getStatusCode());
        } catch (FcrepoOperationFailedException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return;
            } else {
                throw e;
            }
        }
    }

    private void assertMessagePresent(List<Document> returnedDocs, PID pid, ResourceType rescType,
            String mimetype, File... contentFiles) {

        for (Document returnedDoc : returnedDocs) {
            Element root = returnedDoc.getRootElement();
            Element info = root.getChild("objToDestroy", CDR_MESSAGE_NS);
            String pidId = info.getChildTextTrim("pidId", CDR_MESSAGE_NS);
            if (!pid.getQualifiedId().equals(pidId)) {
                continue;
            }

            String msgObjType = info.getChildTextTrim("objType", CDR_MESSAGE_NS);
            assertEquals(rescType.getUri(), msgObjType);
            assertEquals(mimetype, info.getChildTextTrim("mimeType", CDR_MESSAGE_NS));

            if (contentFiles != null) {
                List<String> msgContentUris = info.getChildren("contentUri", CDR_MESSAGE_NS)
                        .stream().map(Element::getTextTrim).collect(Collectors.toList());
                List<String> expectedUris = Arrays.stream(contentFiles).map(f -> f.toURI().toString())
                        .collect(Collectors.toList());
                assertEquals(expectedUris.size(), msgContentUris.size());
                assertTrue("Did not contain all expected URIS", msgContentUris.containsAll(expectedUris));
            }
            return;
        }
        fail("Destroy message not present for " + pid + " of type " + rescType);
    }
}
