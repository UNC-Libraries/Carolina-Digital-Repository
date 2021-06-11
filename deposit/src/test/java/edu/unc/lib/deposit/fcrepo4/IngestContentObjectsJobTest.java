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
package edu.unc.lib.deposit.fcrepo4;

import static edu.unc.lib.dl.acl.util.AccessPrincipalConstants.AUTHENTICATED_PRINC;
import static edu.unc.lib.dl.acl.util.AccessPrincipalConstants.PUBLIC_PRINC;
import static edu.unc.lib.dl.model.DatastreamType.ORIGINAL_FILE;
import static edu.unc.lib.dl.model.DatastreamType.TECHNICAL_METADATA;
import static edu.unc.lib.dl.persist.services.storage.StorageLocationTestHelper.LOC1_ID;
import static edu.unc.lib.dl.test.TestHelpers.setField;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyCollectionOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.http.HttpStatus;
import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoOperationFailedException;
import org.fcrepo.client.HeadBuilder;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import edu.unc.lib.deposit.validate.VerifyObjectsAreInFedoraService;
import edu.unc.lib.deposit.work.JobFailedException;
import edu.unc.lib.deposit.work.JobInterruptedException;
import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.ContentContainerObject;
import edu.unc.lib.dl.fcrepo4.ContentRootObject;
import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPathConstants;
import edu.unc.lib.dl.fcrepo4.TransactionCancelledException;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.ChecksumMismatchException;
import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.storage.StorageLocationManager;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.persist.services.edit.UpdateDescriptionService;
import edu.unc.lib.dl.persist.services.edit.UpdateDescriptionService.UpdateDescriptionRequest;
import edu.unc.lib.dl.persist.services.event.PremisEventBuilderImpl;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrAcl;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.test.SelfReturningAnswer;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositField;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositState;

/**
 *
 * @author bbpennel
 *
 */
public class IngestContentObjectsJobTest extends AbstractDepositJobTest {

    private IngestContentObjectsJob job;

    private PID depositPid;

    private PID destinationPid;

    private Bag depBag;

    private Model model;

    private ContentContainerObject destinationObj;

    @Mock
    private PremisLoggerFactory mockPremisLoggerFactory;

    @Mock
    private RepositoryObjectLoader repoObjLoader;
    @Mock
    private RepositoryObjectFactory repoObjFactory;
    @Mock
    private TransactionManager txManager;
    @Mock
    private FcrepoClient fcrepoClient;

    @Mock
    private FedoraTransaction mockTx;

    @Mock
    private HeadBuilder headBuilder;

    @Mock
    private PremisLogger mockPremisLogger;

    private PremisEventBuilderImpl mockPremisEventBuilder;

    @Mock
    private AccessControlService aclService;

    @Mock
    private FileObject mockFileObj;

    @Mock
    private BinaryObject mockBinaryObj;

    @Mock
    private BinaryTransferService binaryTransferService;
    @Mock
    private StorageLocationManager storageLocationManager;
    @Mock
    private StorageLocation storageLocation;
    @Mock
    private BinaryTransferSession mockTransferSession;
    @Mock
    private UpdateDescriptionService updateDescService;

    @Captor
    private ArgumentCaptor<Model> modelCaptor;

    private Path storageLocPath;

    @Mock
    private VerifyObjectsAreInFedoraService verificationService;

