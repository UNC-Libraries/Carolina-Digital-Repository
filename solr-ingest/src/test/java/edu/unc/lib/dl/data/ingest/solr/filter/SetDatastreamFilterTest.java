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
package edu.unc.lib.dl.data.ingest.solr.filter;

import static edu.unc.lib.boxc.model.api.DatastreamType.JP2_ACCESS_COPY;
import static edu.unc.lib.boxc.model.api.DatastreamType.ORIGINAL_FILE;
import static edu.unc.lib.boxc.model.api.DatastreamType.TECHNICAL_METADATA;
import static edu.unc.lib.boxc.model.api.DatastreamType.THUMBNAIL_LARGE;
import static edu.unc.lib.boxc.model.api.DatastreamType.THUMBNAIL_SMALL;
import static edu.unc.lib.boxc.model.fcrepo.ids.DatastreamPids.getOriginalFilePid;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import edu.unc.lib.boxc.model.api.DatastreamType;
import edu.unc.lib.boxc.model.api.ids.PID;
import edu.unc.lib.boxc.model.api.objects.ContentObject;
import edu.unc.lib.boxc.model.api.rdf.Ebucore;
import edu.unc.lib.boxc.model.api.rdf.Premis;
import edu.unc.lib.boxc.model.fcrepo.ids.DatastreamPids;
import edu.unc.lib.boxc.model.fcrepo.ids.PIDs;
import edu.unc.lib.boxc.model.api.objects.BinaryObject;
import edu.unc.lib.boxc.model.api.objects.FileObject;
import edu.unc.lib.boxc.model.api.objects.FolderObject;
import edu.unc.lib.boxc.model.api.objects.WorkObject;
import edu.unc.lib.boxc.model.fcrepo.services.DerivativeService;
import edu.unc.lib.boxc.model.fcrepo.services.DerivativeService.Derivative;
import edu.unc.lib.dl.data.ingest.solr.exception.IndexingException;
import edu.unc.lib.dl.data.ingest.solr.indexing.DocumentIndexingPackage;
import edu.unc.lib.dl.search.solr.model.IndexDocumentBean;

/**
 *
 * @author bbpennel
 *
 */
public class SetDatastreamFilterTest {

    private static final String BASE_URI = "http://example.com/rest/";

    private static final String PID_STRING = "uuid:07d9594f-310d-4095-ab67-79a1056e7430";

    private static final String FILE_MIMETYPE = "text/plain";
    private static final String FILE_NAME = "test.txt";
    private static final String FILE_DIGEST = "urn:sha1:82022e1782b92dce5461ee636a6c5bea8509ffee";
    private static final long FILE_SIZE = 5062l;

    private static final String FILE2_MIMETYPE = "text/xml";
    private static final String FILE2_NAME = "fits.xml";
    private static final String FILE2_DIGEST = "urn:sha1:afbf62faf8a82d00969e0d4d965d62a45bb8c69b";
    private static final long FILE2_SIZE = 7231l;

    private static final String FILE3_MIMETYPE = "image/png";
    private static final String FILE3_NAME = "image.png";
    private static final String FILE3_DIGEST = "urn:sha1:280f5922b6487c39d6d01a5a8e93bfa07b8f1740";
    private static final long FILE3_SIZE = 17136l;
    private static final String FILE3_EXTENT = "375x250";

    private static final String MODS_MIMETYPE = "text/xml";
    private static final String MODS_NAME = "mods.xml";
    private static final String MODS_DIGEST = "urn:sha1:aa0c62faf8a82d00969e0d4d965d62a45bb8c69b";
    private static final long MODS_SIZE = 540l;

    private static final String PREMIS_MIMETYPE = "text/xml";
    private static final String PREMIS_NAME = "premis.xml";
    private static final String PREMIS_DIGEST = "urn:sha1:da39a3ee5e6b4b0d3255bfef95601890afd80709";
    private static final long PREMIS_SIZE = 893l;

    @Rule
    public TemporaryFolder derivDir = new TemporaryFolder();

    @Mock
    private DocumentIndexingPackage dip;
    private PID pid;

    @Mock
    private FileObject fileObj;
    @Mock
    private BinaryObject binObj;
    @Mock
    private IndexDocumentBean idb;
    @Captor
    private ArgumentCaptor<List<String>> listCaptor;

    @Mock
    private DerivativeService derivativeService;

    private SetDatastreamFilter filter;

    @Before
    public void setup() throws Exception {
        initMocks(this);

        pid = PIDs.get(PID_STRING);

        when(dip.getDocument()).thenReturn(idb);
        when(dip.getPid()).thenReturn(pid);
        when(fileObj.getOriginalFile()).thenReturn(binObj);
        when(binObj.getPid()).thenReturn(DatastreamPids.getOriginalFilePid(pid));
        when(fileObj.getBinaryObjects()).thenReturn(Arrays.asList(binObj));

        filter = new SetDatastreamFilter();
        filter.setDerivativeService(derivativeService);

        when(binObj.getResource()).thenReturn(
                fileResource(ORIGINAL_FILE.getId(), FILE_SIZE, FILE_MIMETYPE, FILE_NAME, FILE_DIGEST));
    }

