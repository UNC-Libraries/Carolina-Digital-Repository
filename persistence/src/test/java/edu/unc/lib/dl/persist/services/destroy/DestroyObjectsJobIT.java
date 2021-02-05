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
import static edu.unc.lib.dl.rdf.CdrAcl.markedForDeletion;
import static edu.unc.lib.dl.sparql.SparqlUpdateHelper.createSparqlReplace;
import static edu.unc.lib.dl.util.IndexingActionType.DELETE_SOLR_TREE;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.CDR_MESSAGE_NS;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.client.FcrepoClient;
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

import edu.unc.lib.dl.acl.fcrepo4.InheritedAclFactory;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.ContentRootObject;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryInitializer;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.Tombstone;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.persist.services.storage.StorageLocationManagerImpl;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.Ebucore;
import edu.unc.lib.dl.rdf.PcdmModels;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.search.solr.model.ObjectPath;
import edu.unc.lib.dl.search.solr.service.ObjectPathFactory;
import edu.unc.lib.dl.services.IndexingMessageSender;
import edu.unc.lib.dl.services.MessageSender;
import edu.unc.lib.dl.sparql.SparqlUpdateService;
import edu.unc.lib.dl.test.AclModelBuilder;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.ResourceType;

/**
 *
 * @author harring
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml"),
    @ContextConfiguration("/spring-test/acl-service-context.xml")
})
public class DestroyObjectsJobIT {
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
    private TransactionManager txManager;
    @Autowired
    private ObjectPathFactory pathFactory;
    @Mock
    private ObjectPath path;
    @Autowired
    private SparqlUpdateService sparqlUpdateService;
    @Autowired
    private Model queryModel;
    @Autowired
    private FcrepoClient fcrepoClient;
    @Autowired
    private AccessControlService aclService;
    @Autowired
    private InheritedAclFactory inheritedAclFactory;
    @Autowired
    private RepositoryInitializer repoInitializer;
    @Mock
    private IndexingMessageSender indexingMessageSender;
    @Mock
    private MessageSender binaryDestroyedMessageSender;
    @Captor
    private ArgumentCaptor<Document> docCaptor;

    @Autowired
    private StorageLocationManagerImpl locationManager;
    @Autowired
    private BinaryTransferService transferService;

    private AgentPrincipals agent;

    private RepositoryObjectTreeIndexer treeIndexer;

    private List<PID> objsToDestroy = new ArrayList<>();

    private DestroyObjectsJob job;

    @Before
    public void init() throws Exception {
        initMocks(this);
        TestHelper.setContentBase(baseAddress);

        AccessGroupSet testPrincipals = new AccessGroupSet(USER_GROUPS);
        agent = new AgentPrincipals(USER_NAME, testPrincipals);

        treeIndexer = new RepositoryObjectTreeIndexer(queryModel, fcrepoClient);

        objsToDestroy = createContentTree();

        when(pathFactory.getPath(any(PID.class))).thenReturn(path);
        when(path.toNamePath()).thenReturn("path/to/object");
        when(path.toIdPath()).thenReturn("pid0/pid1/pid2/pid3");
    }

    @Test
    public void destroySingleFileObjectTest() throws Exception {
        PID fileObjPid = objsToDestroy.get(2);
        initializeJob(asList(fileObjPid));

        FileObject fileObj = repoObjLoader.getFileObject(fileObjPid);
        Map<URI, Map<String, String>> filesToCleanup = derivativesToCleanup(fileObj);

        URI contentUri = fileObj.getOriginalFile().getContentUri();
        assertTrue(Files.exists(Paths.get(contentUri)));

        job.run();

        Model logParentModel = fileObj.getParent().getPremisLog().getEventsModel();
        assertTrue(logParentModel.contains(null, RDF.type, Premis.Deletion));
        assertTrue(logParentModel.contains(null, Premis.note,
                "1 object(s) were destroyed"));

        Tombstone stoneFile = repoObjLoader.getTombstone(fileObjPid);
        Resource stoneResc = stoneFile.getResource();
        assertTrue(stoneResc.hasProperty(RDF.type, Cdr.Tombstone));
        assertTrue(stoneResc.hasProperty(RDF.type, Cdr.FileObject));
        // check to make sure metadata from binary was retained by file obj's tombstone
        assertTrue(stoneResc.hasProperty(Ebucore.filename));
        assertTrue(stoneResc.hasProperty(Cdr.hasMessageDigest));
        assertTrue(stoneResc.hasProperty(Ebucore.hasMimeType));
        assertTrue(stoneResc.hasProperty(Cdr.hasSize));

        assertFalse("Original file must be deleted", Files.exists(Paths.get(contentUri)));

        verify(indexingMessageSender).sendIndexingOperation(anyString(), eq(fileObjPid), eq(DELETE_SOLR_TREE));

        verify(binaryDestroyedMessageSender).sendMessage(docCaptor.capture());

        assertMessagePresent(docCaptor.getAllValues(), filesToCleanup, null);
    }

    @Test
    public void destroyObjectsInSameTreeTest() throws Exception {
        //remove unrelated folder obj before running job
        objsToDestroy.remove(3);

        initializeJob(objsToDestroy);

        PID fileObjPid = objsToDestroy.get(2);
        PID workObjPid = objsToDestroy.get(1);
        PID folderObjPid = objsToDestroy.get(0);

        FileObject fileObj = repoObjLoader.getFileObject(fileObjPid);
        WorkObject workObj = repoObjLoader.getWorkObject(workObjPid);
        FolderObject folderObj = repoObjLoader.getFolderObject(folderObjPid);
        Map<URI, Map<String, String>> filesToCleanup = derivativesToCleanup(fileObj);
        Map<URI, Map<String, String>> nonBinariesToCleanup = new HashMap<>();
        nonBinariesToCleanup.put(folderObj.getUri(), nonBinaryToCleanup(folderObj));
        nonBinariesToCleanup.put(workObj.getUri(), nonBinaryToCleanup(workObj));

        job.run();

        Tombstone stoneFile = repoObjLoader.getTombstone(fileObjPid);
        Resource fileResc = stoneFile.getResource();
        Tombstone stoneWork = repoObjLoader.getTombstone(workObjPid);
        Resource workResc = stoneWork.getResource();
        Tombstone stoneFolder = repoObjLoader.getTombstone(folderObjPid);
        Resource folderResc = stoneFolder.getResource();
        assertTrue(fileResc.hasProperty(RDF.type, Cdr.Tombstone));
        assertTrue(fileResc.hasProperty(RDF.type, Cdr.FileObject));
        assertTrue(fileResc.hasProperty(PcdmModels.memberOf, workResc));
        assertTrue(workResc.hasProperty(RDF.type, Cdr.Tombstone));
        assertTrue(workResc.hasProperty(RDF.type, Cdr.Work));
        assertTrue(workResc.hasProperty(PcdmModels.memberOf, folderResc));
        assertTrue(folderResc.hasProperty(RDF.type, Cdr.Tombstone));
        assertTrue(folderResc.hasProperty(RDF.type, Cdr.Folder));

        Model logModel = stoneFolder.getPremisLog().getEventsModel();
        assertTrue(logModel.contains(null, RDF.type, Premis.Deletion));
        assertTrue(logModel.contains(null, Premis.note,
                "Item deleted from repository and replaced by tombstone"));

        verify(indexingMessageSender).sendIndexingOperation(anyString(), eq(folderObjPid), eq(DELETE_SOLR_TREE));

        verify(binaryDestroyedMessageSender, times(3)).sendMessage(docCaptor.capture());

        assertMessagePresent(docCaptor.getAllValues(), filesToCleanup, nonBinariesToCleanup);
    }

    @Test
    public void destroyObjectsInDifferentTreesTest() {
        initializeJob(objsToDestroy);

        job.run();

        PID folderObj2Pid = objsToDestroy.get(3);
        PID fileObjPid = objsToDestroy.get(2);
        PID workObjPid = objsToDestroy.get(1);
        PID folderObj1Pid = objsToDestroy.get(0);

        Tombstone stoneFile = repoObjLoader.getTombstone(fileObjPid);
        Tombstone stoneWork = repoObjLoader.getTombstone(workObjPid);
        Tombstone stoneFolder1 = repoObjLoader.getTombstone(folderObj1Pid);
        Tombstone stoneFolder2 = repoObjLoader.getTombstone(folderObj2Pid);
        assertTrue(stoneFile.getModel().contains(stoneFile.getResource(), RDF.type, Cdr.Tombstone));
        assertTrue(stoneWork.getModel().contains(stoneWork.getResource(), RDF.type, Cdr.Tombstone));
        assertTrue(stoneFolder1.getModel().contains(stoneFolder1.getResource(), RDF.type, Cdr.Tombstone));
        assertTrue(stoneFolder2.getModel().contains(stoneFolder2.getResource(), RDF.type, Cdr.Tombstone));

        Model logModel1 = stoneFolder1.getPremisLog().getEventsModel();
        assertTrue(logModel1.contains(null, RDF.type, Premis.Deletion));
        assertTrue(logModel1.contains(null, Premis.note,
                "Item deleted from repository and replaced by tombstone"));

        Model logModel2 = stoneFolder2.getPremisLog().getEventsModel();
        assertTrue(logModel2.contains(null, RDF.type, Premis.Deletion));
        assertTrue(logModel2.contains(null, Premis.note,
                "Item deleted from repository and replaced by tombstone"));

        verify(indexingMessageSender).sendIndexingOperation(anyString(), eq(folderObj1Pid), eq(DELETE_SOLR_TREE));
        verify(indexingMessageSender).sendIndexingOperation(anyString(), eq(folderObj2Pid), eq(DELETE_SOLR_TREE));
    }

    @Test
    public void destroyFolderTest() throws Exception {
        PID folderObjPid = objsToDestroy.get(0);
        initializeJob(Arrays.asList(folderObjPid));
        FolderObject folderObj = repoObjLoader.getFolderObject(folderObjPid);
        WorkObject workObj = (WorkObject) folderObj.getMembers().get(0);
        FileObject fileObj = (FileObject) workObj.getMembers().get(0);

        Map<URI, Map<String, String>> filesToCleanup = derivativesToCleanup(fileObj);
        Map<URI, Map<String, String>> nonBinariesToCleanup = new HashMap<>();
        nonBinariesToCleanup.put(folderObj.getUri(), nonBinaryToCleanup(folderObj));
        nonBinariesToCleanup.put(workObj.getUri(), nonBinaryToCleanup(workObj));

        job.run();

        RepositoryObject folderObjParent = folderObj.getParent();
        Model logParentModel = folderObjParent.getPremisLog().getEventsModel();
        assertTrue(logParentModel.contains(null, RDF.type, Premis.Deletion));
        assertTrue(logParentModel.contains(null, Premis.note, "3 object(s) were destroyed"));

        Resource fileResc = fileObj.getResource();
        Resource workResc = workObj.getResource();
        Resource folderResc = folderObj.getResource();
        assertTrue(fileResc.hasProperty(RDF.type, Cdr.Tombstone));
        assertTrue(fileResc.hasProperty(PcdmModels.memberOf, workResc));
        assertTrue(workResc.hasProperty(RDF.type, Cdr.Tombstone));
        assertTrue(workResc.hasProperty(PcdmModels.memberOf, folderResc));
        assertTrue(folderResc.hasProperty(RDF.type, Cdr.Tombstone));

        verify(indexingMessageSender).sendIndexingOperation(anyString(), eq(folderObjPid), eq(DELETE_SOLR_TREE));

        verify(binaryDestroyedMessageSender, times(3)).sendMessage(docCaptor.capture());
        List<Document> values = docCaptor.getAllValues();
        assertMessagePresent(values, filesToCleanup, nonBinariesToCleanup);
    }

    @Test
    public void destroySingleObjectWithPreexistingPremisEventTest() {
        PID fileObjPid = objsToDestroy.get(2);
        FileObject fileObj = repoObjLoader.getFileObject(fileObjPid);
        Resource event = fileObj.getPremisLog().buildEvent(null, Premis.Ingestion, new Date(1L)).write();

        initializeJob(Collections.singletonList(fileObjPid));

        job.run();

        Model logParentModel = fileObj.getParent().getPremisLog().getEventsModel();
        assertTrue(logParentModel.contains(null, RDF.type, Premis.Deletion));
        assertTrue(logParentModel.contains(null, Premis.note, "1 object(s) were destroyed"));

        Tombstone stoneFile = repoObjLoader.getTombstone(fileObjPid);
        assertTrue(stoneFile.getModel().contains(stoneFile.getResource(), RDF.type, Cdr.Tombstone));

        Model logModel = stoneFile.getPremisLog().getEventsModel();
        assertTrue(logModel.contains(null, RDF.type, Premis.Deletion));
        assertTrue(logModel.contains(event, RDF.type, Premis.Ingestion));
    }

    private List<PID> createContentTree() throws Exception {
        PID contentRootPid = getContentRootPid();
        repoInitializer.initializeRepository();
        ContentRootObject contentRoot = repoObjLoader.getContentRootObject(contentRootPid);

        AdminUnit adminUnit = repoObjFactory.createAdminUnit(new AclModelBuilder("Unit")
                .addUnitOwner(agent.getUsernameUri())
                .model);
        contentRoot.addMember(adminUnit);

        CollectionObject collection = repoObjFactory.createCollectionObject(null);
        adminUnit.addMember(collection);
        FolderObject folder = repoObjFactory.createFolderObject(null);
        FolderObject folder2 = repoObjFactory.createFolderObject(null);
        collection.addMember(folder);
        collection.addMember(folder2);
        WorkObject work = repoObjFactory.createWorkObject(null);
        folder.addMember(work);
        String bodyString = "Content";
        String mimetype = "text/plain";
        Path storagePath = Paths.get(locationManager.getStorageLocationById(LOC1_ID).getNewStorageUri(work.getPid()));
        Files.createDirectories(storagePath);
        File contentFile = Files.createTempFile(storagePath, "file", ".txt").toFile();
        String sha1 = "4f9be057f0ea5d2ba72fd2c810e8d7b9aa98b469";
        String filename = contentFile.getName();
        FileUtils.writeStringToFile(contentFile, bodyString, "UTF-8");
        FileObject file = work.addDataFile(contentFile.toPath().toUri(), filename, mimetype, sha1, null);

        treeIndexer.indexAll(baseAddress);

        objsToDestroy.add(folder.getPid());
        objsToDestroy.add(work.getPid());
        objsToDestroy.add(file.getPid());
        objsToDestroy.add(folder2.getPid());
        markObjsForDeletion(objsToDestroy);

        return objsToDestroy;
    }

    private void initializeJob(List<PID> objsToDestroy) {
        DestroyObjectsRequest request = new DestroyObjectsRequest("jobid", agent,
                objsToDestroy.stream().map(PID::getId).toArray(String[]::new));
        job = new DestroyObjectsJob(request);
        job.setPathFactory(pathFactory);
        job.setRepoObjFactory(repoObjFactory);
        job.setRepoObjLoader(repoObjLoader);
        job.setTransactionManager(txManager);
        job.setFcrepoClient(fcrepoClient);
        job.setAclService(aclService);
        job.setInheritedAclFactory(inheritedAclFactory);
        job.setBinaryTransferService(transferService);
        job.setStorageLocationManager(locationManager);
        job.setIndexingMessageSender(indexingMessageSender);
        job.setBinaryDestroyedMessageSender(binaryDestroyedMessageSender);
    }

    private void assertMessagePresent(List<Document> returnedDocs, Map<URI, Map<String, String>> filesToCleanup,
            Map<URI, Map<String, String>> nonBinariesToCleanup) {

        for (Document returnedDoc : returnedDocs) {
            Element root = returnedDoc.getRootElement();
            Element info = root.getChild("objToDestroy", CDR_MESSAGE_NS);
            String pidId = info.getChildTextTrim("pidId", CDR_MESSAGE_NS);
            String msgObjType = info.getChildTextTrim("objType", CDR_MESSAGE_NS);
            String msgMimetype = info.getChildTextTrim("mimeType", CDR_MESSAGE_NS);

            if (msgObjType.equals(Cdr.FileObject.getURI())) {
                URI uri = URI.create(info.getChildTextTrim("contentUri", CDR_MESSAGE_NS));
                Map<String, String> cleanupFile = filesToCleanup.get(uri);
                assertEquals(cleanupFile.get("objType"), msgObjType);
                assertEquals(cleanupFile.get("mimeType"), msgMimetype);
                assertEquals(cleanupFile.get("pid"), pidId);
            } else {
                Map<String, String> cleanupObj = nonBinariesToCleanup.get(PIDs.get(pidId).getRepositoryUri());
                assertEquals(msgObjType, cleanupObj.get("objType"));
                assertNull(msgMimetype);
            }
        }
    }

    private void markObjsForDeletion(List<PID> objsToDestroy) {
        for (PID pid : objsToDestroy) {
            String updateString = createSparqlReplace(pid.getRepositoryPath(), markedForDeletion,
                    true);
            sparqlUpdateService.executeUpdate(pid.getRepositoryUri().toString(), updateString);
        }
    }

    private Map<String, String> nonBinaryToCleanup(RepositoryObject obj) {
        Map<String, String> contentMetadata = new HashMap<>();
        contentMetadata.put("objType", ResourceType.getResourceTypeForUris(obj.getTypes()).getUri());
        PID pid = obj.getPid();
        contentMetadata.put("pid", pid.getQualifiedId());

        return contentMetadata;
    }

    private Map<URI, Map<String, String>> derivativesToCleanup(FileObject fileObj) {
        HashMap<URI, Map<String, String>> cleanupBinaryUris = new HashMap<>();

        BinaryObject binary = fileObj.getOriginalFile();
        PID binaryPid = binary.getPid();
        Map<String, String> contentMetadata = new HashMap<>();
        contentMetadata.put("objType", Cdr.FileObject.getURI());
        contentMetadata.put("pid", binaryPid.getQualifiedId());
        contentMetadata.put("mimeType", binary.getMimetype());
        cleanupBinaryUris.put(binary.getContentUri(), contentMetadata);

        return cleanupBinaryUris;
    }
}
