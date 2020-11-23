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

import static edu.unc.lib.dl.test.TestHelpers.setField;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.apache.commons.io.FileUtils;
import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.util.concurrent.MoreExecutors;
import com.philvarner.clamavj.ClamScan;
import com.philvarner.clamavj.ScanResult;
import com.philvarner.clamavj.ScanResult.Status;

import edu.unc.lib.deposit.fcrepo4.AbstractDepositJobTest;
import edu.unc.lib.deposit.work.JobFailedException;
import edu.unc.lib.deposit.work.JobInterruptedException;
import edu.unc.lib.dl.fcrepo4.RepositoryPathConstants;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositState;
import edu.unc.lib.dl.util.URIUtil;

/**
 *
 * @author bbpennel
 *
 */
public class VirusScanJobTest extends AbstractDepositJobTest {

    private PID depositPid;

    private VirusScanJob job;

    @Mock
    private ClamScan clamScan;

    @Mock
    private ScanResult scanResult;

    private final static ExecutorService executorService = MoreExecutors.newDirectExecutorService();

    @Before
    public void init() throws Exception {

        job = new VirusScanJob();
        job.setJobUUID(jobUUID);
        job.setDepositUUID(depositUUID);
        job.setDepositDirectory(depositDir);
        setField(job, "pidMinter", pidMinter);
        job.setClamScan(clamScan);
        job.setPremisLoggerFactory(premisLoggerFactory);
        setField(job, "depositModelManager", depositModelManager);
        setField(job, "depositsDirectory", depositsDirectory);
        setField(job, "depositStatusFactory", depositStatusFactory);
        setField(job, "jobStatusFactory", jobStatusFactory);
        depositJobId = depositUUID + ":" + this.getClass().getName();
        setField(job, "depositJobId", depositJobId);
        job.setExecutorService(executorService);
        job.init();

        when(depositStatusFactory.getState(anyString()))
                .thenReturn(DepositState.running);

        depositPid = job.getDepositPID();

        when(pidMinter.mintPremisEventPid(any(PID.class))).thenAnswer(new Answer<PID>() {
            @Override
            public PID answer(InvocationOnMock invocation) throws Throwable {
                PID pid = mock(PID.class);
                String path = URIUtil.join(FEDORA_BASE, "event", UUID.randomUUID().toString());
                when(pid.getRepositoryPath()).thenReturn(path);
                return pid;
            }
        });

        when(clamScan.scan(any(File.class))).thenReturn(scanResult);

        File examplesFile = new File("src/test/resources/examples");
        FileUtils.copyDirectory(examplesFile, depositDir);

        when(premisEventBuilder.addOutcome(anyBoolean())).thenReturn(premisEventBuilder);
    }

    @AfterClass
    public static void afterTestClass() {
        executorService.shutdown();
    }

    @Test
    public void passScanTest() throws Exception {
        when(scanResult.getStatus()).thenReturn(Status.PASSED);

        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());
        File manifestFile = new File(depositDir, "manifest.txt");
        Resource manifestResc = DepositModelHelpers.addManifest(depBag, "manifest.txt");
        manifestResc.addLiteral(CdrDeposit.stagingLocation, manifestFile.toPath().toUri().toString());

        File pdfFile = new File(depositDir, "pdf.pdf");
        File textFile = new File(depositDir, "text.txt");
        PID file1Pid = addFileObject(depBag, pdfFile);
        PID file2Pid = addFileObject(depBag, textFile);

        job.closeModel();