    @Test
    public void fileObjectTest() throws Exception {
        when(dip.getContentObject()).thenReturn(fileObj);

        filter.filter(dip);

        verify(idb).setDatastream(listCaptor.capture());
        assertContainsDatastream(listCaptor.getValue(), ORIGINAL_FILE.getId(),
                FILE_SIZE, FILE_MIMETYPE, FILE_NAME, FILE_DIGEST, null, null);

        verify(idb).setFilesizeSort(eq(FILE_SIZE));
        verify(idb).setFilesizeTotal(eq(FILE_SIZE));
    }

    @Test
    public void fileObjectMultipleBinariesTest() throws Exception {
        BinaryObject binObj2 = mock(BinaryObject.class);
        when(binObj2.getPid()).thenReturn(DatastreamPids.getTechnicalMetadataPid(pid));
        when(binObj2.getResource()).thenReturn(
                fileResource(TECHNICAL_METADATA.getId(), FILE2_SIZE, FILE2_MIMETYPE, FILE2_NAME, FILE2_DIGEST));
        when(binObj2.getBinaryStream()).thenReturn(getClass().getResourceAsStream("/datastream/techmd.xml"));

        BinaryObject binObj3 = mock(BinaryObject.class);
        when(binObj3.getPid()).thenReturn(PIDs.get(pid.getId() + "/" + THUMBNAIL_LARGE.getId()));
        when(binObj3.getResource()).thenReturn(
                fileResource(THUMBNAIL_LARGE.getId(), FILE3_SIZE, FILE3_MIMETYPE, FILE3_NAME, FILE3_DIGEST));

        when(fileObj.getBinaryObjects()).thenReturn(Arrays.asList(binObj, binObj2, binObj3));
        when(dip.getContentObject()).thenReturn(fileObj);

        filter.filter(dip);

        verify(idb).setDatastream(listCaptor.capture());
        assertContainsDatastream(listCaptor.getValue(), ORIGINAL_FILE.getId(),
                FILE_SIZE, FILE_MIMETYPE, FILE_NAME, FILE_DIGEST, null, null);
        assertContainsDatastream(listCaptor.getValue(), TECHNICAL_METADATA.getId(),
                FILE2_SIZE, FILE2_MIMETYPE, FILE2_NAME, FILE2_DIGEST, null, null);
        assertContainsDatastream(listCaptor.getValue(), THUMBNAIL_LARGE.getId(),
                FILE3_SIZE, FILE3_MIMETYPE, FILE3_NAME, FILE3_DIGEST, null, null);

        verify(idb).setFilesizeSort(eq(FILE_SIZE));
        verify(idb).setFilesizeTotal(eq(FILE_SIZE + FILE2_SIZE + FILE3_SIZE));
    }

    @Test
    public void fileObjectImageBinaryTest() throws Exception {
        when(binObj.getResource()).thenReturn(
                fileResource(ORIGINAL_FILE.getId(), FILE_SIZE, FILE3_MIMETYPE, "test.png", FILE_DIGEST));

        BinaryObject binObj2 = mock(BinaryObject.class);
        when(binObj2.getPid()).thenReturn(DatastreamPids.getTechnicalMetadataPid(pid));
        when(binObj2.getResource()).thenReturn(
                fileResource(TECHNICAL_METADATA.getId(), FILE2_SIZE, FILE2_MIMETYPE, FILE2_NAME, FILE2_DIGEST));
        when(binObj2.getBinaryStream()).thenReturn(getClass().getResourceAsStream("/datastream/techmd.xml"));

        BinaryObject binObj3 = mock(BinaryObject.class);
        when(binObj3.getPid()).thenReturn(PIDs.get(pid.getId() + "/" + JP2_ACCESS_COPY.getId()));
        when(binObj3.getResource()).thenReturn(
                fileResource(THUMBNAIL_LARGE.getId(), FILE3_SIZE, JP2_ACCESS_COPY.getMimetype(),
                        JP2_ACCESS_COPY.getDefaultFilename(), FILE3_DIGEST));

        BinaryObject binObj4 = mock(BinaryObject.class);
        when(binObj4.getPid()).thenReturn(PIDs.get(pid.getId() + "/" + THUMBNAIL_LARGE.getId()));
        when(binObj4.getResource()).thenReturn(
                fileResource(THUMBNAIL_LARGE.getId(), FILE3_SIZE, FILE3_MIMETYPE, FILE3_NAME, FILE3_DIGEST));

        when(fileObj.getBinaryObjects()).thenReturn(Arrays.asList(binObj, binObj2, binObj3, binObj4));
        when(dip.getContentObject()).thenReturn(fileObj);

        filter.filter(dip);

        verify(idb).setDatastream(listCaptor.capture());
        assertContainsDatastream(listCaptor.getValue(), ORIGINAL_FILE.getId(),
                FILE_SIZE, FILE3_MIMETYPE, "test.png", FILE_DIGEST, null, FILE3_EXTENT);
        assertContainsDatastream(listCaptor.getValue(), TECHNICAL_METADATA.getId(),
                FILE2_SIZE, FILE2_MIMETYPE, FILE2_NAME, FILE2_DIGEST, null, null);
        assertContainsDatastream(listCaptor.getValue(), THUMBNAIL_LARGE.getId(),
                FILE3_SIZE, FILE3_MIMETYPE, FILE3_NAME, FILE3_DIGEST, null, null);

        verify(idb).setFilesizeSort(eq(FILE_SIZE));
        // JP2 and thumbnail set to same size
        verify(idb).setFilesizeTotal(eq(FILE_SIZE + FILE2_SIZE + (FILE3_SIZE * 2)));
    }

