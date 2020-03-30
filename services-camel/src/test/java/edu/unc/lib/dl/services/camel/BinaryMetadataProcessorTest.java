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
package edu.unc.lib.dl.services.camel;

import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrBinaryMimeType;
import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrBinaryPath;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.fcrepo.camel.FcrepoHeaders.FCREPO_URI;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.File;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.ObjectTypeMismatchException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.rdf.Ebucore;
import edu.unc.lib.dl.rdf.Fcrepo4Repository;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.test.TestHelper;

/**
 *
 * @author bbpennel
 *
 */
public class BinaryMetadataProcessorTest {

    private BinaryMetadataProcessor processor;

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    private static final String FEDORA_BASE = "http://example.com/rest/";

    private String binaryBase;

    private static final String RESC_ID = "de75d811-9e0f-4b1f-8631-2060ab3580cc";
    private static final String RESC_URI = FEDORA_BASE + "content/de/75/d8/11/" + RESC_ID;
    private static final String MIMETYPE = "text/plain";
    private static final String CHECKSUM_PREFIX = "urn:sha1:";
    private static final String CHECKSUM = "61673dacf6c6eea104e77b151584ed7215388ea3";

    private PID binaryPid;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private RepositoryObjectLoader repoObjLoader;
    @Mock
    private BinaryObject binaryObject;

    @Before
    public void init() throws Exception {
        initMocks(this);

        TestHelper.setContentBase(FEDORA_BASE);

        binaryBase = tmpFolder.newFolder().getAbsolutePath();

        processor = new BinaryMetadataProcessor();
        processor.setRepositoryObjectLoader(repoObjLoader);

        when(exchange.getIn()).thenReturn(message);
        when(exchange.getIn().getHeader(FCREPO_URI)).thenReturn(RESC_ID);

        binaryPid = PIDs.get(RESC_ID);
    }

    @Test
    public void validExternalBinary() throws Exception {
        File file = new File(binaryBase + "/61/67/3d/" + CHECKSUM);
        file.getParentFile().mkdirs();
        file.createNewFile();

        when(repoObjLoader.getBinaryObject(binaryPid)).thenReturn(binaryObject);
        when(binaryObject.getContentUri()).thenReturn(file.toPath().toUri());

        Model model = ModelFactory.createDefaultModel();
        Resource resc = model.createResource(RESC_URI);
        resc.addProperty(RDF.type, Fcrepo4Repository.Binary);
        resc.addProperty(Ebucore.hasMimeType, MIMETYPE);
        resc.addProperty(Premis.hasMessageDigest, CHECKSUM_PREFIX + CHECKSUM);

        when(binaryObject.getResource()).thenReturn(resc);

        processor.process(exchange);

        verify(message).setHeader(CdrBinaryMimeType, MIMETYPE);
        verify(message).setHeader(CdrBinaryPath, file.toPath().toString());
    }

    @Test
    public void nonbinaryObject() throws Exception {
        when(repoObjLoader.getBinaryObject(binaryPid)).thenThrow(new ObjectTypeMismatchException(""));

        Model model = ModelFactory.createDefaultModel();
        Resource resc = model.createResource(RESC_ID);
        resc.addProperty(RDF.type, createResource(Fcrepo4Repository.Resource.getURI()));

        when(binaryObject.getResource()).thenReturn(resc);

        processor.process(exchange);

        verify(message, never()).setHeader(eq(CdrBinaryPath), any());
    }

    @Test
    public void internalBinary() throws Exception {
        when(repoObjLoader.getBinaryObject(binaryPid)).thenReturn(binaryObject);
        when(binaryObject.getContentUri()).thenReturn(null);

        Model model = ModelFactory.createDefaultModel();
        Resource resc = model.createResource(RESC_URI);
        resc.addProperty(RDF.type, Fcrepo4Repository.Binary);
        resc.addProperty(Ebucore.hasMimeType, MIMETYPE);
        resc.addProperty(Premis.hasMessageDigest, CHECKSUM_PREFIX + CHECKSUM);

        when(binaryObject.getResource()).thenReturn(resc);

        processor.process(exchange);

        verify(message, never()).setHeader(eq(CdrBinaryPath), any());
    }
}
