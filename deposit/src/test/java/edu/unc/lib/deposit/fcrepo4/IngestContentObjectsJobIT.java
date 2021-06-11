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

import static edu.unc.lib.dl.model.DatastreamType.ORIGINAL_FILE;
import static edu.unc.lib.dl.model.DatastreamType.TECHNICAL_METADATA;
import static edu.unc.lib.dl.persist.services.storage.StorageLocationTestHelper.LOC1_ID;
import static edu.unc.lib.dl.test.TestHelpers.setField;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DC;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.client.FcrepoClient;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import edu.unc.lib.deposit.validate.VerifyObjectsAreInFedoraService;
import edu.unc.lib.deposit.work.JobFailedException;
import edu.unc.lib.deposit.work.JobInterruptedException;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.ContentContainerObject;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.DepositRecord;
import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryPaths;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.model.DatastreamType;
import edu.unc.lib.dl.persist.services.deposit.DepositDirectoryManager;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.persist.services.edit.UpdateDescriptionService;
import edu.unc.lib.dl.persist.services.event.FilePremisLogger;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.rdf.Prov;
import edu.unc.lib.dl.test.AclModelBuilder;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.util.DateTimeUtil;
import edu.unc.lib.dl.util.DepositStatusFactory;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositField;
import edu.unc.lib.dl.util.RedisWorkerConstants.JobField;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;

/**
 *
 * @author bbpennel
 *
 */
public class IngestContentObjectsJobIT extends AbstractFedoraDepositJobIT {

    private static final Logger log = getLogger(IngestContentObjectsJobIT.class);

    static {
        System.setProperty("fcrepo.properties.management", "relaxed");
    }

    private static final String INGESTOR_PRINC = "ingestor";
    private static final String DEPOSITOR_NAME = "boxy_depositor";

    private IngestContentObjectsJob job;

    private PID destinationPid;

    private static final String FILE1_LOC = "pdf.pdf";
    private static final String FILE1_MIMETYPE = "application/pdf";
    private static final String FILE1_SHA1 = "7185198c0f158a3b3caa3f387efa3df990d2a904";
    private static final String FILE1_MD5 = "b5808604069f9f61d94e0660409616ba";
    private static final String FILE1_MD5_BAD = "b7908604069f9f61d94e0660409616ba";
    private static final long FILE1_SIZE = 739L;
    private static final String FILE2_LOC = "text.txt";
    private static final String FILE2_MIMETYPE = "text/plain";
    private static final long FILE2_SIZE = 4L;
    private static final String BLANK_SHA1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

    @Autowired
    private AccessControlService aclService;
    @Autowired
    private DepositStatusFactory depositStatusFactory;

    @Autowired
    private RepositoryObjectFactory repoObjFactory;
    @Autowired
    private TransactionManager txManager;
    @Autowired
    private FcrepoClient fcrepoClient;
    @Autowired
    private RepositoryObjectTreeIndexer treeIndexer;
    @Autowired
    private VerifyObjectsAreInFedoraService verificationService;
    @Autowired
    private UpdateDescriptionService updateDescService;

    private DepositDirectoryManager depositDirManager;

    private DepositRecord depositRecord;

    @Before
    public void init() throws Exception {
        constructJob();

        // Create a destination folder where deposits will be ingested to
        setupDestination();

        FileUtils.copyDirectory(new File("src/test/resources/examples"), depositDir);

        // Create deposit record for this deposit to reference
        depositRecord = repoObjFactory.createDepositRecord(depositPid, null);
        Model model = job.getWritableModel();
        Resource depositResc = model.getResource(depositPid.getRepositoryPath());
        depositResc.addProperty(Cdr.storageLocation, LOC1_ID);
        job.closeModel();

        depositStatusFactory.set(depositUUID, DepositField.depositorName, DEPOSITOR_NAME);

        depositDirManager = new DepositDirectoryManager(depositPid, depositsDirectory.toPath(), true);
    }

    private void constructJob() {
        job = new IngestContentObjectsJob();
        job.setJobUUID(jobUUID);
        job.setDepositUUID(depositUUID);
        job.setDepositDirectory(depositDir);
        job.setPremisLoggerFactory(premisLoggerFactory);
        setField(job, "pidMinter", pidMinter);
        setField(job, "aclService", aclService);
        setField(job, "depositModelManager", depositModelManager);
        setField(job, "depositsDirectory", depositsDirectory);
        setField(job, "depositStatusFactory", depositStatusFactory);
        setField(job, "txManager", txManager);
        setField(job, "jobStatusFactory", jobStatusFactory);
        setField(job, "metricsClient", metricsClient);
        setField(job, "repoObjLoader", repoObjLoader);
        setField(job, "repoObjFactory", repoObjFactory);
        setField(job, "fcrepoClient", fcrepoClient);
        setField(job, "verificationService", verificationService);
        setField(job, "transferService", binaryTransferService);
        setField(job, "locationManager", storageLocationManager);
        setField(job, "updateDescService", updateDescService);
        job.init();
    }

    private void setupDestination() throws Exception {
        AdminUnit unitObj = repoObjFactory.createAdminUnit(null);
        CollectionObject collObj = repoObjFactory.createCollectionObject(
                new AclModelBuilder("Coll").addCanIngest(INGESTOR_PRINC).model);
        FolderObject destFolder = repoObjFactory.createFolderObject(null);

        rootObj.addMember(unitObj);
        unitObj.addMember(collObj);
        collObj.addMember(destFolder);

        treeIndexer.indexAll(baseAddress);

        destinationPid = destFolder.getPid();

        Map<String, String> status = new HashMap<>();
        status.put(DepositField.containerId.name(), destinationPid.getRepositoryPath());
        status.put(DepositField.permissionGroups.name(), INGESTOR_PRINC);
        depositStatusFactory.save(depositUUID, status);
    }

