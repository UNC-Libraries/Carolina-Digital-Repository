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
package edu.unc.lib.deposit.validate;

import static edu.unc.lib.dl.model.DatastreamType.ORIGINAL_FILE;
import static edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers.addDatastream;
import static edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers.getDatastream;
import static edu.unc.lib.dl.test.TestHelpers.setField;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.FileUtils;
import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.jgroups.util.UUID;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

import edu.unc.lib.deposit.fcrepo4.AbstractDepositJobTest;
import edu.unc.lib.deposit.work.JobFailedException;
import edu.unc.lib.deposit.work.JobInterruptedException;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.services.event.PremisLoggerFactoryImpl;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.DigestAlgorithm;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositState;

/**
 * @author bbpennel
 */
public class FixityCheckJobTest extends AbstractDepositJobTest {
    private static final Logger log = getLogger(FixityCheckJobTest.class);

    private final static int FLUSH_RATE = 100;

    private static final String CONTENT1 = "Something to digest";
    private static final String CONTENT1_MD5 = "7afbf05666feeebe7fbbf1c9071584e6";
    private static final String CONTENT1_SHA1 = "23d51c61a578a8cb00c5eec6b29c12b7da15c8de";
    private static final String CONTENT2 = "Boxy eats a checksum";
    private static final String CONTENT2_MD5 = "d4568cb41bf223e418cfd4f23b1a385b";
    private static final String CONTENT2_SHA1 = "f14b0716d448386a4f0671112097c55fc0d91313";

    private RepositoryPIDMinter pidMinter;

    private FixityCheckJob job;

    private File stagingDir;

    private final static ExecutorService executorService = Executors.newFixedThreadPool(2);

    @Before
    public void setup() throws Exception {
        pidMinter = new RepositoryPIDMinter();

        premisLoggerFactory = new PremisLoggerFactoryImpl();
        premisLoggerFactory.setPidMinter(pidMinter);

        initializeJob();

        depositJobId = depositUUID + ":" + job.getClass().getName();

        stagingDir = tmpFolder.newFolder("staged");
    }

    private void initializeJob() {
        job = new FixityCheckJob(jobUUID, depositUUID);
        job.setDepositStatusFactory(depositStatusFactory);
        setField(job, "depositModelManager", depositModelManager);
        setField(job, "premisLoggerFactory", premisLoggerFactory);
        setField(job, "depositsDirectory", depositsDirectory);
        setField(job, "jobStatusFactory", jobStatusFactory);
        job.setExecutorService(executorService);
        job.setFlushRate(FLUSH_RATE);
        job.setMaxQueuedJobs(2);
        job.init();
    }

    @AfterClass
    public static void afterTestClass() {
        executorService.shutdown();
    }

    @Test
    public void depositWithNoDigests() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String stagingPath = stageFile(CONTENT1);
        PID filePid = addFileObject(depBag, stagingPath);
        job.closeModel();

        job.run();

        Model resultModel = job.getReadOnlyModel();
        Resource origResc = getDatastream(resultModel.getResource(filePid.getRepositoryPath()));
        assertTrue(origResc.hasProperty(CdrDeposit.sha1sum, CONTENT1_SHA1));