    @Before
    public void init() throws Exception {
        job = new IngestContentObjectsJob();
        job.setJobUUID(jobUUID);
        job.setDepositUUID(depositUUID);
        job.setDepositDirectory(depositDir);
        setField(job, "premisLoggerFactory", mockPremisLoggerFactory);
        setField(job, "aclService", aclService);
        setField(job, "depositModelManager", depositModelManager);
        setField(job, "depositsDirectory", depositsDirectory);
        setField(job, "depositStatusFactory", depositStatusFactory);
        setField(job, "jobStatusFactory", jobStatusFactory);
        setField(job, "metricsClient", metricsClient);
        setField(job, "pidMinter", pidMinter);
        setField(job, "repoObjLoader", repoObjLoader);
        setField(job, "repoObjFactory", repoObjFactory);
        setField(job, "fcrepoClient", fcrepoClient);
        setField(job, "txManager", txManager);
        setField(job, "verificationService", verificationService);
        setField(job, "transferService", binaryTransferService);
        setField(job, "locationManager", storageLocationManager);
        setField(job, "updateDescService", updateDescService);
        setField(job, "depositModelManager", depositModelManager);

        job.init();

        depositPid = job.getDepositPID();

        setupDestination();

        FileUtils.copyDirectory(new File("src/test/resources/examples"), depositDir);

        storageLocPath = tmpFolder.newFolder("storageLoc").toPath();

        // Setup logging dependencies
        mockPremisEventBuilder = mock(PremisEventBuilderImpl.class, new SelfReturningAnswer());
        when(mockPremisLoggerFactory.createPremisLogger(any(PID.class), any(File.class)))
                .thenReturn(mockPremisLogger);
        when(mockPremisLogger.buildEvent(any(Resource.class))).thenReturn(mockPremisEventBuilder);

        when(mockFileObj.getOriginalFile()).thenReturn(mockBinaryObj);

        // Get a writeable model
        model = job.getWritableModel();
        depBag = model.createBag(depositPid.getRepositoryPath());

        when(fcrepoClient.head(any(URI.class))).thenReturn(headBuilder);

        when(txManager.startTransaction()).thenReturn(mockTx);
        doThrow(new TransactionCancelledException()).when(mockTx).cancel(any(Exception.class));

        depBag.addProperty(Cdr.storageLocation, LOC1_ID);
        when(storageLocationManager.getStorageLocationById(anyString())).thenReturn(storageLocation);
        when(binaryTransferService.getSession(any(StorageLocation.class))).thenReturn(mockTransferSession);
    }

    private void setupDestination() {
        // Establish a destination object to deposit to
        destinationPid = makePid(RepositoryPathConstants.CONTENT_BASE);

        Map<String, String> depositStatus = new HashMap<>();
        depositStatus.put(DepositField.containerId.name(), destinationPid.getQualifiedId());
        depositStatus.put(DepositField.permissionGroups.name(), "depositor");
        depositStatus.put(DepositField.depositorName.name(), "depositor");

        when(depositStatusFactory.get(anyString())).thenReturn(depositStatus);

        destinationObj = mock(FolderObject.class);
        when(destinationObj.getPremisLog()).thenReturn(mockPremisLogger);
        when(destinationObj.getPid()).thenReturn(destinationPid);
        when(repoObjLoader.getRepositoryObject(eq(destinationPid))).thenReturn(destinationObj);
    }

    /**
     * Test that an empty folder is successfully deposited
     */
    @Test
    public void ingestEmptyFolderTest() {
        FolderObject folder = mock(FolderObject.class);
        when(repoObjFactory.createFolderObject(any(PID.class), any(Model.class))).thenReturn(folder);

        PID folderPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        Bag folderBag = model.createBag(folderPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        when(folder.getPid()).thenReturn(folderPid);

        depBag.add(folderBag);

        job.closeModel();

        job.run();

        verify(repoObjFactory).createFolderObject(eq(folderPid), any(Model.class));
        verify(destinationObj).addMember(eq(folder));
        verify(jobStatusFactory).incrCompletion(eq(jobUUID), eq(1));
    }

    private Bag setupWork(PID workPid, WorkObject work) {
        when(repoObjFactory.createWorkObject(any(PID.class), any(Model.class))).thenReturn(work);
        when(work.getPid()).thenReturn(workPid);

        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);

        depBag.add(workBag);

        return workBag;
    }