    /**
     * Test that a single folder can be created
     */
    @Test
    public void ingestEmptyFolderTest() throws Exception {
        String label = "testfolder";

        // Construct the deposit model, containing a deposit with one empty folder
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the folder in the deposit model with a title
        PID folderPid = pidMinter.mintContentPid();
        Bag folderBag = model.createBag(folderPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        folderBag.addProperty(CdrDeposit.label, label);
        folderBag.addLiteral(Cdr.storageLocation, LOC1_ID);

        depBag.add(folderBag);
        job.closeModel();

        // Execute the ingest job
        job.run();

        treeIndexer.indexAll(baseAddress);

        // Verify that the destination has the folder added to it
        RepositoryObject destObj = repoObjLoader.getRepositoryObject(destinationPid);
        List<ContentObject> destMembers = ((ContentContainerObject) destObj).getMembers();
        assertEquals("Incorrect number of children at destination", 1, destMembers.size());

        Model destLogModel = destObj.getPremisLog().getEventsModel();
        assertTrue(destLogModel.contains(null, Premis.note,
                "added child object " + folderPid.getRepositoryPath() + " to this container"));

        // Make sure that the folder is present and is actually a folder
        ContentObject mFolder = findContentObjectByPid(destMembers, folderPid);
        assertTrue("Child was not a folder", mFolder instanceof FolderObject);

        // Try directly retrieving the folder
        FolderObject folder = repoObjLoader.getFolderObject(folderPid);
        // Verify that its title was set to the expected value
        String title = folder.getResource(true).getProperty(DC.title).getString();
        assertEquals("Folder title was not correctly set", label, title);
        // Verify that ingestion event gets added for folder
        Model logModel = folder.getPremisLog().getEventsModel();
        Resource eventResc = logModel.listResourcesWithProperty(Prov.generated).toList().get(0);
        assertTrue("Ingestion event did not have expected note",
                eventResc.hasProperty(Premis.note, "ingested as PID: " + folder.getPid().getQualifiedId()));
        Resource authAgent = eventResc.getPropertyResourceValue(Premis.hasEventRelatedAgentAuthorizor);
        assertEquals(AgentPids.forPerson(DEPOSITOR_NAME).getRepositoryPath(), authAgent.getURI());
        assertStorageLocationPresent(folder);

        assertClickCount(1);
        ingestedObjectsCount(1);

        assertLinksToDepositRecord(folder);
    }

    /**
     * Test that work objects are created along with relationships to their children.
     */
    @Test
    public void ingestWorkObjectTest() throws Exception {
        String label = "testwork";

        // Construct the deposit model with work object
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the work in the deposit model with a label
        PID workPid = pidMinter.mintContentPid();
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);
        workBag.addProperty(CdrDeposit.label, label);

        PID mainPid = addFileObject(workBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5);
        PID supPid = addFileObject(workBag, FILE2_LOC, FILE2_MIMETYPE, null, null);

        depBag.add(workBag);

        workBag.asResource().addProperty(Cdr.primaryObject,
                model.getResource(mainPid.getRepositoryPath()));

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        ContentContainerObject destObj = (ContentContainerObject) repoObjLoader.getRepositoryObject(destinationPid);
        List<ContentObject> destMembers = destObj.getMembers();
        assertEquals("Incorrect number of children at destination", 1, destMembers.size());

        // Make sure that the work is present and is actually a work
        WorkObject mWork = (WorkObject) findContentObjectByPid(destMembers, workPid);

        String title = mWork.getResource().getProperty(DC.title).getString();
        assertEquals("Work title was not correctly set", label, title);

        // Verify that the properties of the primary object were added
        FileObject primaryObj = mWork.getPrimaryObject();
        assertBinaryProperties(primaryObj, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5, FILE1_SIZE);
        PID fitsPid = DatastreamPids.getTechnicalMetadataPid(primaryObj.getPid());
        BinaryObject fitsBin = repoObjLoader.getBinaryObject(fitsPid);
        assertBinaryProperties(fitsBin, TECHNICAL_METADATA.getDefaultFilename(),
                TECHNICAL_METADATA.getMimetype(), BLANK_SHA1, null, 0);

        // Check the right number of members are present
        List<ContentObject> workMembers = mWork.getMembers();
        assertEquals("Incorrect number of members in work", 2, workMembers.size());
        FileObject supObj = (FileObject) findContentObjectByPid(workMembers, supPid);
        assertNotNull(supObj);

        // Verify the properties and content of the supplemental file
        assertBinaryProperties(supObj, FILE2_LOC, FILE2_MIMETYPE, null, null, FILE2_SIZE);
        // Verify that ingestion event gets added for work
        Model workLogModel = mWork.getPremisLog().getEventsModel();
        assertTrue(workLogModel.contains(null, Premis.note,
                "ingested as PID: " + mWork.getPid().getQualifiedId()));
        assertTrue(workLogModel.contains(null, Premis.note,
                "added 2 child objects to this container"));
        List<Resource> eventRescs = workLogModel.listResourcesWithProperty(Prov.generated).toList();
        for (Resource eventResc: eventRescs) {
            Resource authAgent = eventResc.getPropertyResourceValue(Premis.hasEventRelatedAgentAuthorizor);
            assertEquals(AgentPids.forPerson(DEPOSITOR_NAME).getRepositoryPath(), authAgent.getURI());
        }

        // Verify that ingestion event gets added for primary object
        Model primLogModel = primaryObj.getPremisLog().getEventsModel();
        assertTrue(primLogModel.contains(null, Premis.note,
                "ingested as PID: " + mainPid.getQualifiedId()
                + "\n ingested as filename: " + FILE1_LOC));

        // Verify that ingestion event gets added for supplementary object
        Model supLogModel = supObj.getPremisLog().getEventsModel();
        assertTrue(supLogModel.contains(null, Premis.note,
                "ingested as PID: " + supPid.getQualifiedId()
                + "\n ingested as filename: " + FILE2_LOC));

        assertClickCount(3);
        ingestedObjectsCount(3);

        assertLinksToDepositRecord(mWork, primaryObj);
    }

