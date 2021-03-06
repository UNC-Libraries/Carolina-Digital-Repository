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

import static edu.unc.lib.boxc.common.test.TestHelpers.setField;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;

import edu.unc.lib.boxc.model.api.ids.PID;
import edu.unc.lib.boxc.model.api.ids.RepositoryPathConstants;
import edu.unc.lib.boxc.model.api.rdf.Cdr;
import edu.unc.lib.boxc.model.api.rdf.CdrDeposit;
import edu.unc.lib.deposit.fcrepo4.AbstractDepositJobTest;
import edu.unc.lib.deposit.work.JobFailedException;
import edu.unc.lib.deposit.work.JobInterruptedException;
import edu.unc.lib.dl.persist.api.ingest.IngestSource;
import edu.unc.lib.dl.persist.api.ingest.IngestSourceManager;
import edu.unc.lib.dl.persist.api.ingest.UnknownIngestSourceException;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositState;

/**
 *
 * @author bbpennel
 *
 */
public class ValidateFileAvailabilityJobTest extends AbstractDepositJobTest {
    @Rule
    public ExpectedException exception = ExpectedException.none();

    private PID depositPid;

    private ValidateFileAvailabilityJob job;

    @Mock
    private IngestSourceManager sourceManager;
    @Mock
    private IngestSource ingestSource;

    @Before
    public void init() throws Exception {

        job = new ValidateFileAvailabilityJob();
        job.setJobUUID(jobUUID);
        job.setDepositUUID(depositUUID);
        job.setDepositDirectory(depositDir);
        setField(job, "pidMinter", pidMinter);
        job.setIngestSourceManager(sourceManager);
        setField(job, "depositModelManager", depositModelManager);
        setField(job, "depositsDirectory", depositsDirectory);
        setField(job, "depositStatusFactory", depositStatusFactory);
        setField(job, "jobStatusFactory", jobStatusFactory);
        job.init();

        when(sourceManager.getIngestSourceForUri(any(URI.class))).thenReturn(ingestSource);
        when(ingestSource.exists(any(URI.class))).thenReturn(true);

        depositPid = job.getDepositPID();

        File examplesFile = new File("src/test/resources/examples");
        FileUtils.copyDirectory(examplesFile, depositDir);
    }

    @Test
    public void multipleRelativePresentTest() {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);

        depBag.add(workBag);
        addFileObject(workBag, "pdf.pdf");
        addFileObject(workBag, "text.txt");

        job.closeModel();

        job.run();

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(2));
        verify(jobStatusFactory, times(2)).incrCompletion(eq(jobUUID), eq(1));
    }

    @Test
    public void absolutePresentTest() {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        PID workPid = makePid(RepositoryPathConstants.CONTENT_BASE);
        Bag workBag = model.createBag(workPid.getRepositoryPath());
        workBag.addProperty(RDF.type, Cdr.Work);

        depBag.add(workBag);
        addFileObject(workBag, Paths.get(depositDir.getAbsolutePath(), "pdf.pdf")
                .toAbsolutePath().toString());
        addFileObject(workBag, Paths.get(depositDir.getAbsolutePath(), "text.txt")
                .toAbsolutePath().toString());

        job.closeModel();

        job.run();

        verify(jobStatusFactory).setTotalCompletion(eq(jobUUID), eq(2));
        verify(jobStatusFactory, times(2)).incrCompletion(eq(jobUUID), eq(1));
    }

    @Test(expected = JobFailedException.class)
    public void invalidSourceTest() {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        when(sourceManager.getIngestSourceForUri(any(URI.class)))
                .thenThrow(new UnknownIngestSourceException("Not source"));

        addFileObject(depBag, "missing.pdf");

        job.closeModel();

        job.run();
    }

    @Test(expected = JobFailedException.class)
    public void missingFileTest() {
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        when(ingestSource.exists(any(URI.class))).thenReturn(false);

        addFileObject(depBag, "missing.pdf");

        job.closeModel();

        job.run();
    }

    @Test(expected = JobInterruptedException.class)
    public void interruptedTest() {
        when(depositStatusFactory.getState(anyString()))
                .thenReturn(DepositState.paused);

        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());

        addFileObject(depBag, "pdf.pdf");

        job.closeModel();

        job.run();

        verify(jobStatusFactory, never()).incrCompletion(eq(jobUUID), eq(1));
    }

    @Test
    public void badStagingLocation() {
        exception.expect(JobFailedException.class);
        exception.expectMessage("Deposit references invalid files");
        when(sourceManager.getIngestSourceForUri(any(URI.class)))
                .thenThrow(new UnknownIngestSourceException("nope"));
        Model model = job.getWritableModel();
        Bag depBag = model.createBag(depositPid.getRepositoryPath());
        addFileObject(depBag, "some/random/location");
        job.closeModel();
        job.run();
    }

    private PID addFileObject(Bag parent, String stagingLocation) {
        PID filePid = makePid(RepositoryPathConstants.CONTENT_BASE);

        Resource fileResc = parent.getModel().createResource(filePid.getRepositoryPath());
        fileResc.addProperty(RDF.type, Cdr.FileObject);
        Resource origResc = DepositModelHelpers.addDatastream(fileResc);
        origResc.addLiteral(CdrDeposit.stagingLocation, stagingLocation);

        parent.add(fileResc);

        return filePid;
    }
}