        assertChecksumEvent(filePid, DigestAlgorithm.SHA1, CONTENT1_SHA1);

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(1));
        verify(jobStatusFactory, times(1)).incrCompletion(eq(jobUUID), eq(1));
    }

    @Test
    public void depositWithValidSha1() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String stagingPath = stageFile(CONTENT1);
        PID filePid = addFileObject(depBag, stagingPath);
        addDigest(model, filePid, DigestAlgorithm.SHA1, CONTENT1_SHA1);
        job.closeModel();

        job.run();

        Model resultModel = job.getReadOnlyModel();
        Resource origResc = getDatastream(resultModel.getResource(filePid.getRepositoryPath()));
        assertTrue(origResc.hasProperty(CdrDeposit.sha1sum, CONTENT1_SHA1));

        assertChecksumEvent(filePid, DigestAlgorithm.SHA1, CONTENT1_SHA1);

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(1));
        verify(jobStatusFactory, times(1)).incrCompletion(eq(jobUUID), eq(1));
    }

    @Test
    public void depositWithInvalidSha1() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String stagingPath = stageFile(CONTENT1);
        PID filePid = addFileObject(depBag, stagingPath);
        addDigest(model, filePid, DigestAlgorithm.SHA1, "ohsha");
        job.closeModel();

        try {
            job.run();
            fail("Expected job to fail");
        } catch (JobFailedException e) {
            assertTrue(e.getMessage().contains("Fixity check failed for " + stagingPath));
            assertTrue(e.getDetails().contains("Checksum mismatch, computed SHA1"));
        }
    }

    @Test
    public void depositWithValidMd5() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String stagingPath = stageFile(CONTENT1);
        PID filePid = addFileObject(depBag, stagingPath);
        addDigest(model, filePid, DigestAlgorithm.MD5, CONTENT1_MD5);
        job.closeModel();

        job.run();

        // Both the provided md5 and the computed sha1 must be present
        Model resultModel = job.getReadOnlyModel();
        Resource origResc = getDatastream(resultModel.getResource(filePid.getRepositoryPath()));
        assertTrue(origResc.hasProperty(CdrDeposit.sha1sum, CONTENT1_SHA1));
        assertTrue(origResc.hasProperty(CdrDeposit.md5sum, CONTENT1_MD5));

        assertChecksumEvent(filePid, DigestAlgorithm.SHA1, CONTENT1_SHA1);
        assertChecksumEvent(filePid, DigestAlgorithm.MD5, CONTENT1_MD5);

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(1));
        verify(jobStatusFactory, times(1)).incrCompletion(eq(jobUUID), eq(1));
    }

    @Test
    public void depositWithInvalidMd5() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String stagingPath = stageFile(CONTENT1);
        PID filePid = addFileObject(depBag, stagingPath);
        addDigest(model, filePid, DigestAlgorithm.MD5, "mdwhat");
        job.closeModel();

        try {
            job.run();
            fail("Expected job to fail");
        } catch (JobFailedException e) {
            assertTrue(e.getMessage().contains("Fixity check failed for " + stagingPath));
            assertTrue(e.getDetails().contains("Checksum mismatch, computed MD5"));
        }
    }

    @Test
    public void depositWithNoFiles() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        Bag folderBag = addFolderObject(depBag);
        PID folderPid = PIDs.get(folderBag.getURI());
        job.closeModel();

        job.run();

        Model resultModel = job.getReadOnlyModel();
        Resource folderResc = resultModel.getResource(folderPid.getRepositoryPath());
        assertFalse(folderResc.hasProperty(CdrDeposit.sha1sum));
    }

    @Test
    public void depositMultipleFilesWithValidDigests() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String stagingPath1 = stageFile(CONTENT1);
        PID filePid1 = addFileObject(depBag, stagingPath1);
        addDigest(model, filePid1, DigestAlgorithm.MD5, CONTENT1_MD5);
        addDigest(model, filePid1, DigestAlgorithm.SHA1, CONTENT1_SHA1);
        String stagingPath2 = stageFile(CONTENT2);
        PID filePid2 = addFileObject(depBag, stagingPath2);
        addDigest(model, filePid2, DigestAlgorithm.MD5, CONTENT2_MD5);
        job.closeModel();

        job.run();

        // Both the provided md5 and the computed sha1 must be present
        Model resultModel = job.getReadOnlyModel();
        Resource origResc1 = getDatastream(resultModel.getResource(filePid1.getRepositoryPath()));
        assertTrue(origResc1.hasProperty(CdrDeposit.sha1sum, CONTENT1_SHA1));
        assertTrue(origResc1.hasProperty(CdrDeposit.md5sum, CONTENT1_MD5));

        assertChecksumEvent(filePid1, DigestAlgorithm.SHA1, CONTENT1_SHA1);
        assertChecksumEvent(filePid1, DigestAlgorithm.MD5, CONTENT1_MD5);

        Resource origResc2 = getDatastream(resultModel.getResource(filePid2.getRepositoryPath()));
        assertTrue(origResc2.hasProperty(CdrDeposit.sha1sum, CONTENT2_SHA1));
        assertTrue(origResc2.hasProperty(CdrDeposit.md5sum, CONTENT2_MD5));

        assertChecksumEvent(filePid2, DigestAlgorithm.SHA1, CONTENT2_SHA1);
        assertChecksumEvent(filePid2, DigestAlgorithm.MD5, CONTENT2_MD5);

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(2));
        verify(jobStatusFactory, times(2)).incrCompletion(eq(jobUUID), eq(1));
    }

    @Test
    public void depositLotsWithNoDigests() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        for (int i = 0; i < 100; i++) {
            String stagingPath1 = stageFile(CONTENT1);
            addFileObject(depBag, stagingPath1);
            String stagingPath2 = stageFile(CONTENT2);
            addFileObject(depBag, stagingPath2);
        }
        job.closeModel();

        long start = System.nanoTime();
        job.run();

        Model resultModel = job.getReadOnlyModel();

        List<Statement> sha1s = resultModel.listStatements(null, CdrDeposit.sha1sum, (String) null).toList();
        assertEquals(200, sha1s.size());

        log.info("Finished in {}", ((System.nanoTime() - start)/1000000));
    }

    @Test
    public void depositResumeAfterFailure() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String stagingPath1 = stageFile(CONTENT1);
        PID filePid1 = addFileObject(depBag, stagingPath1);
        String stagingPath2 = stageFile(CONTENT2);
        PID filePid2 = addFileObject(depBag, stagingPath2);
        addDigest(model, filePid2, DigestAlgorithm.MD5, CONTENT2_MD5);
        job.closeModel();

        Path flappingPath = Paths.get(URI.create(stagingPath2));
        // Delete one of the staged files
        Files.delete(flappingPath);

        try {
            job.run();
            fail("Job expected to fail");
        } catch (JobFailedException e) {
            // expected
        }

        reset(jobStatusFactory);

        // Write the file back into place
        FileUtils.write(flappingPath.toFile(), CONTENT2, UTF_8);

        initializeJob();
        job.run();

        Model resultModel = job.getReadOnlyModel();
        Resource origResc1 = getDatastream(resultModel.getResource(filePid1.getRepositoryPath()));
        assertTrue(origResc1.hasProperty(CdrDeposit.sha1sum, CONTENT1_SHA1));

        assertChecksumEvent(filePid1, DigestAlgorithm.SHA1, CONTENT1_SHA1);

        Resource origResc2 = getDatastream(resultModel.getResource(filePid2.getRepositoryPath()));
        assertTrue(origResc2.hasProperty(CdrDeposit.sha1sum, CONTENT2_SHA1));
        assertTrue(origResc2.hasProperty(CdrDeposit.md5sum, CONTENT2_MD5));

        assertChecksumEvent(filePid2, DigestAlgorithm.SHA1, CONTENT2_SHA1);
        assertChecksumEvent(filePid2, DigestAlgorithm.MD5, CONTENT2_MD5);

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(2));
        verify(jobStatusFactory, times(2)).incrCompletion(eq(jobUUID), eq(1));
    }

    @Test
    public void depositPauseAndResume() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String stagingPath1 = stageFile(CONTENT1);
        PID filePid1 = addFileObject(depBag, stagingPath1);
        String stagingPath2 = stageFile(CONTENT2);
        PID filePid2 = addFileObject(depBag, stagingPath2);
        job.closeModel();

        // Should be running for the first file, then paused
        when(depositStatusFactory.getState(depositUUID))
                .thenReturn(DepositState.running)
                .thenReturn(DepositState.paused);

        try {
            job.run();
            fail("Job expected to fail");
        } catch (JobInterruptedException e) {
            // expected
        }
        // Resume the job
        when(depositStatusFactory.getState(depositUUID))
                .thenReturn(DepositState.running);

        reset(jobStatusFactory);
        initializeJob();
        job.run();

        Model resultModel = job.getReadOnlyModel();
        Resource origResc1 = getDatastream(resultModel.getResource(filePid1.getRepositoryPath()));
        assertTrue(origResc1.hasProperty(CdrDeposit.sha1sum, CONTENT1_SHA1));
        assertChecksumEvent(filePid1, DigestAlgorithm.SHA1, CONTENT1_SHA1);

        Resource origResc2 = getDatastream(resultModel.getResource(filePid2.getRepositoryPath()));
        assertTrue(origResc2.hasProperty(CdrDeposit.sha1sum, CONTENT2_SHA1));
        assertChecksumEvent(filePid2, DigestAlgorithm.SHA1, CONTENT2_SHA1);

        verify(jobStatusFactory).addObjectCompleted(depositJobId, filePid1.getQualifiedId());
        verify(jobStatusFactory).addObjectCompleted(depositJobId, filePid2.getQualifiedId());

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(2));
        verify(jobStatusFactory, times(2)).incrCompletion(eq(jobUUID), eq(1));
    }

    @Test
    public void interruptionTest() throws Exception {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        String stagingPath = stageFile(CONTENT1);
        PID filePid = addFileObject(depBag, stagingPath);
        addDigest(model, filePid, DigestAlgorithm.SHA1, CONTENT1_SHA1);

        String stagingPath2 = stageFile(CONTENT1);
        PID filePid2 = addFileObject(depBag, stagingPath2);
        addDigest(model, filePid2, DigestAlgorithm.SHA1, CONTENT1_SHA1);
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

        Thread.sleep((long) new Random().nextFloat() * 90);
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

    private String stageFile(String content) throws IOException {
        String fileId = UUID.randomUUID().toString();
        File stagedFile = new File(stagingDir, fileId);
        FileUtils.write(stagedFile, content, UTF_8);
        return stagedFile.toPath().toUri().toString();
    }

    private PID addFileObject(Bag parent, String stagingLocation) {
        PID filePid = makePid();

        Model model = parent.getModel();
        Resource fileResc = model.createResource(filePid.getRepositoryPath());
        fileResc.addProperty(RDF.type, Cdr.FileObject);

        Resource origResc = addDatastream(fileResc);
        origResc.addLiteral(CdrDeposit.stagingLocation, stagingLocation);

        parent.add(fileResc);

        return filePid;
    }

    private Bag addFolderObject(Bag parent) {
        PID folderPid = makePid();

        Bag folderBag = parent.getModel().createBag(folderPid.getRepositoryPath());
        folderBag.addProperty(RDF.type, Cdr.Folder);

        parent.add(folderBag);

        return folderBag;
    }

    private void addDigest(Model model, PID filePid, DigestAlgorithm alg, String digest) {
        Resource origResc = addDatastream(model.getResource(filePid.getRepositoryPath()), ORIGINAL_FILE);
        origResc.addLiteral(alg.getDepositProperty(), digest);
    }

    private void assertChecksumEvent(PID pid, DigestAlgorithm alg, String digest) {
        Model eventsModel = job.getPremisLogger(pid).getEventsModel();
        List<Resource> events = eventsModel.listResourcesWithProperty(
                RDF.type, Premis.MessageDigestCalculation).toList();
        // There can be more than one event in the case of interruption
        assertTrue("Expected at least one premis event", 1 <= events.size());
        Resource eventResc = events.get(0);
        eventResc.hasProperty(Premis.note, alg.getName().toUpperCase() + " checksum calculated: " + digest);
    }
}