    /**
     * Test that a single file can be ingested into an existing work, as in the SimpleFile deposit case
     */
    @Test
    public void ingestSimpleFile() throws Exception {
        WorkObject destWork = repoObjFactory.createWorkObject(null);
        FolderObject parentFolder = repoObjLoader.getFolderObject(destinationPid);
        parentFolder.addMember(destWork);

        treeIndexer.indexTree(destWork.getModel());

        depositStatusFactory.set(depositUUID, DepositField.containerId, destWork.getPid().getRepositoryPath());

        // Construct the deposit model with work object
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        PID mainPid = addFileObject(depBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5);

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        // Make sure that the work is present and is actually a work
        WorkObject mWork = repoObjLoader.getWorkObject(destWork.getPid());

        // Verify that the properties of the primary object were added
        FileObject primaryObj = (FileObject) mWork.getMembers().get(0);
        assertBinaryProperties(primaryObj, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5, FILE1_SIZE);

        // Check the right number of members are present
        List<ContentObject> workMembers = mWork.getMembers();
        assertEquals("Incorrect number of members in work", 1, workMembers.size());

        // Verify that ingestion event gets added for work
        Model workLogModel = mWork.getPremisLog().getEventsModel();
        assertTrue(workLogModel.contains(null, Premis.note,
                "added child object " + mainPid.getRepositoryPath() + " to this container"));

        // Verify that ingestion event gets added for primary object
        Model primLogModel = primaryObj.getPremisLog().getEventsModel();
        assertTrue(primLogModel.contains(null, Premis.note,
                "ingested as PID: " + mainPid.getQualifiedId()
                + "\n ingested as filename: " + FILE1_LOC));

        assertClickCount(1);
        ingestedObjectsCount(1);

        assertLinksToDepositRecord(primaryObj);
    }

    @Test
    public void ingestWorkObjectChecksumErrorRetryLimitTest() throws Exception {
        String label = "testwork";
        PID workPid = pidMinter.mintContentPid();

        try {
            // Construct the deposit model with work object
            Model model = job.getWritableModel();
            Bag depBag = model.createBag(depositPid.getRepositoryPath());

            // Constructing the work in the deposit model with a label
            Bag workBag = model.createBag(workPid.getRepositoryPath());
            workBag.addProperty(RDF.type, Cdr.Work);
            workBag.addProperty(CdrDeposit.label, label);

            PID mainPid = addFileObject(workBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5_BAD);

            depBag.add(workBag);

            workBag.asResource().addProperty(Cdr.primaryObject,
                    model.getResource(mainPid.getRepositoryPath()));

            job.closeModel();
            job.run();
        } catch (Exception e) {
            treeIndexer.indexAll(baseAddress);

            ContentContainerObject destObj = (ContentContainerObject) repoObjLoader.getRepositoryObject(destinationPid);
            List<ContentObject> destMembers = destObj.getMembers();
            assertEquals("Incorrect number of children at destination", 1, destMembers.size());

            WorkObject workObj = (WorkObject) destMembers.get(0);
            assertEquals("Incorrect number of files added", 0, workObj.getMembers().size());
        }
    }

    @Test
    public void ingestWorkObjectWithModsHistoryTest() throws Exception {
        String label = "testwork";

        // Construct the deposit model with work object
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the work in the deposit model with a label
        PID workPid = pidMinter.mintContentPid();
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);
        workBag.addProperty(CdrDeposit.label, label);

        depBag.add(workBag);

        Resource historyResc = DepositModelHelpers.addDatastream(workBag, DatastreamType.MD_DESCRIPTIVE_HISTORY);
        Path modsPath = job.getModsPath(workPid, true);
        FileUtils.writeStringToFile(modsPath.toFile(), "Mods content", UTF_8);
        Path modsHistoryPath = depositDirManager.getHistoryFile(workPid, DatastreamType.MD_DESCRIPTIVE_HISTORY);
        FileUtils.writeStringToFile(modsHistoryPath.toFile(), "History content", UTF_8);
        String modsHistorySha1 = getSha1(modsHistoryPath);
        historyResc.addProperty(CdrDeposit.storageUri, modsHistoryPath.toUri().toString());
        historyResc.addLiteral(CdrDeposit.sha1sum, modsHistorySha1);

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        ContentContainerObject destObj = (ContentContainerObject) repoObjLoader.getRepositoryObject(destinationPid);
        List<ContentObject> destMembers = destObj.getMembers();
        assertEquals("Incorrect number of children at destination", 1, destMembers.size());

        // Make sure that the work is present and is actually a work
        WorkObject mWork = (WorkObject) findContentObjectByPid(destMembers, workPid);
        BinaryObject descBin = mWork.getDescription();
        assertEquals("Mods content", IOUtils.toString(descBin.getBinaryStream(), UTF_8));