    @Test(expected = IndexingException.class)
    public void fileObjectNoOriginalTest() throws Exception {
        when(binObj.getResource()).thenReturn(
                fileResource(TECHNICAL_METADATA.getId(), FILE2_SIZE, FILE2_MIMETYPE, FILE2_NAME, FILE2_DIGEST));

        when(fileObj.getBinaryObjects()).thenReturn(Arrays.asList(binObj));
        when(dip.getContentObject()).thenReturn(fileObj);

        filter.filter(dip);
    }

    @Test
    public void fileObjectWithMetadataTest() throws Exception {
        when(dip.getContentObject()).thenReturn(fileObj);
        addMetadataDatastreams(fileObj);

        filter.filter(dip);

        verify(idb).setDatastream(listCaptor.capture());
        assertContainsDatastream(listCaptor.getValue(), ORIGINAL_FILE.getId(),
                FILE_SIZE, FILE_MIMETYPE, FILE_NAME, FILE_DIGEST, null, null);
        assertContainsMetadataDatastreams(listCaptor.getValue());

        verify(idb).setFilesizeSort(eq(FILE_SIZE));
        verify(idb).setFilesizeTotal(eq(FILE_SIZE + FILE2_SIZE + MODS_SIZE + PREMIS_SIZE));
    }

    @Test
    public void workObjectTest() throws Exception {
        WorkObject workObj = mock(WorkObject.class);
        when(workObj.getPrimaryObject()).thenReturn(fileObj);
        when(workObj.getPid()).thenReturn(pid);
        addMetadataDatastreams(workObj);

        when(dip.getContentObject()).thenReturn(workObj);

        String fileId = "055ed112-f548-479e-ab4b-bf1aad40d470";
        PID filePid = PIDs.get(fileId);
        when(fileObj.getPid()).thenReturn(filePid);
        when(binObj.getPid()).thenReturn(getOriginalFilePid(filePid));

        filter.filter(dip);

        verify(idb).setDatastream(listCaptor.capture());
        assertContainsDatastream(listCaptor.getValue(), ORIGINAL_FILE.getId(),
                FILE_SIZE, FILE_MIMETYPE, FILE_NAME, FILE_DIGEST, fileId, null);
        assertContainsMetadataDatastreams(listCaptor.getValue());

        // Sort size is based off primary object's size
        verify(idb).setFilesizeSort(eq(FILE_SIZE));
        // Work has no datastreams of its own
        verify(idb).setFilesizeTotal(eq(FILE2_SIZE + MODS_SIZE + PREMIS_SIZE));
    }

    @Test
    public void workObjectWithoutPrimaryObjectTest() throws Exception {
        WorkObject workObj = mock(WorkObject.class);

        when(dip.getContentObject()).thenReturn(workObj);

        filter.filter(dip);

        verify(idb).setDatastream(anyListOf(String.class));
        verify(idb, never()).setFilesizeSort(anyLong());
        verify(idb).setFilesizeTotal(anyLong());
    }

    @Test
    public void folderObjectWithMetadataTest() throws Exception {
        FolderObject folderObj = mock(FolderObject.class);
        addMetadataDatastreams(folderObj);

        when(dip.getContentObject()).thenReturn(folderObj);

        filter.filter(dip);

        verify(idb).setDatastream(listCaptor.capture());
        assertContainsMetadataDatastreams(listCaptor.getValue());
        verify(idb, never()).setFilesizeSort(anyLong());
        verify(idb).setFilesizeTotal(FILE2_SIZE + MODS_SIZE + PREMIS_SIZE);
    }

