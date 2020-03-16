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

import edu.unc.lib.dl.test.TestHelper;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.jdom2.Document;
import org.jdom2.Element;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import static edu.unc.lib.dl.rdf.Fcrepo4Repository.Binary;
import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrBinaryMimeType;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.ATOM_NS;
import static org.fcrepo.camel.FcrepoHeaders.FCREPO_URI;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 *
 * @author lfarrell
 *
 */
public class BinaryEnhancementProcessorTest {
    private BinaryEnhancementProcessor processor;

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    private static final String FEDORA_BASE = "http://example.com/rest/";

    private static final String RESC_ID = "de75d811-9e0f-4b1f-8631-2060ab3580cc";
    private static final String RESC_URI = FEDORA_BASE + "content/de/75/d8/11/" + RESC_ID + "/original_file";

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Before
    public void init() throws Exception {
        initMocks(this);

        TestHelper.setContentBase(FEDORA_BASE);

        processor = new BinaryEnhancementProcessor();

        when(exchange.getIn()).thenReturn(message);
        when(exchange.getIn().getHeader(FCREPO_URI)).thenReturn(null);
    }

    @Test
    public void testUpdateHeadersText() throws Exception {
        setMessageBody("text/plain");

        processor.process(exchange);

        verify(message).setHeader(FCREPO_URI, RESC_URI);
        verify(message).setHeader(CdrBinaryMimeType, "text/plain");
        verify(message).setHeader("org.fcrepo.jms.resourceType", Binary.getURI());
    }

    @Test
    public void testUpdateHeadersImage() throws Exception {
        setMessageBody("image/png");

        processor.process(exchange);

        verify(message).setHeader(FCREPO_URI, RESC_URI);
        verify(message).setHeader(CdrBinaryMimeType, "image/png");
        verify(message).setHeader("org.fcrepo.jms.resourceType", Binary.getURI());
    }

    @Test
    public void testExistingUriHeader() throws Exception {
        when(exchange.getIn().getHeader(FCREPO_URI)).thenReturn(RESC_URI);
        setMessageBody("image/png");

        processor.process(exchange);

        verify(message, never()).setHeader(FCREPO_URI, RESC_URI);
        verify(message, never()).setHeader(CdrBinaryMimeType, "image/png");
        verify(message, never()).setHeader("org.fcrepo.jms.resourceType", Binary.getURI());
    }

    private void setMessageBody(String mimeType) {
        Document msg = new Document();
        Element entry = new Element("entry", ATOM_NS);
        entry.addContent(new Element("pid", ATOM_NS).setText(RESC_URI));
        entry.addContent(new Element("mimeType", ATOM_NS).setText(mimeType));

        msg.addContent(entry);

        when(message.getBody()).thenReturn(msg);
    }
}