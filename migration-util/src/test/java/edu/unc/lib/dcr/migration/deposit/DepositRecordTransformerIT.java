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
package edu.unc.lib.dcr.migration.deposit;

import static edu.unc.lib.dcr.migration.MigrationConstants.toBxc3Uri;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DC;
import org.apache.jena.vocabulary.RDF;
import org.jdom2.Document;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.CDRProperty;
import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.ContentModel;
import edu.unc.lib.dcr.migration.fcrepo3.DatastreamVersion;
import edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentBuilder;
import edu.unc.lib.dcr.migration.paths.PathIndex;
import edu.unc.lib.dcr.migration.paths.PathIndexingService;
import edu.unc.lib.dl.exceptions.RepositoryException;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.DepositRecord;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.NotFoundException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.storage.StorageLocationManager;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.persist.services.storage.StorageLocationTestHelper;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.test.TestHelper;

/**
 * @author bbpennel
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml")
})
public class DepositRecordTransformerIT extends AbstractDepositRecordTransformationIT {
    private static Path ingestSourcePath;

    static {
        try {
            // Injecting path of the ingest source so it can be picked up by spring
            ingestSourcePath = Files.createTempDirectory("ingestSource");
            System.setProperty("dcr.it.tdr.ingestSource", ingestSourcePath.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Autowired
    private RepositoryPIDMinter pidMinter;
    @Autowired
    private RepositoryObjectFactory repoObjFactory;
    @Autowired
    private RepositoryObjectLoader repoObjLoader;
    @Autowired
    private PremisLoggerFactory premisLoggerFactory;
    @Autowired
    private StorageLocationManager locManager;
    @Autowired
    private BinaryTransferService transferService;

    private BinaryTransferSession transferSession;
    @Autowired
    private PathIndex pathIndex;
    @Autowired
    PathIndexingService pathIndexingService;

    private DepositRecordTransformer transformer;

    private PID bxc3Pid;
    private PID bxc5Pid;

    @Before
    public void init() throws Exception {
        TestHelper.setContentBase("http://localhost:48085/rest");

        Files.createDirectories(ingestSourcePath);
        datastreamsPath = ingestSourcePath.resolve("datastreams");
        objectsPath = ingestSourcePath.resolve("objects");
        Files.createDirectories(datastreamsPath);
        Files.createDirectories(objectsPath);

        StorageLocation loc = locManager.getStorageLocationById(StorageLocationTestHelper.LOC1_ID);
        transferSession = transferService.getSession(loc);

        bxc3Pid = pidMinter.mintDepositRecordPid();
        bxc5Pid = pidMinter.mintDepositRecordPid();
        transformer = new DepositRecordTransformer(bxc3Pid, bxc5Pid, transferSession);
        transformer.setPathIndex(pathIndex);
        transformer.setPremisLoggerFactory(premisLoggerFactory);
        transformer.setRepositoryObjectFactory(repoObjFactory);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteDirectory(ingestSourcePath.toFile());
        transferSession.close();
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        FileUtils.deleteDirectory(ingestSourcePath.toFile());
    }

    @Test(expected = RepositoryException.class)
    public void transform_NoFoxml() throws Exception {
        transformer.compute();
    }

    @Test(expected = RepositoryException.class)
    public void transform_NonDepositRecord() throws Exception {
        Model bxc3Model = createModelWithTypes(bxc3Pid, ContentModel.CONTAINER);

        Document foxml = new FoxmlDocumentBuilder(bxc3Pid, "work")
                .relsExtModel(bxc3Model)
                .build();
        serializeFoxml(bxc3Pid, foxml);

        updatePathIndex();

        transformer.compute();
    }

    @Test
    public void transform_DepositRecord() throws Exception {
        Model bxc3Model = createModelWithTypes(bxc3Pid, ContentModel.DEPOSIT_RECORD);

        Document foxml = new FoxmlDocumentBuilder(bxc3Pid, "Migrated Deposit Record")
                .relsExtModel(bxc3Model)
                .build();
        serializeFoxml(bxc3Pid, foxml);

        addPremisLog(bxc3Pid);

        updatePathIndex();

        transformer.compute();

        DepositRecord depRec = repoObjLoader.getDepositRecord(bxc5Pid);
        Resource recResc = depRec.getResource();

        assertTrue("Did not have deposit record type", recResc.hasProperty(RDF.type, Cdr.DepositRecord));
        assertTrue(recResc.hasProperty(DC.title, "Migrated Deposit Record"));
        assertEquals(DEFAULT_CREATED_DATE, depRec.getCreatedDate());
        assertEquals(DEFAULT_MODIFIED_DATE, depRec.getLastModified());

        assertPremisTransformed(depRec);
    }

    @Test
    public void transform_DepositRecord_WithDepositProperties() throws Exception {
        Model bxc3Model = createModelWithTypes(bxc3Pid, ContentModel.DEPOSIT_RECORD);
        Resource bxc3Resc = bxc3Model.getResource(toBxc3Uri(bxc3Pid));
        bxc3Resc.addLiteral(CDRProperty.depositedOnBehalfOf.getProperty(), "some depositor");
        bxc3Resc.addLiteral(CDRProperty.depositMethod.getProperty(), "dep method");
        bxc3Resc.addLiteral(CDRProperty.depositPackageType.getProperty(), "mets package");
        bxc3Resc.addLiteral(CDRProperty.depositPackageSubType.getProperty(), "package subtype");

        Document foxml = new FoxmlDocumentBuilder(bxc3Pid, "Migrated Deposit Record")
                .relsExtModel(bxc3Model)
                .build();
        serializeFoxml(bxc3Pid, foxml);

        addPremisLog(bxc3Pid);

        updatePathIndex();

        transformer.compute();

        DepositRecord depRec = repoObjLoader.getDepositRecord(bxc5Pid);
        Resource recResc = depRec.getResource();

        assertTrue("Did not have deposit record type", recResc.hasProperty(RDF.type, Cdr.DepositRecord));
        assertTrue(recResc.hasProperty(DC.title, "Migrated Deposit Record"));
        assertEquals(DEFAULT_CREATED_DATE, depRec.getCreatedDate());
        assertEquals(DEFAULT_MODIFIED_DATE, depRec.getLastModified());

        assertPremisTransformed(depRec);

        assertTrue(recResc.hasProperty(Cdr.depositedOnBehalfOf, "some depositor"));
        assertTrue(recResc.hasProperty(Cdr.depositMethod, "dep method"));
        assertTrue(recResc.hasProperty(Cdr.depositPackageType, "mets package"));
        assertTrue(recResc.hasProperty(Cdr.depositPackageProfile, "package subtype"));
    }

    @Test(expected = NotFoundException.class)
    public void transform_DepositRecord_ProQuest() throws Exception {
        createDeposit("http://proquest.com");
        transformer.compute();

        // Make sure there's no record created
        repoObjLoader.getDepositRecord(bxc5Pid);
    }

    @Test(expected = NotFoundException.class)
    public void transform_DepositRecord_Biomed() throws Exception {
        createDeposit("http://purl.org/net/sword/terms/METSDSpaceSIP");
        transformer.compute();

        // Make sure there's no record created
        repoObjLoader.getDepositRecord(bxc5Pid);
    }

    @Test(expected = NotFoundException.class)
    public void transform_DepositRecord_Biomed_Alternate_URI() throws Exception {
        createDeposit("http://purl.org/net/sword-types/METSDSpaceSIP");
        transformer.compute();

        // Make sure there's no record created
        repoObjLoader.getDepositRecord(bxc5Pid);
    }

    @Test
    public void transform_DepositRecord_withManifests() throws Exception {
        Model bxc3Model = createModelWithTypes(bxc3Pid, ContentModel.DEPOSIT_RECORD);

        String manifest0Name = "DATA_MANIFEST0";
        String manifest0Content = "content for m0";
        writeDatastreamFile(bxc3Pid, manifest0Name, manifest0Content);
        DatastreamVersion manifest0 = new DatastreamVersion(null,
                manifest0Name, "0",
                FoxmlDocumentBuilder.DEFAULT_CREATED_DATE,
                Integer.toString(manifest0Content.length()),
                "text/xml",
                null);

        String manifest1Name = "DATA_MANIFEST1";
        String manifest1Content = "additional content";
        writeDatastreamFile(bxc3Pid, manifest1Name, manifest1Content);
        DatastreamVersion manifest1 = new DatastreamVersion(null,
                manifest1Name, "0",
                FoxmlDocumentBuilder.DEFAULT_LAST_MODIFIED,
                Integer.toString(manifest1Content.length()),
                "text/plain",
                null);

        Document foxml = new FoxmlDocumentBuilder(bxc3Pid, "Deposit Record with Manifests")
                .relsExtModel(bxc3Model)
                .withDatastreamVersion(manifest0)
                .withDatastreamVersion(manifest1)
                .build();
        serializeFoxml(bxc3Pid, foxml);

        addPremisLog(bxc3Pid);

        updatePathIndex();

        transformer.compute();

        DepositRecord depRec = repoObjLoader.getDepositRecord(bxc5Pid);
        Resource recResc = depRec.getResource();

        assertTrue("Did not have deposit record type", recResc.hasProperty(RDF.type, Cdr.DepositRecord));
        assertTrue(recResc.hasProperty(DC.title, "Deposit Record with Manifests"));
        assertEquals(DEFAULT_CREATED_DATE, depRec.getCreatedDate());
        assertEquals(DEFAULT_MODIFIED_DATE, depRec.getLastModified());

        assertPremisTransformed(depRec);

        List<BinaryObject> binList = depRec.listManifests().stream()
                .map(repoObjLoader::getBinaryObject)
                .collect(toList());

        assertEquals("Incorrect number of manifests", 2, binList.size());

        BinaryObject manifest0Bin = getManifestByName(binList, manifest0Name);
        assertManifestDetails(DEFAULT_CREATED_DATE,
                "text/xml",
                manifest0Content,
                manifest0Bin);

        BinaryObject manifest1Bin = getManifestByName(binList, manifest1Name);
        assertManifestDetails(DEFAULT_MODIFIED_DATE,
                "text/plain",
                manifest1Content,
                manifest1Bin);
    }

    private void updatePathIndex() {
        pathIndexingService.indexObjectsFromPath(objectsPath);
        pathIndexingService.indexDatastreamsFromPath(datastreamsPath);
    }

    private void createDeposit(String packageType) throws IOException {
        Model bxc3Model = createModelWithTypes(bxc3Pid, ContentModel.DEPOSIT_RECORD);
        Resource bxc3Resc = bxc3Model.getResource(toBxc3Uri(bxc3Pid));
        bxc3Resc.addLiteral(CDRProperty.depositedOnBehalfOf.getProperty(), "some depositor");
        bxc3Resc.addLiteral(CDRProperty.depositMethod.getProperty(), "dep method");
        bxc3Resc.addLiteral(CDRProperty.depositPackageType.getProperty(), packageType);
        bxc3Resc.addLiteral(CDRProperty.depositPackageSubType.getProperty(), "package subtype");

        Document foxml = new FoxmlDocumentBuilder(bxc3Pid, "Migrated Deposit Record")
                .relsExtModel(bxc3Model)
                .build();
        serializeFoxml(bxc3Pid, foxml);

        addPremisLog(bxc3Pid);

        updatePathIndex();
    }
}