    @Test
    public void fileObjectWithDerivativeTest() throws Exception {
        when(fileObj.getPid()).thenReturn(pid);
        when(fileObj.getBinaryObjects()).thenReturn(Arrays.asList(binObj));
        when(dip.getContentObject()).thenReturn(fileObj);

        File derivFile = derivDir.newFile("deriv.png");
        FileUtils.write(derivFile, "content", "UTF-8");
        long derivSize = 7l;

        List<Derivative> derivs = Arrays.asList(new Derivative(THUMBNAIL_SMALL, derivFile));
        when(derivativeService.getDerivatives(pid)).thenReturn(derivs);

        filter.filter(dip);

        verify(idb).setDatastream(listCaptor.capture());
        assertContainsDatastream(listCaptor.getValue(), ORIGINAL_FILE.getId(),
                FILE_SIZE, FILE_MIMETYPE, FILE_NAME, FILE_DIGEST, null, null);
        assertContainsDatastream(listCaptor.getValue(), THUMBNAIL_SMALL.getId(),
                derivSize, THUMBNAIL_SMALL.getMimetype(), derivFile.getName(), null, null, null);

        verify(idb).setFilesizeSort(eq(FILE_SIZE));
        verify(idb).setFilesizeTotal(eq(FILE_SIZE + derivSize));
    }

    @Test
    public void fileObjectNoDetailsTest() throws Exception {
        when(dip.getContentObject()).thenReturn(fileObj);

        Model model = ModelFactory.createDefaultModel();
        when(binObj.getResource()).thenReturn(model.getResource(BASE_URI + ORIGINAL_FILE.getId()));

        filter.filter(dip);

        verify(idb).setDatastream(listCaptor.capture());

        assertTrue("Did not contain datastream", listCaptor.getValue().contains(ORIGINAL_FILE.getId() + "|||||||"));
        verify(idb).setFilesizeSort(eq(0l));
        verify(idb).setFilesizeTotal(eq(0l));
    }

    private Resource fileResource(String name, long filesize, String mimetype, String filename, String digest) {
        Model model = ModelFactory.createDefaultModel();
        Resource resc = model.getResource(BASE_URI + name);
        resc.addLiteral(Premis.hasSize, filesize);
        resc.addLiteral(Ebucore.hasMimeType, mimetype);
        resc.addLiteral(Ebucore.filename, filename);
        resc.addProperty(Premis.hasMessageDigest, createResource(digest));

        return resc;
    }

    private void assertContainsDatastream(List<String> values, String name, long filesize, String mimetype,
                                          String filename, String digest, String owner, String extent) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1);
        List<Object> components = Arrays.asList(
                name, mimetype, filename, extension, filesize, digest, owner, extent);
        String joined = components.stream()
                .map(c -> c == null ? "" : c.toString())
                .collect(Collectors.joining("|"));
        System.out.println("Values: " + values);
        System.out.println("Seeking: " + joined);
        assertTrue("Did not contain datastream " + name, values.contains(joined));
    }

    private void addMetadataDatastreams(ContentObject obj) throws Exception {
        BinaryObject fitsBin = mock(BinaryObject.class);
        when(fitsBin.getPid()).thenReturn(DatastreamPids.getTechnicalMetadataPid(pid));
        when(fitsBin.getResource()).thenReturn(
                fileResource(TECHNICAL_METADATA.getId(), FILE2_SIZE, FILE2_MIMETYPE, FILE2_NAME, FILE2_DIGEST));
        when(fitsBin.getBinaryStream()).thenReturn(getClass().getResourceAsStream("/datastream/techmd.xml"));

        BinaryObject modsBin = mock(BinaryObject.class);
        when(modsBin.getResource()).thenReturn(
                fileResource(DatastreamType.MD_DESCRIPTIVE.getId(),
                        MODS_SIZE, MODS_MIMETYPE, MODS_NAME, MODS_DIGEST));
        BinaryObject premisBin = mock(BinaryObject.class);
        when(premisBin.getResource()).thenReturn(
                fileResource(DatastreamType.MD_EVENTS.getId(),
                        PREMIS_SIZE, PREMIS_MIMETYPE, PREMIS_NAME, PREMIS_DIGEST));
        List<BinaryObject> mdBins = Arrays.asList(fitsBin, premisBin, modsBin);

        when(obj.listMetadata()).thenReturn(mdBins);
    }

    private void assertContainsMetadataDatastreams(List<String> values) {
        assertContainsDatastream(values, DatastreamType.MD_DESCRIPTIVE.getId(),
                        MODS_SIZE, MODS_MIMETYPE, MODS_NAME, MODS_DIGEST, null, null);
        assertContainsDatastream(values, DatastreamType.MD_EVENTS.getId(),
                PREMIS_SIZE, PREMIS_MIMETYPE, PREMIS_NAME, PREMIS_DIGEST, null, null);
    }
}