    /**
     * Test that a deposit involving a work containing two files is successful
     */
    @Test
    public void ingestWorkObjectTest() throws Exception {
        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        WorkObject work = mock(WorkObject.class);
        Bag workBag = setupWork(workPid, work);

        String mainLoc = "pdf.pdf";
        String mainMime = "application/pdf";
        PID mainPid = addFileObject(workBag, mainLoc, mainMime);
        String supLoc = "text.txt";
        String supMime = "text/plain";
        PID supPid = addFileObject(workBag, supLoc, supMime);

        workBag.asResource().addProperty(Cdr.primaryObject,
                model.getResource(mainPid.getRepositoryPath()));

        job.closeModel();

        when(work.addDataFile(any(PID.class), any(URI.class),
                anyString(), anyString(), anyString(), anyString(), any(Model.class)))
                .thenReturn(mockFileObj);
        when(mockFileObj.getPid()).thenReturn(mainPid).thenReturn(supPid);
        when(repoObjLoader.getWorkObject(eq(workPid))).thenReturn(work);

        job.run();

        verify(repoObjFactory).createWorkObject(eq(workPid), any(Model.class));
        verify(destinationObj).addMember(eq(work));

        verify(work).addDataFile(eq(mainPid), any(URI.class), eq(mainLoc),
                eq(mainMime), anyString(), anyString(), any(Model.class));
        verify(work).addDataFile(eq(supPid), any(URI.class), eq(supLoc),
                eq(supMime), anyString(), anyString(), any(Model.class));
        verify(work).setPrimaryObject(mainPid);

        // Add work and file count
        verify(jobStatusFactory, times(3)).incrCompletion(eq(jobUUID), eq(1));

        verify(mockFileObj, times(2)).addBinary(any(PID.class), any(URI.class),
                anyString(), anyString(), isNull(String.class), isNull(String.class),
                any(Property.class), eq(DCTerms.conformsTo), any(Resource.class));
    }

    /**
     * Test that the deposit fails if a file object is specified but not given a
     * staging location
     */
    @Test(expected = JobFailedException.class)
    public void ingestWorkWithFileWithoutLocation() throws Exception {
        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        WorkObject work = mock(WorkObject.class);
        Bag workBag = setupWork(workPid, work);

        PID filePid = makePid(RepositoryPathConstants.CONTENT_BASE);

        Resource fileResc = model.createResource(filePid.getRepositoryPath());
        fileResc.addProperty(RDF.type, Cdr.FileObject);
        fileResc.addProperty(CdrDeposit.mimetype, "text/plain");
        workBag.add(fileResc);

        job.closeModel();

        when(repoObjLoader.getWorkObject(eq(workPid))).thenReturn(work);

        job.run();
    }

    /**
     * Test that deposit fails if depositing a file path that doesn't exist
     */
    @Test(expected = JobFailedException.class)
    public void ingestWorkWithFileDoesNotExist() throws Exception {
        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        WorkObject work = mock(WorkObject.class);
        Bag workBag = setupWork(workPid, work);

        addFileObject(workBag, "doesnotexist.txt", "text/plain");

        job.closeModel();

        when(work.addDataFile(any(PID.class), any(URI.class),
                anyString(), anyString(), anyString(), anyString(), any(Model.class)))
                .thenThrow(new FedoraException("Fail"))
                .thenReturn(mockFileObj);
        when(repoObjLoader.getWorkObject(eq(workPid))).thenReturn(work);

        job.run();
    }

    /**
     * Test that deposit fails when no permission to write to destination
     */
    @Test(expected = AccessRestrictionException.class)
    public void ingestFailNoPermissionsTest() throws Exception {
        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        WorkObject work = mock(WorkObject.class);
        Bag workBag = setupWork(workPid, work);

        addFileObject(workBag, "pdf.pdf", "application/pdf");

        doThrow(new AccessRestrictionException()).when(aclService)
                .assertHasAccess(anyString(), eq(destinationPid),
                        any(AccessGroupSet.class), eq(Permission.ingest));

        job.closeModel();

        job.run();
    }

