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

import static edu.unc.lib.dl.util.DateTimeUtil.parseUTCToDate;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.MODS_V3_NS;
import static org.junit.Assert.assertEquals;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.junit.Before;
import org.junit.Test;

import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.fedora.ServiceException;
import edu.unc.lib.dl.xml.SecureXMLFactory;

/**
 * @author bbpennel
 */
public class DatastreamHistoryLogTest {

    private static final String TEST_ID = "a168cf29-a2a9-4da8-9b8d-025855b180d5/md/md_file";
    private static final String TEST_TITLE = "Historical doc";
    private static final String VERSION1_TIME = "2020-03-03T12:00:00.000Z";
    private static final String VERSION2_TIME = "2020-03-15T05:00:00.000Z";
    private static final Date VERSION1_DATE = parseUTCToDate(VERSION1_TIME);
    private static final Date VERSION2_DATE = parseUTCToDate(VERSION2_TIME);

    private PID dsPid;

    @Before
    public void setup() throws Exception {
        initMocks(this);
        dsPid = PIDs.get(TEST_ID);
    }

    @Test
    public void addVersion_NewLog_XmlContent() throws Exception {
        DatastreamHistoryLog historyLog = new DatastreamHistoryLog(dsPid);

        InputStream contentStream = getModsDocumentStream(TEST_TITLE);

        historyLog.addVersion(contentStream, "text/xml", VERSION1_DATE);

        Document logDoc = inputStreamToDocument(historyLog.toInputStream());
        assertLogObjectIdEquals(dsPid, logDoc);

        List<Element> versions = logDoc.getRootElement().getChildren(DatastreamHistoryLog.VERSION_TAG);
        assertEquals(1, versions.size());

        Element versionEl = versions.get(0);
        assertVersionAttributes(VERSION1_TIME, "text/xml", versionEl);
        assertVersionHasModsTitle(TEST_TITLE, versionEl);
    }

    @Test
    public void addVersion_NewLog_TextContent() throws Exception {
        DatastreamHistoryLog historyLog = new DatastreamHistoryLog(dsPid);

        String dsContent = "all of my content";
        InputStream contentStream = new ByteArrayInputStream(dsContent.getBytes());

        historyLog.addVersion(contentStream, "text/plain", VERSION1_DATE);

        Document logDoc = inputStreamToDocument(historyLog.toInputStream());
        assertLogObjectIdEquals(dsPid, logDoc);

        List<Element> versions = logDoc.getRootElement().getChildren(DatastreamHistoryLog.VERSION_TAG);
        assertEquals(1, versions.size());

        Element versionEl = versions.get(0);
        assertVersionAttributes(VERSION1_TIME, "text/plain", versionEl);
        assertEquals(dsContent, versionEl.getText());
    }

    @Test(expected = ServiceException.class)
    public void addVersion_NewLog_TextContentWithXmlType_Fail() throws Exception {
        DatastreamHistoryLog historyLog = new DatastreamHistoryLog(dsPid);

        String dsContent = "all of my content";
        InputStream contentStream = new ByteArrayInputStream(dsContent.getBytes());

        historyLog.addVersion(contentStream, "text/xml", VERSION1_DATE);
    }

    @Test
    public void addVersion_NewLog_MultipleVersions() throws Exception {
        DatastreamHistoryLog historyLog = new DatastreamHistoryLog(dsPid);

        InputStream contentStream = getModsDocumentStream(TEST_TITLE);
        historyLog.addVersion(contentStream, "text/xml", VERSION1_DATE);

        InputStream contentStream2 = getModsDocumentStream("another title");
        historyLog.addVersion(contentStream2, "text/xml", VERSION2_DATE);

        Document logDoc = inputStreamToDocument(historyLog.toInputStream());
        assertLogObjectIdEquals(dsPid, logDoc);

        List<Element> versions = logDoc.getRootElement().getChildren(DatastreamHistoryLog.VERSION_TAG);
        assertEquals(2, versions.size());

        Element versionEl = versions.get(0);
        assertVersionAttributes(VERSION1_TIME, "text/xml", versionEl);
        assertVersionHasModsTitle(TEST_TITLE, versionEl);

        Element versionEl2 = versions.get(1);
        assertVersionAttributes(VERSION2_TIME, "text/xml", versionEl2);
        assertVersionHasModsTitle("another title", versionEl2);
    }

    @Test
    public void addVersion_ExistingLog_MultipleVersions() throws Exception {
        DatastreamHistoryLog startingLog = new DatastreamHistoryLog(dsPid);

        InputStream contentStream = getModsDocumentStream(TEST_TITLE);
        startingLog.addVersion(contentStream, "text/xml", VERSION1_DATE);

        InputStream startingLogStream = startingLog.toInputStream();

        DatastreamHistoryLog historyLog = new DatastreamHistoryLog(dsPid, startingLogStream);

        InputStream contentStream2 = getModsDocumentStream("another title");
        historyLog.addVersion(contentStream2, "text/xml", VERSION2_DATE);

        Document logDoc = inputStreamToDocument(historyLog.toInputStream());
        assertLogObjectIdEquals(dsPid, logDoc);

        List<Element> versions = logDoc.getRootElement().getChildren(DatastreamHistoryLog.VERSION_TAG);
        assertEquals(2, versions.size());

        Element versionEl = versions.get(0);
        assertVersionAttributes(VERSION1_TIME, "text/xml", versionEl);
        assertVersionHasModsTitle(TEST_TITLE, versionEl);

        Element versionEl2 = versions.get(1);
        assertVersionAttributes(VERSION2_TIME, "text/xml", versionEl2);
        assertVersionHasModsTitle("another title", versionEl2);
    }

    @Test
    public void newLog_NoVersions() throws Exception {
        DatastreamHistoryLog historyLog = new DatastreamHistoryLog(dsPid);

        Document logDoc = inputStreamToDocument(historyLog.toInputStream());
        assertLogObjectIdEquals(dsPid, logDoc);

        List<Element> versions = logDoc.getRootElement().getChildren(DatastreamHistoryLog.VERSION_TAG);
        assertEquals(0, versions.size());
    }

    private void assertLogObjectIdEquals(PID expected, Document logDoc) {
        String id = logDoc.getRootElement().getAttributeValue(DatastreamHistoryLog.ID_ATTR);
        assertEquals("Identifier in log doc did not match expected id",
                expected.getQualifiedId(), id);
    }

    private void assertVersionHasModsTitle(String expected, Element versionEl) {
        String titleVal = versionEl.getChild("mods", MODS_V3_NS)
                .getChild("titleInfo", MODS_V3_NS)
                .getChildText("title", MODS_V3_NS);
        assertEquals(expected, titleVal);
    }

    private void assertVersionAttributes(String expectedTime, String expectedType, Element versionEl) {
        String created = versionEl.getAttributeValue(DatastreamHistoryLog.CREATED_ATTR);
        assertEquals(expectedTime, created);
        String contentType = versionEl.getAttributeValue(DatastreamHistoryLog.CONTENT_TYPE_ATTR);
        assertEquals(expectedType, contentType);
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

    private Document inputStreamToDocument(InputStream docStream) throws Exception{
        return SecureXMLFactory.createSAXBuilder().build(docStream);
    }
}