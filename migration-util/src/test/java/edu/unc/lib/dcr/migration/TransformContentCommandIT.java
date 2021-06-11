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
package edu.unc.lib.dcr.migration;

import static edu.unc.lib.dcr.migration.MigrationConstants.toBxc3Uri;
import static edu.unc.lib.dcr.migration.premis.TestPremisEventHelpers.listEventResources;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.jdom2.Document;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dcr.migration.content.ContentTransformationReport;
import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.ContentModel;
import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.Relationship;
import edu.unc.lib.dcr.migration.fcrepo3.DatastreamVersion;
import edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentBuilder;
import edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers;
import edu.unc.lib.dl.fcrepo4.DepositRecord;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fcrepo4.RepositoryPathConstants;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.persist.services.deposit.DepositModelManager;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;
import picocli.CommandLine;

/**
 * @author bbpennel
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml")
})
public class TransformContentCommandIT extends AbstractTransformationIT {
    private static final Logger log = getLogger(TransformContentCommandIT.class);

    private static final String BINARY_CONTENT = "Binary stuff";
    private static final String BINARY_MD5 = "74dec3bdb7c0cda2c3d501c145d73723";

    @Autowired
    private RepositoryObjectLoader repoObjLoader;
    @Autowired
    private RepositoryPIDMinter pidMinter;
    @Autowired
    private RepositoryObjectFactory repoObjFactory;

    private DepositModelManager modelManager;

    final PrintStream originalOut = System.out;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private PID bxc3Pid;

    CommandLine migrationCommand;

    private File tdbDir;
    private File depositBaseDir;

    private String output;

    @Before
    public void setUp() throws Exception {
        TestHelper.setContentBase("http://localhost:48085/rest");

        out.reset();
        System.setOut(new PrintStream(out));

        datastreamsPath = tmpFolder.newFolder("datastreams").toPath();
        objectsPath = tmpFolder.newFolder("objects").toPath();

        bxc3Pid = pidMinter.mintContentPid();

        tdbDir = tmpFolder.newFolder("tdb");
        depositBaseDir = tmpFolder.newFolder("deposits");
        File dbFile = tmpFolder.newFile("index_db");
        System.setProperty("dcr.deposit.dir", depositBaseDir.getAbsolutePath());
        System.setProperty("dcr.tdb.dir", tdbDir.getAbsolutePath());
        System.setProperty("dcr.migration.index.url", dbFile.toPath().toUri().toString());
        System.setProperty("dcr.it.tdr.ingestSource", tmpFolder.getRoot().getAbsolutePath());

        migrationCommand = new CommandLine(new MigrationCLI());

        // Set the application context path for the test environment
        Map<String, CommandLine> subs = migrationCommand.getSubcommands();
        CommandLine transformCommand = subs.get("tc");

        TransformContentCommand tcCommand = (TransformContentCommand) transformCommand.getCommand();
        Path contextPath = Paths.get("src", "test", "resources", "spring-test",
                "cdr-client-container.xml");
        tcCommand.setApplicationContextPath(contextPath.toUri().toString());

        output = null;

        ContentTransformationReport.reset();
    }

    @After
    public void cleanup() {
        System.setOut(originalOut);
        System.clearProperty("dcr.tdb.dir");
        System.clearProperty("dcr.deposit.dir");
        System.clearProperty("dcr.migration.index.url");
        System.clearProperty("dcr.it.tdr.ingestSource");
        if (modelManager != null) {
            modelManager.close();
        }
    }

    @Test
    public void transformSimpleStructure() throws Exception {
        PID filePid = pidMinter.mintContentPid();
        Path dsPath = writeDatastreamFile(filePid, FoxmlDocumentHelpers.ORIGINAL_DS, BINARY_CONTENT);
        DatastreamVersion ds0 = new DatastreamVersion(BINARY_MD5,
                FoxmlDocumentHelpers.ORIGINAL_DS, "0",
                FoxmlDocumentBuilder.DEFAULT_CREATED_DATE,
                Integer.toString(BINARY_CONTENT.length()),
                "text/plain",
                null);

        Model fileModel = createModelWithTypes(filePid, ContentModel.SIMPLE);

        Document foxml2 = new FoxmlDocumentBuilder(filePid, "file")
                .relsExtModel(fileModel)
                .withDatastreamVersion(ds0)
                .build();
        serializeFoxml(filePid, foxml2);

        addPremisLog(filePid);

        Model folderModel = createModelWithTypes(bxc3Pid, ContentModel.CONTAINER);
        Resource folderResc = folderModel.getResource(toBxc3Uri(bxc3Pid));
        Resource fileResc = folderModel.getResource(toBxc3Uri(filePid));
        folderResc.addProperty(Relationship.contains.getProperty(), fileResc);

        serializeBasicObject(bxc3Pid, "folder", folderModel);

        indexFiles();

        String[] args = new String[] { "tc", bxc3Pid.getId() };
        executeExpectSuccess(args);

        PID depositPid = extractDepositPid(output);

        assertTrue("Expected one transformation successful",
                output.contains(" 2/2 "));
        assertTrue("Expected transformation completed message",
                output.contains("Finished transformation"));

        modelManager = new DepositModelManager(tdbDir.toString());
        Model depModel = modelManager.getReadModel(depositPid);

        Resource resultFolderResc = depModel.getResource(bxc3Pid.getRepositoryPath());
        assertTrue(resultFolderResc.hasProperty(RDF.type, Cdr.Folder));
        assertTrue(resultFolderResc.hasProperty(CdrDeposit.createTime, FoxmlDocumentBuilder.DEFAULT_CREATED_DATE));

        Bag resultWorkBag = depModel.getBag(filePid.getRepositoryPath());
        assertTrue(resultWorkBag.hasProperty(RDF.type, Cdr.Work));
        assertTrue(resultWorkBag.hasProperty(CdrDeposit.createTime, FoxmlDocumentBuilder.DEFAULT_CREATED_DATE));

        List<RDFNode> bagChildren = resultWorkBag.iterator().toList();
        Resource resultFileResc = (Resource) bagChildren.get(0);
        assertTrue(resultFileResc.hasProperty(RDF.type, Cdr.FileObject));
        Resource origResc = DepositModelHelpers.getDatastream(resultFileResc);
        assertTrue(origResc.hasLiteral(CdrDeposit.md5sum, BINARY_MD5));
        assertTrue(origResc.hasLiteral(CdrDeposit.stagingLocation, dsPath.toUri().toString()));

        assertTrue("Deposit directory must exist", new File(depositBaseDir, depositPid.getId()).exists());
    }

    @Test
    public void transformMultipleIds() throws Exception {
        PID folder1Bxc3Pid = pidMinter.mintContentPid();
        Model folder1Model = createModelWithTypes(folder1Bxc3Pid, ContentModel.CONTAINER);
        PID folder2Bxc3Pid = pidMinter.mintContentPid();
        Model folder2Model = createModelWithTypes(folder2Bxc3Pid, ContentModel.CONTAINER);
        PID folder2ChildBxc3Pid = pidMinter.mintContentPid();
        Model folder2ChildModel = createModelWithTypes(folder2ChildBxc3Pid, ContentModel.CONTAINER);
        PID folder3Bxc3Pid = pidMinter.mintContentPid();
        Model folder3Model = createModelWithTypes(folder3Bxc3Pid, ContentModel.CONTAINER);

        Resource folder2Resc = folder2Model.getResource(toBxc3Uri(folder2Bxc3Pid));
        Resource folder2ChildResc = folder2Model.getResource(toBxc3Uri(folder2ChildBxc3Pid));
        folder2Resc.addProperty(Relationship.contains.getProperty(), folder2ChildResc);

        serializeBasicObject(folder1Bxc3Pid, "folder1", folder1Model);
        serializeBasicObject(folder2Bxc3Pid, "folder2", folder2Model);
        serializeBasicObject(folder2ChildBxc3Pid, "folder2c", folder2ChildModel);
        serializeBasicObject(folder3Bxc3Pid, "folder3", folder3Model);

        indexFiles();

        String[] args = new String[] { "tc", String.join(",",
                folder1Bxc3Pid.getId(), folder2Bxc3Pid.getId(), folder3Bxc3Pid.getId()) };
        executeExpectSuccess(args);

        PID depositPid = extractDepositPid(output);

        assertTrue("Expected one transformation successful",
                output.contains(" 4/4 "));
        assertTrue("Expected transformation completed message",
                output.contains("Finished transformation"));

        modelManager = new DepositModelManager(tdbDir.toString());
        Model depModel = modelManager.getReadModel(depositPid);

        Resource resultFolder1Resc = depModel.getResource(folder1Bxc3Pid.getRepositoryPath());
        assertTrue(resultFolder1Resc.hasProperty(RDF.type, Cdr.Folder));
        assertTrue(resultFolder1Resc.hasProperty(CdrDeposit.label, "folder1"));
        assertTrue(resultFolder1Resc.hasProperty(CdrDeposit.createTime, FoxmlDocumentBuilder.DEFAULT_CREATED_DATE));

        Resource resultFolder2Resc = depModel.getResource(folder2Bxc3Pid.getRepositoryPath());
        assertTrue(resultFolder2Resc.hasProperty(RDF.type, Cdr.Folder));
        assertTrue(resultFolder2Resc.hasProperty(CdrDeposit.label, "folder2"));
        assertTrue(resultFolder2Resc.hasProperty(CdrDeposit.createTime, FoxmlDocumentBuilder.DEFAULT_CREATED_DATE));

        Resource resultFolder2ChildResc = depModel.getResource(folder2ChildBxc3Pid.getRepositoryPath());
        assertTrue(resultFolder2ChildResc.hasProperty(RDF.type, Cdr.Folder));
        assertTrue(resultFolder2ChildResc.hasProperty(CdrDeposit.label, "folder2c"));
        assertTrue(resultFolder2ChildResc.hasProperty(CdrDeposit.createTime, FoxmlDocumentBuilder.DEFAULT_CREATED_DATE));

        Resource resultFolder3Resc = depModel.getResource(folder3Bxc3Pid.getRepositoryPath());
        assertTrue(resultFolder3Resc.hasProperty(RDF.type, Cdr.Folder));
        assertTrue(resultFolder3Resc.hasProperty(CdrDeposit.label, "folder3"));
        assertTrue(resultFolder3Resc.hasProperty(CdrDeposit.createTime, FoxmlDocumentBuilder.DEFAULT_CREATED_DATE));

        List<RDFNode> bagChildren = depModel.getBag(resultFolder2Resc).iterator().toList();
        Resource resultChildResc = (Resource) bagChildren.get(0);
        assertEquals(resultFolder2ChildResc, resultChildResc);

        assertTrue("Deposit directory must exist", new File(depositBaseDir, depositPid.getId()).exists());
    }

    @Test
    public void transformDryRun() throws Exception {
        Model folderModel = createModelWithTypes(bxc3Pid, ContentModel.CONTAINER);

        serializeBasicObject(bxc3Pid, "folder", folderModel);

        indexFiles();

        String[] args = new String[] { "tc", bxc3Pid.getId(),
                "-n"};
        executeExpectSuccess(args);

        PID depositPid = extractDepositPid(output);

        assertTrue("Expected one transformation successful",
                output.contains(" 1/1 "));
        assertTrue("Expected transformation completed message",
                output.contains("Finished transformation"));
        assertTrue("Expected dry run message",
                output.contains("Dry run, deposit model not saved"));

        assertFalse("Deposit directory must not exist", new File(depositBaseDir, depositPid.getId()).exists());
    }

    @Test
    public void transformGenerateMissingDepositRecord() throws Exception {
        PID mysteryDepositPid = pidMinter.mintDepositRecordPid();

        Model folderModel = createModelWithTypes(bxc3Pid, ContentModel.CONTAINER);
        Resource folderResc = folderModel.getResource(toBxc3Uri(bxc3Pid));
        folderResc.addProperty(Relationship.originalDeposit.getProperty(),
                createResource(toBxc3Uri(mysteryDepositPid)));
        serializeBasicObject(bxc3Pid, "folder", folderModel);

        indexFiles();

        String[] args = new String[] { "tc", bxc3Pid.getId(),
                "--missing-deposit-records",
                "--default-storage-location", "loc1"};
        executeExpectSuccess(args);

        PID depositPid = extractDepositPid(output);

        assertTrue("Expected one transformation successful",
                output.contains(" 1/1 "));
        assertTrue("Expected transformation completed message",
                output.contains("Finished transformation"));
        assertTrue("Expected generated deposit records report",
                output.contains("Generated deposit records: 1"));

        modelManager = new DepositModelManager(tdbDir.toString());
        Model depModel = modelManager.getReadModel(depositPid);

        Resource resultFolderResc = depModel.getResource(bxc3Pid.getRepositoryPath());
        assertTrue(resultFolderResc.hasProperty(RDF.type, Cdr.Folder));
        assertTrue(resultFolderResc.hasProperty(CdrDeposit.createTime, FoxmlDocumentBuilder.DEFAULT_CREATED_DATE));
        assertTrue(resultFolderResc.hasProperty(CdrDeposit.originalDeposit,
                createResource(mysteryDepositPid.getRepositoryPath())));

        DepositRecord generatedRec = repoObjLoader.getDepositRecord(mysteryDepositPid);
        assertTrue(generatedRec.getResource().hasLiteral(Cdr.storageLocation, "loc1"));

        assertEquals(DEFAULT_CREATED_DATE, generatedRec.getCreatedDate());
        PremisLogger premisLog = generatedRec.getPremisLog();
        Model eventsModel = premisLog.getEventsModel();
        List<Resource> eventRescs = listEventResources(mysteryDepositPid, eventsModel);
        assertEquals(1, eventRescs.size());

        Resource ingestEventResc = eventRescs.get(0);
        assertTrue(ingestEventResc.hasProperty(RDF.type, Premis.Ingestion));
        assertTrue("Missing migration event note",
                ingestEventResc.hasProperty(Premis.note, "Deposit record generated by Boxc 3 to 5 migration"));
        Resource agentResc = ingestEventResc.getProperty(Premis.hasEventRelatedAgentExecutor).getResource();
        assertEquals(AgentPids.forSoftware(SoftwareAgent.migrationUtil).getRepositoryPath(),
                agentResc.getURI());
    }

    @Test
    public void transformDryRunMissingDepositRecord() throws Exception {
        PID mysteryDepositPid = pidMinter.mintDepositRecordPid();

        Model folderModel = createModelWithTypes(bxc3Pid, ContentModel.CONTAINER);
        Resource folderResc = folderModel.getResource(toBxc3Uri(bxc3Pid));
        folderResc.addProperty(Relationship.originalDeposit.getProperty(),
                createResource(toBxc3Uri(mysteryDepositPid)));
        serializeBasicObject(bxc3Pid, "folder", folderModel);

        indexFiles();

        String[] args = new String[] { "tc", bxc3Pid.getId(),
                "--missing-deposit-records",
                "--default-storage-location", "loc1",
                "--dry-run"};
        executeExpectSuccess(args);

        PID depositPid = extractDepositPid(output);

        assertTrue("Expected one transformation successful",
                output.contains(" 1/1 "));
        assertTrue("Expected transformation completed message",
                output.contains("Finished transformation"));
        assertTrue("Expected dry run message",
                output.contains("Dry run, deposit model not saved"));
        assertTrue("Expected generated deposit records report",
                output.contains("Generated deposit records: 1"));

        assertFalse("Deposit directory must not exist", new File(depositBaseDir, depositPid.getId()).exists());

        assertFalse(repoObjFactory.objectExists(mysteryDepositPid.getRepositoryUri()));
    }

    @Test
    public void transformSkipMembers() throws Exception {
        PID innerPid = pidMinter.mintContentPid();
        Model innerFolderModel = createModelWithTypes(innerPid, ContentModel.CONTAINER);

        Model folderModel = createModelWithTypes(bxc3Pid, ContentModel.CONTAINER);
        Resource folderResc = folderModel.getResource(toBxc3Uri(bxc3Pid));
        Resource fileResc = folderModel.getResource(toBxc3Uri(innerPid));
        folderResc.addProperty(Relationship.contains.getProperty(), fileResc);

        addPremisLog(bxc3Pid);

        serializeBasicObject(bxc3Pid, "folder", folderModel);
        serializeBasicObject(innerPid, "folder2", innerFolderModel);

        indexFiles();

        String[] args = new String[] { "tc", bxc3Pid.getId(), "--skip-members" };
        executeExpectSuccess(args);

        PID depositPid = extractDepositPid(output);

        assertTrue("Expected one transformation successful",
                output.contains(" 1/1 "));
        assertTrue("Expected transformation completed message",
                output.contains("Finished transformation"));

        modelManager = new DepositModelManager(tdbDir.toString());
        Model depModel = modelManager.getReadModel(depositPid);

        Bag resultFolderResc = depModel.getBag(bxc3Pid.getRepositoryPath());
        assertTrue(resultFolderResc.hasProperty(RDF.type, Cdr.Folder));
        assertTrue(resultFolderResc.hasProperty(CdrDeposit.createTime, FoxmlDocumentBuilder.DEFAULT_CREATED_DATE));

        List<RDFNode> bagChildren = resultFolderResc.iterator().toList();
        assertEquals("No children should have been migrated due to skip-members", 0, bagChildren.size());

        assertTrue("Deposit directory must exist", new File(depositBaseDir, depositPid.getId()).exists());
    }

    private void serializeBasicObject(PID pid, String title, Model model) throws IOException {
        Document foxml = new FoxmlDocumentBuilder(pid, title)
                .relsExtModel(model)
                .build();
        serializeFoxml(pid, foxml);
    }

    private PID extractDepositPid(String output) {
        String fieldText = "Populating deposit: ";
        int start = output.indexOf(fieldText);
        if (start == -1) {
            fail("No deposit id output");
        }
        start += fieldText.length();
        int end = output.indexOf("\n", start);
        String id = output.substring(start, end);
        return PIDs.get(RepositoryPathConstants.DEPOSIT_RECORD_BASE, id);
    }

    private void indexFiles() {
        String[] indexArgs = new String[] { "pi", "populate",
                objectsPath.toString(),
                datastreamsPath.toString(),
                "-l" };
        migrationCommand.execute(indexArgs);
    }

    private void executeExpectSuccess(String[] args) {
        int result = migrationCommand.execute(args);
        output = out.toString();
        if (result != 0) {
            System.setOut(originalOut);
            log.error(output);
            fail("Expected command to result in success: " + String.join(" ", args));
        }
    }
}
