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
package edu.unc.lib.dl.persist.services.versioning;

import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.DCR_PACKAGING_NS;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.MODS_V3_NS;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.ObjectTypeMismatchException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.persist.services.ingest.IngestSourceManagerImpl;
import edu.unc.lib.dl.persist.services.ingest.IngestSourceTestHelper;
import edu.unc.lib.dl.persist.services.transfer.BinaryTransferServiceImpl;
import edu.unc.lib.dl.persist.services.versioning.VersionedDatastreamService.DatastreamVersion;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.DateTimeUtil;
import edu.unc.lib.dl.xml.SecureXMLFactory;

/**
 * @author bbpennel
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml")
})
public class VersionedDatastreamServiceIT {
    private static final String TEST_TITLE = "Historical doc";

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    private VersionedDatastreamService service;

    @Autowired
    private String baseAddress;
    @Autowired
    private RepositoryObjectLoader repoObjLoader;
    @Autowired
    private RepositoryObjectFactory repoObjFactory;
    @Autowired
    private BinaryTransferServiceImpl transferService;
    @Autowired
    private RepositoryPIDMinter pidMinter;
    @Autowired
    private RepositoryObjectTreeIndexer treeIndexer;
    @Autowired
    private TransactionManager transactionManager;

    private IngestSourceManagerImpl sourceManager;
    private Path sourcePath;

    private PID parentPid;
    private PID dsPid;

    @Before
    public void setup() throws Exception {
        TestHelper.setContentBase(baseAddress);

        service = new VersionedDatastreamService();
        service.setBinaryTransferService(transferService);
        service.setRepositoryObjectFactory(repoObjFactory);
        service.setRepositoryObjectLoader(repoObjLoader);
        service.setTransactionManager(transactionManager);

        File sourceMappingFile = new File(tmpFolder.getRoot(), "sourceMapping.json");
        FileUtils.writeStringToFile(sourceMappingFile, "[]", "UTF-8");
        sourcePath = tmpFolder.newFolder("source_dir").toPath();
        Path sourceConfigPath = IngestSourceTestHelper.createConfigFile(
                IngestSourceTestHelper.createFilesystemConfig("source_dir", "src", sourcePath, asList("*")));

        sourceManager = new IngestSourceManagerImpl();
        sourceManager.setConfigPath(sourceConfigPath.toString());
        sourceManager.setMappingPath(sourceMappingFile.toString());
        sourceManager.init();

        transferService.setIngestSourceManager(sourceManager);

        parentPid = pidMinter.mintContentPid();
        dsPid = DatastreamPids.getMdDescriptivePid(parentPid);
    }

    @Test
    public void addVersion_NewDatastream_StreamContent() throws Exception {
        repoObjFactory.createFolderObject(parentPid, null);
        treeIndexer.indexAll(baseAddress);

        DatastreamVersion newV = new DatastreamVersion(dsPid);
        newV.setContentStream(getModsDocumentStream(TEST_TITLE));
        newV.setContentType("text/xml");

        BinaryObject dsObj = service.addVersion(newV);
        Document storedDoc = inputStreamToDocument(dsObj.getBinaryStream());

        assertHasModsTitle(TEST_TITLE, storedDoc);
    }

    @Test
    public void addVersion_NewDatastream_UriContent() throws Exception {
        repoObjFactory.createFolderObject(parentPid, null);
        treeIndexer.indexAll(baseAddress);

        Path srcFile = sourcePath.resolve("src_mods.xml");
        DatastreamVersion newV = new DatastreamVersion(dsPid);
        Files.copy(getModsDocumentStream(TEST_TITLE), srcFile);
        newV.setStagedContentUri(srcFile.toUri());
        newV.setContentType("text/xml");

        BinaryObject dsObj = service.addVersion(newV);
        Document storedDoc = inputStreamToDocument(dsObj.getBinaryStream());

        assertHasModsTitle(TEST_TITLE, storedDoc);
        assertNotNull("Checksum not set", dsObj.getSha1Checksum());
    }

    @Test
    public void addVersion_ExistingDatastream() throws Exception {
        repoObjFactory.createFolderObject(parentPid, null);
        treeIndexer.indexAll(baseAddress);

        DatastreamVersion newV1 = new DatastreamVersion(dsPid);
        newV1.setContentStream(getModsDocumentStream(TEST_TITLE));
        newV1.setContentType("text/xml");

        BinaryObject dsObj = service.addVersion(newV1);
        Date originalCreated = dsObj.getCreatedDate();
        String digest1 = dsObj.getSha1Checksum();
        assertNotNull("Checksum not set for first version", digest1);

        DatastreamVersion newV2 = new DatastreamVersion(dsPid);
        newV2.setContentStream(getModsDocumentStream("more titles"));
        newV2.setContentType("text/xml");

        BinaryObject dsObjUpdated = service.addVersion(newV2);
        String digest2 = dsObjUpdated.getSha1Checksum();
        assertNotNull("Checksum not set for second version", digest2);
        assertNotEquals("Versions must have different digests",  digest1, digest2);

        Document headDoc = inputStreamToDocument(dsObjUpdated.getBinaryStream());
        assertHasModsTitle("more titles", headDoc);

        PID historyPid = DatastreamPids.getDatastreamHistoryPid(dsPid);
        BinaryObject dsHistoryObj = repoObjLoader.getBinaryObject(historyPid);
        assertNotNull("Checksum not set for history", dsHistoryObj.getSha1Checksum());

        Document logDoc = inputStreamToDocument(dsHistoryObj.getBinaryStream());
        List<Element> versions = listVersions(logDoc);
        assertEquals(1, versions.size());

        Element versionEl = versions.get(0);
        assertVersionAttributes(originalCreated, "text/xml", versionEl);
        assertVersionHasModsTitle(TEST_TITLE, versionEl);
    }

    @Test
    public void addVersion_DatastreamWithHistory() throws Exception {
        repoObjFactory.createFolderObject(parentPid, null);
        treeIndexer.indexAll(baseAddress);

        DatastreamVersion newV1 = new DatastreamVersion(dsPid);
        newV1.setContentStream(getModsDocumentStream(TEST_TITLE));
        newV1.setContentType("text/xml");

        BinaryObject dsObj = service.addVersion(newV1);
        Date version1Date = dsObj.getCreatedDate();
        String digest1 = dsObj.getSha1Checksum();
        assertNotNull("Checksum not set for first version", digest1);

        DatastreamVersion newV2 = new DatastreamVersion(dsPid);
        newV2.setContentStream(getModsDocumentStream("more titles"));
        newV2.setContentType("text/xml");

        BinaryObject dsObj2 = service.addVersion(newV2);
        Date version2Date = dsObj2.getLastModified();
        String digest2 = dsObj2.getSha1Checksum();
        assertNotNull("Checksum not set for second version", digest2);

        DatastreamVersion newV3 = new DatastreamVersion(dsPid);
        newV3.setContentStream(getModsDocumentStream("lets leave it here"));
        newV3.setContentType("text/xml");

        BinaryObject dsObjFinal = service.addVersion(newV3);
        String digest3 = dsObjFinal.getSha1Checksum();
        assertNotNull("Checksum not set for second version", digest3);

        assertNotEquals(digest1, digest2);
        assertNotEquals(digest2, digest3);
        assertNotEquals(digest1, digest3);

        Document headDoc = inputStreamToDocument(dsObjFinal.getBinaryStream());
        assertHasModsTitle("lets leave it here", headDoc);

        assertNotEquals("Second version timestamp should not match head version",
                version2Date, dsObjFinal.getLastModified());

        // check historic versions
        PID historyPid = DatastreamPids.getDatastreamHistoryPid(dsPid);
        BinaryObject dsHistoryObj = repoObjLoader.getBinaryObject(historyPid);
        assertNotNull("Checksum not set for history", dsHistoryObj.getSha1Checksum());

        Document logDoc = inputStreamToDocument(dsHistoryObj.getBinaryStream());
        List<Element> versions = listVersions(logDoc);
        assertEquals(2, versions.size());

        Element versionEl = versions.get(0);
        assertVersionAttributes(version1Date, "text/xml", versionEl);
        assertVersionHasModsTitle(TEST_TITLE, versionEl);

        Element versionEl2 = versions.get(1);
        assertVersionAttributes(version2Date, "text/xml", versionEl2);
        assertVersionHasModsTitle("more titles", versionEl2);
    }

    @Test
    public void addVersion_NewDatastream_ProvidedTransferSession() throws Exception {
        WorkObject work = repoObjFactory.createWorkObject(parentPid, null);
        treeIndexer.indexAll(baseAddress);

        try (BinaryTransferSession session = transferService.getSession(work)) {
            DatastreamVersion newV = new DatastreamVersion(dsPid);
            newV.setContentStream(getModsDocumentStream(TEST_TITLE));
            newV.setContentType("text/xml");
            newV.setTransferSession(session);

            BinaryObject dsObj = service.addVersion(newV);
            Document storedDoc = inputStreamToDocument(dsObj.getBinaryStream());

            assertHasModsTitle(TEST_TITLE, storedDoc);
            assertNotNull("Checksum not set for first version", dsObj.getSha1Checksum());
        }
    }

    @Test(expected = ObjectTypeMismatchException.class)
    public void addVersion_NonDatastreamObject() throws Exception {
        repoObjFactory.createWorkObject(parentPid, null);
        treeIndexer.indexAll(baseAddress);

        DatastreamVersion newV = new DatastreamVersion(parentPid);
        newV.setContentStream(getModsDocumentStream(TEST_TITLE));
        newV.setContentType("text/xml");

        service.addVersion(newV);
    }

    @Test(expected = IllegalArgumentException.class)
    public void addVersion_NonDatastreamPid() throws Exception {
        DatastreamVersion newV = new DatastreamVersion(parentPid);
        newV.setContentStream(getModsDocumentStream(TEST_TITLE));
        newV.setContentType("text/xml");

        service.addVersion(newV);
    }

    private InputStream getModsDocumentStream(String title) throws Exception {
        Document document = new Document()
                .addContent(new Element("mods", MODS_V3_NS)
                .addContent(new Element("titleInfo", MODS_V3_NS)
                        .addContent(new Element("title", MODS_V3_NS).setText(title))));

        return convertDocumentToStream(document);
    }

    private InputStream convertDocumentToStream(Document doc) throws IOException {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        new XMLOutputter(Format.getPrettyFormat()).output(doc, outStream);
        return new ByteArrayInputStream(outStream.toByteArray());
    }

    private void assertHasModsTitle(String expected, Document doc) {
        assertHasModsTitle(expected, doc.getRootElement());
    }

    private void assertVersionHasModsTitle(String expected, Element versionEl) {
        assertHasModsTitle(expected, versionEl.getChild("mods", MODS_V3_NS));
    }

    private void assertHasModsTitle(String expected, Element modsEl) {
        String titleVal = modsEl.getChild("titleInfo", MODS_V3_NS)
                .getChildText("title", MODS_V3_NS);
        assertEquals(expected, titleVal);
    }

    private Document inputStreamToDocument(InputStream docStream) throws Exception{
        return SecureXMLFactory.createSAXBuilder().build(docStream);
    }

    private void assertVersionAttributes(Date expectedTime, String expectedType, Element versionEl) {
        String created = versionEl.getAttributeValue(DatastreamHistoryLog.CREATED_ATTR);
        assertEquals(expectedTime, DateTimeUtil.parseUTCToDate(created));
        String contentType = versionEl.getAttributeValue(DatastreamHistoryLog.CONTENT_TYPE_ATTR);
        assertEquals(expectedType, contentType);
    }

    private List<Element> listVersions(Document logDoc) {
        return logDoc.getRootElement()
                .getChildren(DatastreamHistoryLog.VERSION_TAG, DCR_PACKAGING_NS);
    }
}