    /**
     * Test resuming a Work ingest where the work and the main file were already
     * ingested
     */
    @Test
    public void resumeIngestWorkObjectTest() throws Exception {
        // Mark the deposit as resumed
        when(depositStatusFactory.isResumedDeposit(anyString())).thenReturn(true);

        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        WorkObject work = mock(WorkObject.class);
        Bag workBag = setupWork(workPid, work);
        when(repoObjLoader.getWorkObject(eq(workPid))).thenReturn(work);

        Model workModel = ModelFactory.createDefaultModel();
        Resource workResc = workModel.createResource(workPid.getRepositoryPath())
                .addProperty(RDF.type, Cdr.Work);
        when(work.getResource()).thenReturn(workResc);

        String mainLoc = "pdf.pdf";
        String mainMime = "application/pdf";
        PID mainPid = addFileObject(workBag, mainLoc, mainMime);
        String supLoc = "text.txt";
        String supMime = "text/plain";
        PID supPid = addFileObject(workBag, supLoc, supMime);

        workBag.asResource().addProperty(Cdr.primaryObject,
                model.getResource(mainPid.getRepositoryPath()));

        job.closeModel();

        when(work.addDataFile(any(PID.class), any(URI.class),
                anyString(), anyString(), anyString(), anyString(), any(Model.class)))
                .thenReturn(mockFileObj);
        when(mockFileObj.getPid()).thenReturn(mainPid).thenReturn(supPid);

        HeadBuilder notFoundBuilder = mock(HeadBuilder.class);
        when(fcrepoClient.head(eq(supPid.getRepositoryUri()))).thenReturn(notFoundBuilder);
        when(notFoundBuilder.perform()).thenThrow(new FcrepoOperationFailedException(
                destinationPid.getRepositoryUri(), HttpStatus.SC_NOT_FOUND, ""));

        job.run();

        // Check that the work object was retrieved rather than created
        // verify(repository).objectExists(eq(workPid));
        verify(repoObjFactory, never()).createWorkObject(any(PID.class), any(Model.class));
        verify(repoObjLoader).getWorkObject(any(PID.class));

        // Main file object should not be touched
        verify(work, never()).addDataFile(eq(mainPid), any(URI.class),
                anyString(), anyString(), anyString(), anyString(), any(Model.class));
        verify(repoObjLoader, never()).getFileObject(eq(mainPid));

        // Supplemental file should be created
        verify(repoObjLoader, never()).getFileObject(eq(supPid));
        verify(work).addDataFile(eq(supPid), any(URI.class), eq(supLoc),
                eq(supMime), anyString(), anyString(), any(Model.class));

        // Ensure that the primary object still got set
        verify(work).setPrimaryObject(mainPid);

        verify(jobStatusFactory).setCompletion(eq(jobUUID), eq(2));
        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(3));
    }

    @Test
    public void ingestWorkObjectWithTransientChecksumFailure() throws Exception {
        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        WorkObject work = mock(WorkObject.class);
        Bag workBag = setupWork(workPid, work);

        BinaryObject mockDescBin = mock(BinaryObject.class);
        when(mockDescBin.getContentUri()).thenReturn(URI.create("file://path/to/desc"));
        when(updateDescService.updateDescription(any(UpdateDescriptionRequest.class))).thenReturn(mockDescBin);

        Path modsPath = job.getModsPath(workPid, true);
        FileUtils.writeStringToFile(modsPath.toFile(), "Mods content", UTF_8);

        String mainLoc = "pdf.pdf";
        String mainMime = "application/pdf";
        PID mainPid = addFileObject(workBag, mainLoc, mainMime);
        String supLoc = "text.txt";
        String supMime = "text/plain";
        PID supPid = addFileObject(workBag, supLoc, supMime);

        workBag.asResource().addProperty(Cdr.primaryObject,
                model.getResource(mainPid.getRepositoryPath()));

        job.closeModel();

        when(work.addDataFile(any(PID.class), any(URI.class),
                anyString(), anyString(), anyString(), anyString(), any(Model.class)))
                .thenThrow(new ChecksumMismatchException("Temporarily bad"))
                .thenReturn(mockFileObj);
        when(mockFileObj.getPid()).thenReturn(mainPid).thenReturn(supPid);
        when(repoObjLoader.getWorkObject(eq(workPid))).thenReturn(work);

        job.run();

        verify(repoObjFactory).createWorkObject(eq(workPid), any(Model.class));
        verify(destinationObj).addMember(eq(work));

        verify(updateDescService).updateDescription(any(UpdateDescriptionRequest.class));

        verify(work, times(2)).addDataFile(eq(mainPid), any(URI.class), eq(mainLoc),
                eq(mainMime), anyString(), anyString(), any(Model.class));
        verify(work).addDataFile(eq(supPid), any(URI.class), eq(supLoc),
                eq(supMime), anyString(), anyString(), any(Model.class));
        verify(work).setPrimaryObject(mainPid);

        // Add work and file count
        verify(jobStatusFactory, times(3)).incrCompletion(eq(jobUUID), eq(1));

        verify(mockFileObj, times(2)).addBinary(any(PID.class), any(URI.class),
                anyString(), anyString(), isNull(String.class), isNull(String.class),
                any(Property.class), eq(DCTerms.conformsTo), any(Resource.class));
    }

    @Test
    public void pauseAndResumeTest() throws Exception {
        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        WorkObject work = mock(WorkObject.class);
        Bag workBag = setupWork(workPid, work);

        String mainLoc = "pdf.pdf";
        String mainMime = "application/pdf";
        PID mainPid = addFileObject(workBag, mainLoc, mainMime);

        workBag.addProperty(Cdr.primaryObject, model.getResource(mainPid.getRepositoryPath()));

        job.closeModel();

        when(work.addDataFile(any(PID.class), any(URI.class),
                anyString(), anyString(), anyString(), anyString(), any(Model.class)))
                .thenReturn(mockFileObj);
        when(mockFileObj.getPid()).thenReturn(mainPid);
        when(repoObjLoader.getWorkObject(eq(workPid))).thenReturn(work);

        when(depositStatusFactory.getState(depositUUID))
                .thenReturn(DepositState.running)
                .thenReturn(DepositState.running)
                .thenReturn(DepositState.paused);

        try {
            job.run();
            fail("Job must be interrupted due to pausing");
        } catch (JobInterruptedException e) {
            // expected
        }

        when(depositStatusFactory.getState(depositUUID))
                .thenReturn(DepositState.running);

        job.run();

        verify(repoObjFactory, times(2)).createWorkObject(eq(workPid), any(Model.class));
        verify(destinationObj, times(2)).addMember(eq(work));

        verify(work).addDataFile(eq(mainPid), any(URI.class), eq(mainLoc),
                eq(mainMime), anyString(), anyString(), any(Model.class));
        verify(work).setPrimaryObject(mainPid);

        verify(jobStatusFactory, times(2)).setTotalCompletion(eq(jobUUID), eq(2));
    }

    @Test
    public void ingestAdminUnitTest() {
        destinationObj = mock(ContentRootObject.class);
        when(destinationObj.getPid()).thenReturn(destinationPid);
        when(destinationObj.getPremisLog()).thenReturn(mockPremisLogger);
        when(repoObjLoader.getRepositoryObject(eq(destinationPid))).thenReturn(destinationObj);

        AdminUnit adminUnit = mock(AdminUnit.class);
        when(repoObjFactory.createAdminUnit(any(PID.class), any(Model.class))).thenReturn(adminUnit);

        PID adminPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        Bag adminBag = model.createBag(adminPid.getRepositoryPath());
        adminBag.addProperty(RDF.type, Cdr.AdminUnit);
        adminBag.addProperty(CdrAcl.canManage, "staff");
        when(adminUnit.getPid()).thenReturn(adminPid);

        depBag.add(adminBag);

        job.closeModel();

        job.run();

        verify(repoObjFactory).createAdminUnit(eq(adminPid), modelCaptor.capture());
        verify(destinationObj).addMember(eq(adminUnit));
        verify(jobStatusFactory).incrCompletion(eq(jobUUID), eq(1));

        Resource adminAipResc = modelCaptor.getValue().getResource(adminPid.getRepositoryPath());
        assertTrue("Admin object did not contain assigned restriction",
                adminAipResc.hasProperty(CdrAcl.canManage));
    }

    @Test(expected = AccessRestrictionException.class)
    public void ingestAdminUnitNoPermissionTest() {
        destinationObj = mock(ContentRootObject.class);
        when(destinationObj.getPid()).thenReturn(destinationPid);
        when(repoObjLoader.getRepositoryObject(eq(destinationPid))).thenReturn(destinationObj);

        // Throw access exception for creating admin unit
        doThrow(new AccessRestrictionException()).when(aclService)
                .assertHasAccess(anyString(), eq(destinationPid), any(AccessGroupSet.class),
                        eq(Permission.createAdminUnit));

        AdminUnit adminUnit = mock(AdminUnit.class);
        when(repoObjFactory.createAdminUnit(any(PID.class), any(Model.class))).thenReturn(adminUnit);

        PID adminPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        Bag adminBag = model.createBag(adminPid.getRepositoryPath());
        adminBag.addProperty(RDF.type, Cdr.AdminUnit);
        when(adminUnit.getPid()).thenReturn(adminPid);

        depBag.add(adminBag);

        job.closeModel();

        job.run();
    }

    @Test
    public void ingestCollectionTest() {
        destinationObj = mock(AdminUnit.class);
        when(destinationObj.getPid()).thenReturn(destinationPid);
        when(destinationObj.getPremisLog()).thenReturn(mockPremisLogger);
        when(repoObjLoader.getRepositoryObject(eq(destinationPid))).thenReturn(destinationObj);

        CollectionObject collection = mock(CollectionObject.class);
        when(repoObjFactory.createCollectionObject(any(PID.class), any(Model.class))).thenReturn(collection);

        PID collectionPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        Bag collectionBag = model.createBag(collectionPid.getRepositoryPath());
        collectionBag.addProperty(RDF.type, Cdr.Collection);
        collectionBag.addProperty(CdrAcl.canViewOriginals, AUTHENTICATED_PRINC);

        when(collection.getPid()).thenReturn(collectionPid);

        depBag.add(collectionBag);

        job.closeModel();

        job.run();

        verify(repoObjFactory).createCollectionObject(eq(collectionPid), modelCaptor.capture());
        verify(destinationObj).addMember(eq(collection));
        verify(jobStatusFactory).incrCompletion(eq(jobUUID), eq(1));

        Resource collAipResc = modelCaptor.getValue().getResource(collectionPid.getRepositoryPath());
        assertTrue("Collection object did not contain assigned restriction",
                collAipResc.hasProperty(CdrAcl.canViewOriginals, AUTHENTICATED_PRINC));
    }

    @Test(expected = AccessRestrictionException.class)
    public void ingestCollectionNoPermisionTest() {
        destinationObj = mock(AdminUnit.class);
        when(destinationObj.getPid()).thenReturn(destinationPid);
        when(repoObjLoader.getRepositoryObject(eq(destinationPid))).thenReturn(destinationObj);

        // Throw access exception for creating collection
        doThrow(new AccessRestrictionException()).when(aclService)
                .assertHasAccess(anyString(), eq(destinationPid), any(AccessGroupSet.class),
                        eq(Permission.createCollection));

        CollectionObject collection = mock(CollectionObject.class);
        when(repoObjFactory.createCollectionObject(any(PID.class), any(Model.class))).thenReturn(collection);

        PID collectionPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        Bag collectionBag = model.createBag(collectionPid.getRepositoryPath());
        collectionBag.addProperty(RDF.type, Cdr.Collection);
        when(collection.getPid()).thenReturn(collectionPid);

        depBag.add(collectionBag);

        job.closeModel();

        job.run();
    }

    @Test
    public void ingestFolderWithAcls() {
        FolderObject folder = mock(FolderObject.class);
        when(repoObjFactory.createFolderObject(any(PID.class), any(Model.class))).thenReturn(folder);

        PID folderPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        when(folder.getPid()).thenReturn(folderPid);

        Bag folderBag = model.createBag(folderPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        folderBag.addProperty(CdrAcl.canViewMetadata, PUBLIC_PRINC);

        depBag.add(folderBag);

        job.closeModel();

        job.run();

        verify(repoObjFactory).createFolderObject(eq(folderPid), modelCaptor.capture());

        Model aipModel = modelCaptor.getValue();
        Resource aipResc = aipModel.getResource(folderPid.getRepositoryPath());
        assertTrue(aipResc.hasProperty(CdrAcl.canViewMetadata));
    }

    @Test
    public void ingestWorkWithFileObjWithAcls() throws Exception {
        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        WorkObject work = mock(WorkObject.class);
        Bag workBag = setupWork(workPid, work);
        workBag.addProperty(CdrAcl.embargoUntil, model.createTypedLiteral(Calendar.getInstance()));

        String mainLoc = "pdf.pdf";
        String mainMime = "application/pdf";
        PID mainPid = addFileObject(workBag, mainLoc, mainMime);
        model.getResource(mainPid.getRepositoryPath())
                .addProperty(CdrAcl.canViewOriginals, AUTHENTICATED_PRINC);

        workBag.addProperty(Cdr.primaryObject,
                model.getResource(mainPid.getRepositoryPath()));

        job.closeModel();

        when(work.addDataFile(any(PID.class), any(URI.class),
                anyString(), anyString(), anyString(), anyString(), any(Model.class)))
                .thenReturn(mockFileObj);
        when(mockFileObj.getPid()).thenReturn(mainPid);
        when(repoObjLoader.getWorkObject(eq(workPid))).thenReturn(work);

        job.run();

        verify(repoObjFactory).createWorkObject(eq(workPid), modelCaptor.capture());

        Resource workAipResc = modelCaptor.getValue().getResource(workPid.getRepositoryPath());
        assertTrue("Work object did not contain assigned restriction",
                workAipResc.hasProperty(CdrAcl.embargoUntil));

        verify(work).addDataFile(eq(mainPid), any(URI.class), eq(mainLoc),
                eq(mainMime), anyString(), anyString(), modelCaptor.capture());

        Resource fileAipResc = modelCaptor.getValue().getResource(mainPid.getRepositoryPath());
        assertTrue("File object did not contain assigned restriction",
                fileAipResc.hasProperty(CdrAcl.canViewOriginals));
    }

    @Test(expected=JobFailedException.class)
    public void testObjectsNotInFedoraAfterIngest() throws Exception {
        FolderObject folder = mock(FolderObject.class);
        when(repoObjFactory.createFolderObject(any(PID.class), any(Model.class))).thenReturn(folder);

        PID folderPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        Bag folderBag = model.createBag(folderPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        when(folder.getPid()).thenReturn(folderPid);

        depBag.add(folderBag);

        job.closeModel();

        // have verification service return a non-empty list
        when(verificationService.listObjectsNotInFedora(anyCollectionOf(String.class)))
                .thenReturn(Arrays.asList(folderPid));

        job.run();
    }

    private PID addFileObject(Bag parent, String stagingLocation, String mimetype) throws Exception {
        PID filePid = makePid(RepositoryPathConstants.CONTENT_BASE);

        Model model = parent.getModel();
        String stagingPath = Paths.get(depositDir.getAbsolutePath(), stagingLocation).toUri().toString();
        String storagePath = storageLocPath.resolve(filePid.getId() + ".txt").toUri().toString();
        Resource fileResc = model.createResource(filePid.getRepositoryPath());
        fileResc.addProperty(RDF.type, Cdr.FileObject);
        fileResc.addProperty(CdrDeposit.label, stagingLocation);

        Resource origResc = DepositModelHelpers.addDatastream(fileResc, ORIGINAL_FILE);
        origResc.addProperty(CdrDeposit.stagingLocation, stagingPath);
        origResc.addProperty(CdrDeposit.storageUri, storagePath);
        origResc.addProperty(CdrDeposit.mimetype, mimetype);

        parent.add(fileResc);

        // Create the accompanying fake FITS report file
        Path fitsPath = job.getTechMdPath(filePid, true);
        Files.createFile(fitsPath);
        Resource fitsResc = DepositModelHelpers.addDatastream(fileResc, TECHNICAL_METADATA);
        fitsResc.addProperty(CdrDeposit.storageUri, fitsPath.toUri().toString());

        return filePid;
    }
}
