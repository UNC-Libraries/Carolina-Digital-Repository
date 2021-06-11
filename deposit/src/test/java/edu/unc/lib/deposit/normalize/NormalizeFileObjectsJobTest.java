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
package edu.unc.lib.deposit.normalize;

import static edu.unc.lib.dl.fcrepo4.RepositoryPathConstants.CONTENT_BASE;
import static edu.unc.lib.dl.test.TestHelpers.setField;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import edu.unc.lib.deposit.fcrepo4.AbstractDepositJobTest;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.persist.services.event.PremisEventBuilderImpl;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrAcl;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.test.SelfReturningAnswer;

/**
 *
 * @author bbpennel
 *
 */
public class NormalizeFileObjectsJobTest extends AbstractDepositJobTest {

    private static final String FILENAME = "file.txt";

    private NormalizeFileObjectsJob job;

    private Model model;

    private Bag depBag;

    @Mock
    private PremisLogger mockPremisLogger;
    @Mock
    private PremisLoggerFactory mockPremisLoggerFactory;
    private PremisEventBuilderImpl mockPremisEventBuilder;

    @Mock
    private RepositoryPIDMinter pidMinter;

    @Before
    public void init() {
        job = new NormalizeFileObjectsJob();
        job.setDepositUUID(depositUUID);
        job.setDepositDirectory(depositDir);
        job.setDepositStatusFactory(depositStatusFactory);
        setField(job, "depositModelManager", depositModelManager);
        setField(job, "premisLoggerFactory", mockPremisLoggerFactory);
        setField(job, "depositsDirectory", depositsDirectory);
        setField(job, "pidMinter", pidMinter);
        job.init();

        mockPremisEventBuilder = mock(PremisEventBuilderImpl.class, new SelfReturningAnswer());
        when(mockPremisLoggerFactory.createPremisLogger(any(PID.class), any(File.class)))
                .thenReturn(mockPremisLogger);
        when(mockPremisLogger.buildEvent(any(Resource.class))).thenReturn(mockPremisEventBuilder);

        model = job.getWritableModel();
        depBag = model.createBag(depositPid.getRepositoryPath());

        when(pidMinter.mintContentPid()).thenAnswer(new Answer<PID>() {
            @Override
            public PID answer(InvocationOnMock invocation) throws Throwable {
                return makePid(CONTENT_BASE);
            }
        });
    }

    @Test
    public void fileObjectInWorkTest() throws Exception {
        Bag workBag = addContainer(Cdr.Work);

        Resource childResc = addFileObject(workBag);

        job.closeModel();

        job.run();

        Model model = job.getReadOnlyModel();

        // Check that no additional objects were added
        Map<String, Resource> map = getResourceMap(model);
        assertEquals("Number of objects in model must not change", 3, map.size());

        assertNotNull(map.get(workBag.getURI()));
        assertNotNull(map.get(childResc.getURI()));
    }

    @Test
    public void fileObjectInFolderTest() throws Exception {
        Bag folderBag = addContainer(Cdr.Folder);
        String folderUri = folderBag.getURI();

        Resource childResc = addFileObject(folderBag);
        childResc.addLiteral(CdrAcl.embargoUntil,
                model.createTypedLiteral(Calendar.getInstance()));

        job.closeModel();

        job.run();

        Model model = job.getReadOnlyModel();

        // Check that no additional objects were added
        Map<String, Resource> map = getResourceMap(model);
        assertEquals("Must be one more resource than originally added", 4, map.size());

        // Verify that work was created
        Resource workResc = model.listResourcesWithProperty(RDF.type, Cdr.Work).next();
        assertNotNull(workResc);

        // Verify that the label was set
        assertEquals(FILENAME, workResc.getProperty(CdrDeposit.label).getString());

        folderBag = model.getBag(folderUri);
        // Verify that the folder contains the work, not the file
        assertEquals("Folder must contain work",
                folderBag.iterator().next().asResource(),
                workResc);

        childResc = model.getResource(childResc.getURI());
        // Verify that ACL properties are transfered to work
        assertFalse(childResc.hasProperty(CdrAcl.embargoUntil));
        assertTrue(workResc.hasProperty(CdrAcl.embargoUntil));

        // Check that the work contains the fileObject
        assertEquals("Folder must contain work",
                model.getBag(workResc).iterator().next().asResource(),
                childResc);

        // Work must have fileObject as primary
        assertTrue(workResc.hasProperty(Cdr.primaryObject, childResc));

        // Label still present on file object
        assertEquals(FILENAME, childResc.getProperty(CdrDeposit.label).getString());

        // Other properties still present on file object
        Resource origResc = DepositModelHelpers.getDatastream(childResc);
        assertTrue(origResc.hasProperty(CdrDeposit.stagingLocation));

        // Check that premis event was added
        verify(mockPremisLogger).buildEvent(eq(Premis.Creation));
    }

    @Test
    public void fileObjectWithDescriptionTest() throws Exception {
        Bag folderBag = addContainer(Cdr.Folder);

        Resource childResc = addFileObject(folderBag);
        PID childPid = PIDs.get(childResc.getURI());

        File childModsFile = job.getModsPath(childPid, true).toFile();
        childModsFile.createNewFile();

        job.closeModel();

        job.run();

        Model model = job.getReadOnlyModel();

        Resource workResc = model.listResourcesWithProperty(RDF.type, Cdr.Work).next();

        // Verify that the MODS file was renamed to match work uuid
        PID workPid = PIDs.get(workResc.getURI());
        File workModsFile = job.getModsPath(workPid).toFile();

        assertTrue(workModsFile.exists());
        assertFalse(childModsFile.exists());
    }

    @Test
    public void noFileObjectTest() throws Exception {
        Bag workBag = addContainer(Cdr.Work);

        job.closeModel();

        job.run();

        Model model = job.getReadOnlyModel();

        // Check that no additional objects were added
        Map<String, Resource> map = getResourceMap(model);
        assertEquals("Number of objects in model must not change", 2, map.size());

        // Check that no objects changed
        Resource workResc = map.get(workBag.getURI());
        assertNotNull(workResc);
        assertTrue(workResc.hasProperty(RDF.type, Cdr.Work));
    }

    private Bag addContainer(Resource type) {
        PID objPid = makePid(CONTENT_BASE);
        Bag objBag = model.createBag(objPid.getRepositoryPath());
        objBag.addProperty(RDF.type, type);

        depBag.add(objBag);

        return objBag;
    }

    private Resource addFileObject(Bag parent) {
        PID childPid = makePid(CONTENT_BASE);
        Resource childResc = model.getResource(childPid.getRepositoryPath());
        childResc.addProperty(RDF.type, Cdr.FileObject);
        childResc.addProperty(CdrDeposit.label, FILENAME);
        URI stagingUri = Paths.get(depositDir.getAbsolutePath(), "path", FILENAME).toUri();

        Resource origResc = DepositModelHelpers.addDatastream(childResc);
        origResc.addProperty(CdrDeposit.stagingLocation, stagingUri.toString());

        parent.add(childResc);

        return childResc;
    }

    private Map<String, Resource> getResourceMap(Model model) {
        Map<String, Resource> map = new HashMap<>();

        ResIterator rescIt = model.listResourcesWithProperty(RDF.type);
        while (rescIt.hasNext()) {
            Resource resc = rescIt.next();
            map.put(resc.getURI(), resc);
        }

        return map;
    }
}