        PID historyPid = DatastreamPids.getDatastreamHistoryPid(descBin.getPid());
        BinaryObject historyBin = repoObjLoader.getBinaryObject(historyPid);
        assertEquals("History content", IOUtils.toString(historyBin.getBinaryStream(), UTF_8));
        assertEquals("urn:sha1:" + modsHistorySha1, historyBin.getSha1Checksum());
    }

    @Test
    public void ingestWorkObjectWithFITSHistoryTest() throws Exception {
        String label = "testwork";

        // Construct the deposit model with work object
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the work in the deposit model with a label
        PID workPid = pidMinter.mintContentPid();
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);
        workBag.addProperty(CdrDeposit.label, label);

        PID mainPid = addFileObject(workBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5);

        Resource mainResc = model.getResource(mainPid.getRepositoryPath());

        PID fitsPid = DatastreamPids.getTechnicalMetadataPid(mainPid);
        PID historyPid = DatastreamPids.getDatastreamHistoryPid(fitsPid);
        Resource historyResc = DepositModelHelpers.addDatastream(mainResc, DatastreamType.TECHNICAL_METADATA_HISTORY);
        Path historyPath = depositDirManager.getHistoryFile(fitsPid, DatastreamType.TECHNICAL_METADATA_HISTORY);
        FileUtils.writeStringToFile(historyPath.toFile(), "History content", UTF_8);
        String historySha1 = getSha1(historyPath);
        historyResc.addProperty(CdrDeposit.storageUri, historyPath.toUri().toString());
        historyResc.addLiteral(CdrDeposit.sha1sum, historySha1);

        depBag.add(workBag);

        workBag.addProperty(Cdr.primaryObject, model.getResource(mainPid.getRepositoryPath()));

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        ContentContainerObject destObj = (ContentContainerObject) repoObjLoader.getRepositoryObject(destinationPid);
        List<ContentObject> destMembers = destObj.getMembers();
        assertEquals("Incorrect number of children at destination", 1, destMembers.size());

        // Make sure that the work is present and is actually a work
        WorkObject mWork = (WorkObject) findContentObjectByPid(destMembers, workPid);

        String title = mWork.getResource().getProperty(DC.title).getString();
        assertEquals("Work title was not correctly set", label, title);

        // Verify that the properties of the primary object were added
        FileObject primaryObj = mWork.getPrimaryObject();
        assertBinaryProperties(primaryObj, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5, FILE1_SIZE);
        BinaryObject fitsBin = repoObjLoader.getBinaryObject(fitsPid);
        assertBinaryProperties(fitsBin, TECHNICAL_METADATA.getDefaultFilename(),
                TECHNICAL_METADATA.getMimetype(), BLANK_SHA1, null, 0);

        BinaryObject historyBin = repoObjLoader.getBinaryObject(historyPid);
        assertEquals("History content", IOUtils.toString(historyBin.getBinaryStream(), UTF_8));
        assertEquals("urn:sha1:" + historySha1, historyBin.getSha1Checksum());
        assertEquals("text/xml", historyBin.getMimetype());

        assertClickCount(2);
        ingestedObjectsCount(2);

        assertLinksToDepositRecord(mWork, primaryObj);
    }

    /**
     * Ensure that deposit fails on a sha1 checksum mismatch for a single file deposit
     */
    @Test(expected = JobFailedException.class)
    public void ingestFileObjectChecksumMismatch() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String badSha1 = "111111111111111111111111111111111111";
        PID filePid = addWorkWithFileObject(depBag, FILE1_LOC, FILE1_MIMETYPE, badSha1, null).get(1);

        job.closeModel();

        try {
            job.run();
        } finally {
            assertFalse(FedoraTransaction.hasTxId());
            assertFalse(FedoraTransaction.isStillAlive());
            assertFalse(objectExists(filePid));
        }
    }

    /**
     * Ensure that deposit fails on a md5 checksum mismatch for a single file deposit
     */
    @Test(expected = JobFailedException.class)
    public void ingestFileObjectMd5ChecksumMismatch() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String badMd5 = "111111111111111111111111111111111111";
        addWorkWithFileObject(depBag, FILE1_LOC, FILE1_MIMETYPE, null, badMd5);

        job.closeModel();

        job.run();
    }

    /**
     * Verify that resuming a completed deposit doesn't result in extra objects
     */
    @Test
    public void resumeCompletedWorkIngestTest() throws Exception {
        ingestWorkObjectTest();

        job.run();

        treeIndexer.indexAll(baseAddress);

        RepositoryObject destObj = repoObjLoader.getRepositoryObject(destinationPid);
        List<ContentObject> destMembers = ((ContentContainerObject) destObj).getMembers();
        assertEquals("Incorrect number of children at destination", 1, destMembers.size());

        List<ContentObject> workMembers = ((ContentContainerObject) destMembers.get(0)).getMembers();
        assertEquals("Incorrect number of members in work", 2, workMembers.size());

        assertClickCount(3);
        ingestedObjectsCount(3);
    }

    @Test
    public void ingestWorkObjectObjCountWithRetry() throws Exception {
        String label = "testwork";
        PID workPid = pidMinter.mintContentPid();

        // Construct the deposit model with work object
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the work in the deposit model with a label
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);
        workBag.addProperty(CdrDeposit.label, label);

        PID mainPid = addFileObject(workBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5);
        PID mainPid2 = addFileObject(workBag, FILE1_LOC, FILE1_MIMETYPE, null, FILE1_MD5_BAD);

        depBag.add(workBag);

        workBag.addProperty(Cdr.primaryObject,
                model.getResource(mainPid.getRepositoryPath()));

        job.closeModel();

        try {
            job.run();
            fail("Test should not reach this line. Job should have thrown exception");
        } catch (JobFailedException e) {
            Map<String, String> jobStatus = jobStatusFactory.get(jobUUID);
            assertEquals("2", jobStatus.get(JobField.num.name()));

            Resource fileResc = job.getWritableModel().getResource(mainPid2.getRepositoryPath());
            Resource origResc = DepositModelHelpers.getDatastream(fileResc);
            origResc.removeAll(CdrDeposit.md5sum);
            origResc.addProperty(CdrDeposit.md5sum, FILE1_MD5);
            job.closeModel();
        }

        job.run();

        assertClickCount(3);
        ingestedObjectsCount(3);
    }

    /**
     * Tests ingest of a folder with two files in it, which fails on the second
     * child and then attempts to resume
     */
    @Test
    public void resumeIngestFolderTest() throws Exception {
        String label = "testfolder";

        // Construct the deposit model, containing a deposit with one empty folder
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the folder in the deposit model with a title
        PID folderPid = pidMinter.mintContentPid();
        Bag folderBag = model.createBag(folderPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        folderBag.addProperty(CdrDeposit.label, label);

        depBag.add(folderBag);

        // Add children, where the second child is invalid due to missing location
        PID file1Pid = addWorkWithFileObject(folderBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5).get(1);
        List<PID> work2Pids = addWorkWithFileObject(folderBag, null, FILE2_MIMETYPE, null, null);
        PID work2Pid = work2Pids.get(0);
        PID file2Pid = work2Pids.get(1);

        job.closeModel();

        // Execute the ingest job
        try {
            job.run();
            fail("Test should not reach this line. Job should have thrown exception");
        } catch (JobFailedException e) {
            // expected, lets continue
        }

        treeIndexer.indexAll(baseAddress);

        // Check that the folder and first child successfully made it in
        RepositoryObject destObj = repoObjLoader.getRepositoryObject(destinationPid);
        List<ContentObject> destMembersFailed = ((ContentContainerObject) destObj).getMembers();
        assertEquals("Incorrect number of children at destination", 1, destMembersFailed.size());

        ContentObject mFolderFailed = destMembersFailed.get(0);
        List<ContentObject> folderMembersFailed = ((ContentContainerObject) mFolderFailed).getMembers();
        assertEquals("Incorrect number of children in folder", 2, folderMembersFailed.size());

        WorkObject work2Failed = (WorkObject) findContentObjectByPid(folderMembersFailed, work2Pid);
        assertEquals("No files should be present", 0, work2Failed.getMembers().size());

        // Fix the staging location of the second file
        model = job.getWritableModel();
        Resource file2Resc = model.getResource(file2Pid.getRepositoryPath());
        Resource orig2Resc = DepositModelHelpers.getDatastream(file2Resc);
        orig2Resc.addProperty(CdrDeposit.storageUri, Paths.get(depositDir.getAbsolutePath(),
                FILE2_LOC).toUri().toString());
        job.closeModel();

        // Second run of job
        job.run();

        treeIndexer.indexAll(baseAddress);

        List<ContentObject> destMembers = ((ContentContainerObject) destObj).getMembers();
        assertEquals("Incorrect number of children at destination", 1, destMembers.size());

        ContentObject mFolder = destMembers.get(0);
        List<ContentObject> folderMembers = ((ContentContainerObject) mFolder).getMembers();
        assertEquals("Incorrect number of children in folder", 2, folderMembers.size());

        // Order of the children isn't guaranteed, so find by primary obj pid
        WorkObject workA = (WorkObject) folderMembers.get(0);
        WorkObject workB = (WorkObject) folderMembers.get(1);
        workA.shouldRefresh();
        workB.shouldRefresh();

        FileObject file1Obj;
        FileObject file2Obj;
        if (file1Pid.equals(workA.getPrimaryObject().getPid())) {
            file1Obj = workA.getPrimaryObject();
            file2Obj = workB.getPrimaryObject();
        } else {
            file2Obj = workA.getPrimaryObject();
            file1Obj = workB.getPrimaryObject();
        }

        assertBinaryProperties(file1Obj, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5, FILE1_SIZE);
        assertBinaryProperties(file2Obj, FILE2_LOC, FILE2_MIMETYPE, null, null, FILE2_SIZE);

        // Count includes folder, two works each with a file
        assertClickCount(5);
        ingestedObjectsCount(5);
    }

    /**
     * Test a deeply nested hierarchy of folders with binary at the bottom of
     * the tree
     */
    @Test
    public void ingestDeepHierarchy() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the nested folders
        int nestingDepth = 6;
        Bag previousBag = depBag;
        for (int i = 0; i < nestingDepth; i++) {
            PID folderPid = pidMinter.mintContentPid();
            Bag folderBag = model.createBag(folderPid.getRepositoryPath());
            folderBag.addProperty(RDF.type, Cdr.Folder);
            previousBag.add(folderBag);
            previousBag = folderBag;
        }

        addWorkWithFileObject(previousBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5);

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        RepositoryObject destObj = repoObjLoader.getRepositoryObject(destinationPid);
        List<ContentObject> members = ((ContentContainerObject) destObj).getMembers();
        List<RepositoryObject> deposited = new ArrayList<>();

        for (int i = 0; i < nestingDepth; i++) {
            assertEquals("Incorrect number of children", 1, members.size());
            FolderObject folder = (FolderObject) members.get(0);
            deposited.add(folder);
            members = folder.getMembers();
        }

        assertEquals("Incorrect number of children in last tier", 1, members.size());
        WorkObject work = (WorkObject) members.get(0);
        assertStorageLocationPresent(work);
        FileObject primaryFile = work.getPrimaryObject();
        assertBinaryProperties(primaryFile, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5, FILE1_SIZE);
        assertStorageLocationPresent(primaryFile);
        deposited.add(work);
        deposited.add(primaryFile);

        // Nesting depth plus 2 for the final work and its file
        assertClickCount(nestingDepth + 2);
        ingestedObjectsCount(nestingDepth + 2);

        assertLinksToDepositRecord(deposited.toArray(new RepositoryObject[deposited.size()]));
    }

    @Test
    public void addDescriptionTest() throws Exception {
        PID folderPid = pidMinter.mintContentPid();

        Path modsPath = job.getModsPath(folderPid, true);
        Files.createFile(modsPath);

        String label = "testfolder";

        // Construct the deposit model, containing a deposit with one empty folder
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the folder in the deposit model with a title
        Bag folderBag = model.createBag(folderPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        folderBag.addProperty(CdrDeposit.label, label);

        depBag.add(folderBag);

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        ContentContainerObject destObj = (ContentContainerObject) repoObjLoader.getRepositoryObject(destinationPid);
        List<ContentObject> destMembers = destObj.getMembers();
        // Make sure that the folder is present and is actually a folder
        FolderObject folderObj = (FolderObject) findContentObjectByPid(destMembers, folderPid);

        assertNotNull(folderObj.getDescription());

        assertLinksToDepositRecord(folderObj);
    }

    @Test
    public void noDescriptionAddedTest() throws Exception {
        PID folderPid = pidMinter.mintContentPid();

        String label = "testfolder";

        // Construct the deposit model, containing a deposit with one empty folder
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the folder in the deposit model with a title
        Bag folderBag = model.createBag(folderPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        folderBag.addProperty(CdrDeposit.label, label);

        depBag.add(folderBag);

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        ContentContainerObject destObj = (ContentContainerObject) repoObjLoader.getRepositoryObject(destinationPid);
        List<ContentObject> destMembers = destObj.getMembers();
        // Make sure that the folder is present and is actually a folder
        FolderObject folderObj = (FolderObject) findContentObjectByPid(destMembers, folderPid);

        assertNull(folderObj.getDescription());

        assertLinksToDepositRecord(folderObj);
    }

    @Test
    public void addPremisEventsTest() throws Exception {
        PID folderObjPid = pidMinter.mintContentPid();

        File premisEventsFile = job.getPremisFile(folderObjPid);
        premisEventsFile.createNewFile();

        String label = "testfolder";

        // Construct the deposit model, containing a deposit with one empty folder
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the folder in the deposit model with a title
        Bag folderBag = model.createBag(folderObjPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        folderBag.addProperty(CdrDeposit.label, label);

        depBag.add(folderBag);

        FilePremisLogger premisLogger = new FilePremisLogger(folderObjPid, premisEventsFile, pidMinter);
        // build event 1
        premisLogger.buildEvent(Premis.Normalization)
                .addEventDetail("Event 1")
                .addAuthorizingAgent(AgentPids.forPerson("someuser"))
                .write();
        // build event 2
        premisLogger.buildEvent(Premis.VirusCheck)
                .addEventDetail("Event 2")
                .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.clamav))
                .write();

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        FolderObject folder = repoObjLoader.getFolderObject(folderObjPid);

        Model logModel = folder.getPremisLog().getEventsModel();
        assertTrue(logModel.contains(null, RDF.type, Premis.Ingestion));
        assertTrue(logModel.contains(null, RDF.type, Premis.Normalization));
        assertTrue(logModel.contains(null, RDF.type, Premis.VirusCheck));

        assertLinksToDepositRecord(folder);

        premisLogger.close();
    }

    @Test
    public void addPremisEventsResumeTest() throws Exception {
        PID folderObjPid = pidMinter.mintContentPid();

        File premisEventsFile = job.getPremisFile(folderObjPid);
        premisEventsFile.createNewFile();

        String label = "testfolder";

        // Construct the deposit model, containing a deposit with one empty folder
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the folder in the deposit model with a title
        Bag folderBag = model.createBag(folderObjPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        folderBag.addProperty(CdrDeposit.label, label);

        PID workPid = pidMinter.mintContentPid();
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);
        workBag.addProperty(CdrDeposit.label, label);
        PID file1Pid = addWorkWithFileObject(folderBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5_BAD).get(1);


        depBag.add(folderBag);

        FilePremisLogger premisLogger = new FilePremisLogger(folderObjPid, premisEventsFile, pidMinter);
        // build event 1
        premisLogger.buildEvent(Premis.Normalization)
                .addEventDetail("Event 1")
                .addAuthorizingAgent(AgentPids.forPerson("someuser"))
                .write();
        // build event 2
        premisLogger.buildEvent(Premis.VirusCheck)
                .addEventDetail("Event 2")
                .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.clamav))
                .write();

        job.closeModel();

        try {
            job.run();
        } catch (Exception e) {
            FolderObject folder = repoObjLoader.getFolderObject(folderObjPid);

            Model logModel = folder.getPremisLog().getEventsModel();
            assertFalse(logModel.contains(null, RDF.type, Premis.Ingestion));
            assertFalse(logModel.contains(null, RDF.type, Premis.Normalization));
            assertFalse(logModel.contains(null, RDF.type, Premis.VirusCheck));

            Resource fileResc = job.getWritableModel().getResource(file1Pid.getRepositoryPath());
            Resource origResc = DepositModelHelpers.getDatastream(fileResc);
            origResc.removeAll(CdrDeposit.md5sum);
            origResc.addProperty(CdrDeposit.md5sum, FILE1_MD5);
            job.closeModel();
        }

        job.run();
        treeIndexer.indexAll(baseAddress);

        FolderObject folder = repoObjLoader.getFolderObject(folderObjPid);

        Model logModel = folder.getPremisLog().getEventsModel();
        assertTrue(logModel.contains(null, RDF.type, Premis.Ingestion));
        assertTrue(logModel.contains(null, RDF.type, Premis.Normalization));
        assertTrue(logModel.contains(null, RDF.type, Premis.VirusCheck));

        assertLinksToDepositRecord(folder);

        premisLogger.close();
    }

    @Test
    public void onlyIngestionEventAddedTest() throws Exception {
        File premisEventsDir = job.getEventsDirectory();
        premisEventsDir.mkdir();

        PID folderObjPid = pidMinter.mintContentPid();

        File premisEventsFile = job.getPremisFile(folderObjPid);
        premisEventsFile.createNewFile();

        String label = "testfolder";

        // Construct the deposit model, containing a deposit with one empty folder
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // Constructing the folder in the deposit model with a title
        Bag folderBag = model.createBag(folderObjPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        folderBag.addProperty(CdrDeposit.label, label);

        depBag.add(folderBag);

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        FolderObject folder = repoObjLoader.getFolderObject(folderObjPid);

        Model logModel = folder.getPremisLog().getEventsModel();
        assertTrue(logModel.contains(null, RDF.type, Premis.Ingestion));
    }

    @Test
    public void fromMultipleDepositsTest() throws Exception {

        PID folderObj1Pid = pidMinter.mintContentPid();
        PID folderObj2Pid = pidMinter.mintContentPid();
        PID folderObj3Pid = pidMinter.mintContentPid();

        PID deposit2Pid = pidMinter.mintDepositRecordPid();
        PID deposit3Pid = pidMinter.mintDepositRecordPid();

        // Create the deposit records since the references must resolve
        repoObjFactory.createDepositRecord(deposit2Pid, null);
        repoObjFactory.createDepositRecord(deposit3Pid, null);

        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        // First folder from deposit 2
        Bag folder1Bag = model.createBag(folderObj1Pid.getRepositoryPath());
        folder1Bag.addProperty(RDF.type, Cdr.Folder);
        folder1Bag.addProperty(CdrDeposit.originalDeposit, createResource(deposit2Pid.getRepositoryPath()));
        depBag.add(folder1Bag);

        // Second folder from default deposit
        Bag folder2Bag = model.createBag(folderObj2Pid.getRepositoryPath());
        folder2Bag.addProperty(RDF.type, Cdr.Folder);
        depBag.add(folder2Bag);

        // Third folder from deposit 3
        Bag folder3Bag = model.createBag(folderObj3Pid.getRepositoryPath());
        folder3Bag.addProperty(RDF.type, Cdr.Folder);
        folder3Bag.addProperty(CdrDeposit.originalDeposit, createResource(deposit3Pid.getRepositoryPath()));
        depBag.add(folder3Bag);

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        // Verify that the correct original deposit ids are assigned to each folder
        FolderObject folder1 = repoObjLoader.getFolderObject(folderObj1Pid);
        Resource f1DepositResc = folder1.getResource(true).getProperty(Cdr.originalDeposit).getResource();
        assertEquals(deposit2Pid.getRepositoryPath(), f1DepositResc.getURI());

        FolderObject folder2 = repoObjLoader.getFolderObject(folderObj2Pid);
        Resource f2DepositResc = folder2.getResource(true).getProperty(Cdr.originalDeposit).getResource();
        assertEquals(depositPid.getRepositoryPath(), f2DepositResc.getURI());

        FolderObject folder3 = repoObjLoader.getFolderObject(folderObj3Pid);
        Resource f3DepositResc = folder3.getResource(true).getProperty(Cdr.originalDeposit).getResource();
        assertEquals(deposit3Pid.getRepositoryPath(), f3DepositResc.getURI());

        ContentObject destObj = repoObjLoader.getFolderObject(destinationPid);
        Model destLogModel = destObj.getPremisLog().getEventsModel();
        assertTrue(destLogModel.contains(null, Premis.note,
                "added 3 child objects to this container"));
    }

    private final static String CREATED_STRING = "2011-10-04T20:36:44.902Z";
    private final static String LAST_MODIFIED_STRING = "2013-10-06T10:16:44.111Z";
    private final static Date CREATED_DATE = DateTimeUtil.parseUTCToDate(CREATED_STRING);
    private final static Date LAST_MODIFIED_DATE = DateTimeUtil.parseUTCToDate(LAST_MODIFIED_STRING);

    @Test
    public void overrideTimestampsTest() throws Exception {
        Map<String, String> status = new HashMap<>();
        status.put(DepositField.containerId.name(), RepositoryPaths.getContentRootPid().getRepositoryPath());
        status.put(DepositField.permissionGroups.name(), "adminGroup");
        status.put(DepositField.overrideTimestamps.name(), "true");
        depositStatusFactory.save(depositUUID, status);

        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        PID unitPid = pidMinter.mintContentPid();
        Bag unitBag = model.createBag(unitPid.getRepositoryPath());
        unitBag.addProperty(RDF.type, Cdr.AdminUnit);
        unitBag.addLiteral(CdrDeposit.lastModifiedTime, LAST_MODIFIED_STRING);
        unitBag.addLiteral(CdrDeposit.createTime, CREATED_STRING);
        depBag.add(unitBag);

        PID collPid = pidMinter.mintContentPid();
        Bag collBag = model.createBag(collPid.getRepositoryPath());
        collBag.addProperty(RDF.type, Cdr.Collection);
        collBag.addLiteral(CdrDeposit.lastModifiedTime, LAST_MODIFIED_STRING);
        collBag.addLiteral(CdrDeposit.createTime, CREATED_STRING);
        unitBag.add(collBag);

        PID folderPid = pidMinter.mintContentPid();
        Bag folderBag = model.createBag(folderPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);
        folderBag.addLiteral(CdrDeposit.lastModifiedTime, LAST_MODIFIED_STRING);
        folderBag.addLiteral(CdrDeposit.createTime, CREATED_STRING);
        collBag.add(folderBag);

        PID workPid = pidMinter.mintContentPid();
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);
        workBag.addLiteral(CdrDeposit.lastModifiedTime, LAST_MODIFIED_STRING);
        workBag.addLiteral(CdrDeposit.createTime, CREATED_STRING);
        folderBag.add(workBag);

        PID filePid = addFileObject(workBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5);
        Resource fileResc = model.getResource(filePid.getRepositoryPath());
        fileResc.addLiteral(CdrDeposit.lastModifiedTime, LAST_MODIFIED_STRING);
        fileResc.addLiteral(CdrDeposit.createTime, CREATED_STRING);

        job.closeModel();

        job.run();

        treeIndexer.indexAll(baseAddress);

        AdminUnit unitObj = repoObjLoader.getAdminUnit(unitPid);
        assertTimestamps(CREATED_DATE, LAST_MODIFIED_DATE, unitObj);
        CollectionObject collObj = repoObjLoader.getCollectionObject(collPid);
        assertTimestamps(CREATED_DATE, LAST_MODIFIED_DATE, collObj);
        FolderObject folderObj = repoObjLoader.getFolderObject(folderPid);
        assertTimestamps(CREATED_DATE, LAST_MODIFIED_DATE, folderObj);
        WorkObject workObj = repoObjLoader.getWorkObject(workPid);
        assertTimestamps(CREATED_DATE, LAST_MODIFIED_DATE, workObj);
        FileObject fileObj = repoObjLoader.getFileObject(filePid);
        assertTimestamps(CREATED_DATE, LAST_MODIFIED_DATE, fileObj);
    }

    @Test
    public void interruptTest() throws Exception {
        String label = "testwork";

        // Construct the deposit model with work object
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        PID folderObj1Pid = pidMinter.mintContentPid();
        Bag folder1Bag = model.createBag(folderObj1Pid.getRepositoryPath());
        folder1Bag.addProperty(RDF.type, Cdr.Folder);
        depBag.add(folder1Bag);

        // Constructing the work in the deposit model with a label
        PID workPid = pidMinter.mintContentPid();
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);
        workBag.addProperty(CdrDeposit.label, label);

        PID mainPid = addFileObject(workBag, FILE1_LOC, FILE1_MIMETYPE, FILE1_SHA1, FILE1_MD5);

        folder1Bag.add(workBag);

        workBag.asResource().addProperty(Cdr.primaryObject,
                model.getResource(mainPid.getRepositoryPath()));

        job.closeModel();

        AtomicBoolean gotJobInterrupted = new AtomicBoolean(false);
        AtomicReference<Exception> otherException = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                job.run();
            } catch (JobInterruptedException e) {
                gotJobInterrupted.set(true);
            } catch (Exception e) {
                otherException.set(e);
            }
        });
        thread.start();

        // Wait random amount of time and then interrupt thread if still alive
        Thread.sleep(50 + (long) new Random().nextFloat() * 600);
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join();

            if (gotJobInterrupted.get()) {
                // success
            } else {
                if (otherException.get() != null) {
                    throw otherException.get();
                }
            }
        } else {
            log.warn("Job completed before interruption");
        }
    }

    @Test
    public void interruptAndResume2() throws Exception {
        interruptTest();
    }

    private void assertTimestamps(Date expectedCreated, Date expectedModified, ContentObject obj) {
        assertEquals("Date created for " + obj.getPid().getId() + " did not match expected value",
                expectedCreated, obj.getCreatedDate());
        assertEquals("Last modifed for " + obj.getPid().getId() + " did not match expected value",
                expectedModified, obj.getLastModified());
    }

    private void assertBinaryProperties(FileObject fileObj, String loc, String mimetype,
            String sha1, String md5, long size) {
        assertBinaryProperties(fileObj.getOriginalFile(), loc, mimetype, sha1, md5, size);
    }
    private void assertBinaryProperties(BinaryObject binary, String loc, String mimetype,
            String sha1, String md5, long size) {
        assertEquals(loc, binary.getFilename());
        if (sha1 != null) {
            assertEquals("urn:sha1:" + sha1, binary.getSha1Checksum());
        }
        // md5 isn't required, so not all tests will need to ensure it isn't null
        if (md5 != null) {
            assertEquals("urn:md5:" + md5, binary.getMd5Checksum());
        }
        assertEquals(size, binary.getFilesize().longValue());
        assertEquals(mimetype, binary.getMimetype());
    }

    private void assertClickCount(int count) {
        Map<String, String> jobStatus = jobStatusFactory.get(jobUUID);
        assertEquals(count, Integer.parseInt(jobStatus.get(JobField.num.name())));
    }

    private void ingestedObjectsCount(int count) {
        Map<String, String> depositStatus = depositStatusFactory.get(depositUUID);
        assertEquals(count, Integer.parseInt(depositStatus.get(DepositField.ingestedObjects.name())));
    }

    private PID addFileObject(Bag parent, String stagingLocation,
                String mimetype, String sha1, String md5) throws Exception {
        PID filePid = pidMinter.mintContentPid();

        Model model = parent.getModel();
        Resource fileResc = model.createResource(filePid.getRepositoryPath());
        fileResc.addProperty(RDF.type, Cdr.FileObject);

        Resource origResc = DepositModelHelpers.addDatastream(fileResc, ORIGINAL_FILE);
        if (stagingLocation != null) {
            origResc.addProperty(CdrDeposit.storageUri, Paths.get(depositDir.getAbsolutePath(),
                    stagingLocation).toUri().toString());
            fileResc.addLiteral(Cdr.storageLocation, LOC1_ID);
        }
        origResc.addProperty(CdrDeposit.mimetype, mimetype);
        if (sha1 != null) {
            origResc.addProperty(CdrDeposit.sha1sum, sha1);
        }
        if (md5 != null) {
            origResc.addProperty(CdrDeposit.md5sum, md5);
        }

        parent.add(fileResc);

        // Create the accompanying fake premis report file
        Path fitsPath = job.getTechMdPath(filePid, true);
        Files.createFile(fitsPath);
        Resource fitsResc = DepositModelHelpers.addDatastream(fileResc, TECHNICAL_METADATA);
        fitsResc.addProperty(CdrDeposit.storageUri, fitsPath.toUri().toString());
        fitsResc.addLiteral(CdrDeposit.sha1sum, getSha1(fitsPath));

        return filePid;
    }

    private List<PID> addWorkWithFileObject(Bag parent, String stagingLocation,
            String mimetype, String sha1, String md5) throws Exception {
        Model model = parent.getModel();
        // Constructing the work in the deposit model with a label
        PID workPid = pidMinter.mintContentPid();
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);
        workBag.addProperty(CdrDeposit.label, "testwork");
        workBag.addLiteral(Cdr.storageLocation, LOC1_ID);

        PID fileObjPid = addFileObject(workBag, stagingLocation, mimetype, sha1, md5);
        workBag.addProperty(Cdr.primaryObject, createResource(fileObjPid.getRepositoryPath()));
        parent.add(workBag);

        return Arrays.asList(workPid, fileObjPid);

    }

    private void assertLinksToDepositRecord(RepositoryObject... depositedObjs) {
        List<PID> linked = depositRecord.listDepositedObjects();

        for (RepositoryObject deposited: depositedObjs) {
            assertTrue("No original deposit link for " + deposited.getPid(),
                    linked.contains(deposited.getPid()));
        }
    }

    private void assertStorageLocationPresent(ContentObject contentObj) {
        assertTrue("Storage location property was not set",
                contentObj.getResource(true).hasLiteral(Cdr.storageLocation, LOC1_ID));
    }

    private String getSha1(Path filePath) throws Exception {
        byte[] result = DigestUtils.digest(MessageDigest.getInstance("SHA1"), filePath.toFile());
        return Hex.encodeHexString(result);
    }
}