        job.run();

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(3));
        verify(jobStatusFactory, times(3)).incrCompletion(eq(jobUUID), eq(1));

        verify(premisLogger, times(4)).buildEvent(eq(Premis.VirusCheck));
        verify(premisLoggerFactory).createPremisLogger(eq(file1Pid), any(File.class));
        verify(premisLoggerFactory).createPremisLogger(eq(file2Pid), any(File.class));
        verify(premisLoggerFactory, times(2)).createPremisLogger(eq(depositPid), any(File.class));
        verify(premisEventBuilder, times(3)).addOutcome(true);
    }

    @Test
    public void alreadyRunScanTest() throws Exception {
        when(scanResult.getStatus()).thenReturn(Status.PASSED);

        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        File pdfFile = new File(depositDir, "pdf.pdf");
        File textFile = new File(depositDir, "text.txt");
        PID file1Pid = addFileObject(depBag, pdfFile);
        PID orig1Pid = DatastreamPids.getOriginalFilePid(file1Pid);
        PID file2Pid = addFileObject(depBag, textFile);

        job.closeModel();

        when(jobStatusFactory.objectIsCompleted(depositJobId, orig1Pid.getQualifiedId())).thenReturn(true);
        job.run();

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(2));
        verify(jobStatusFactory, times(2)).incrCompletion(eq(jobUUID), eq(1));

        verify(premisLogger, times(2)).buildEvent(eq(Premis.VirusCheck));
        verify(premisLoggerFactory).createPremisLogger(eq(file2Pid), any(File.class));
        verify(premisLoggerFactory).createPremisLogger(eq(depositPid), any(File.class));
        verify(premisEventBuilder, times(1)).addOutcome(true);
    }

    @Test
    public void failOneScanTest() throws Exception {
        // Fail the text scan, but not the pdf
        when(scanResult.getStatus()).thenReturn(Status.PASSED);
        ScanResult result2 = mock(ScanResult.class);
        when(result2.getStatus()).thenReturn(Status.FAILED);
        File pdfFile = new File(depositDir, "pdf.pdf");
        File textFile = new File(depositDir, "text.txt");
        doReturn(scanResult).when(clamScan).scan(argThat(new FileArgumentMatcher(pdfFile)));
        doReturn(result2).when(clamScan).scan(argThat(new FileArgumentMatcher(textFile)));

        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        PID file1Pid = addFileObject(depBag, pdfFile);
        addFileObject(depBag, textFile);

        job.closeModel();

        try {
            job.run();
            fail();
        } catch(JobFailedException e) {
            // Both files should have been scanned
            verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(2));

            // Only marks the successful file as completed
            verify(jobStatusFactory, times(1)).incrCompletion(eq(jobUUID), eq(1));

            // Only the file that passed should have a premis log
            verify(premisLogger).buildEvent(eq(Premis.VirusCheck));
            verify(premisLoggerFactory).createPremisLogger(any(PID.class), any(File.class));
            verify(premisLoggerFactory).createPremisLogger(eq(file1Pid), any(File.class));
            verify(premisEventBuilder).addOutcome(true);
        }
    }

    @Test
    public void failAndRescanTest() throws Exception {
        when(scanResult.getStatus()).thenReturn(Status.FAILED)
                .thenReturn(Status.PASSED);
        File textFile = new File(depositDir, "text.txt");
        doReturn(scanResult).when(clamScan).scan(argThat(new FileArgumentMatcher(textFile)));

        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        PID filePid = addFileObject(depBag, textFile);

        job.closeModel();

        try {
            job.run();
            fail();
        } catch(JobFailedException e) {
            verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(1));
            verify(jobStatusFactory, never()).incrCompletion(eq(jobUUID), eq(0));
        }

        job.run();

        verify(jobStatusFactory, times(2)).setTotalCompletion(eq(jobUUID), eq(1));
        verify(jobStatusFactory).incrCompletion(eq(jobUUID), eq(1));

        verify(premisLogger, times(2)).buildEvent(eq(Premis.VirusCheck));
        verify(premisLoggerFactory).createPremisLogger(eq(filePid), any(File.class));
        verify(premisEventBuilder).addOutcome(true);
    }

    @Test
    public void errorScanTest() throws Exception {
        // Fail the text scan, but not the pdf
        when(scanResult.getStatus()).thenReturn(Status.ERROR);
        Exception mockE = mock(Exception.class);
        when(mockE.getLocalizedMessage()).thenReturn("Something isn't work");
        when(scanResult.getException()).thenReturn(mockE);

        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        File pdfFile = new File(depositDir, "pdf.pdf");
        addFileObject(depBag, pdfFile);

        job.closeModel();

        try {
            job.run();
            fail();
        } catch(Error e) {
            // No files should have completed scanning
            verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(1));
            verify(jobStatusFactory, never()).incrCompletion(anyString(), anyInt());

            // No premis logs should have been created
            verify(premisLoggerFactory, never()).createPremisLogger(any(PID.class), any(File.class));
        }
    }

    @Test
    public void pauseScanTest() throws Exception {
        when(scanResult.getStatus()).thenReturn(Status.PASSED);

        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        File pdfFile = new File(depositDir, "pdf.pdf");
        File textFile = new File(depositDir, "text.txt");
        PID file1Pid = addFileObject(depBag, pdfFile);
        PID file2Pid = addFileObject(depBag, textFile);

        // Should be running for the first file, then paused
        when(depositStatusFactory.getState(depositUUID))
                .thenReturn(DepositState.running)
                .thenReturn(DepositState.running)
                .thenReturn(DepositState.paused);

        job.closeModel();

        try {
            job.run();
            fail("Job must be interrupted due to pausing");
        } catch (JobInterruptedException e) {
            // expected
        }

        // Resume the job
        when(depositStatusFactory.getState(depositUUID))
                .thenReturn(DepositState.running);

        job.run();

        verify(jobStatusFactory, times(2)).setTotalCompletion(eq(jobUUID), eq(2));
        verify(jobStatusFactory, times(3)).incrCompletion(eq(jobUUID), eq(1));

        verify(premisLogger, times(3)).buildEvent(eq(Premis.VirusCheck));
        verify(premisLoggerFactory).createPremisLogger(eq(file1Pid), any(File.class));
        verify(premisLoggerFactory).createPremisLogger(eq(file2Pid), any(File.class));
        verify(premisLoggerFactory).createPremisLogger(eq(depositPid), any(File.class));
        verify(premisEventBuilder, times(2)).addOutcome(true);
    }

    private PID addFileObject(Bag parent, File stagedFile) {
        PID filePid = makePid(RepositoryPathConstants.CONTENT_BASE);

        Resource fileResc = parent.getModel().createResource(filePid.getRepositoryPath());
        fileResc.addProperty(RDF.type, Cdr.FileObject);
        Resource origResc = DepositModelHelpers.addDatastream(fileResc);
        origResc.addLiteral(CdrDeposit.stagingLocation, stagedFile.toPath().toUri().toString());

        parent.add(fileResc);

        return filePid;
    }

    private class FileArgumentMatcher extends ArgumentMatcher<File> {
        private File file;

        public FileArgumentMatcher(File file) {
            this.file = file;
        }

        @Override
        public boolean matches(Object argument) {
            File compareFile = (File) argument;
            return file.getAbsolutePath().equals(compareFile.getAbsolutePath());
        }

    }
}
