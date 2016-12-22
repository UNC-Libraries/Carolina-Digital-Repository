/**
 * Copyright 2016 The University of North Carolina at Chapel Hill
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
package edu.unc.lib.cdr;

import static com.hp.hpl.jena.rdf.model.ResourceFactory.createResource;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.vocabulary.RDF;

import edu.unc.lib.dl.rdf.Ebucore;
import edu.unc.lib.dl.rdf.Fcrepo4Repository;
import edu.unc.lib.dl.rdf.Premis;

/**
 * 
 * @author bbpennel
 *
 */
public class BinaryMetadataProcessorTest {

	private BinaryMetadataProcessor processor;

	private static final String FEDORA_BASE = "http://example.com/";

	private static final String BINARY_BASE = "/binary/base/";

	private static final String RESC_ID = FEDORA_BASE + "de75d811-9e0f-4b1f-8631-2060ab3580cc";

	@Mock
	private Exchange exchange;
	@Mock
	private Message message;

	@Before
	public void init() throws Exception {
		initMocks(this);

		processor = new BinaryMetadataProcessor(BINARY_BASE);

		when(exchange.getIn()).thenReturn(message);
	}

	@Test
	public void validTest() throws Exception {
		String mimetype = "text/plain";
		String checksumPrefix = "urn:sha1:";
		String checksum = "61673dacf6c6eea104e77b151584ed7215388ea3";

		Model model = ModelFactory.createDefaultModel();

		Resource resc = model.createResource(RESC_ID);
		resc.addProperty(RDF.type, Fcrepo4Repository.Binary);
		resc.addProperty(Ebucore.hasMimeType, mimetype);
		resc.addProperty(Premis.hasMessageDigest, checksumPrefix + checksum);

		setMessageBody(model);

		processor.process(exchange);

		verify(message).setHeader("Checksum", checksum);
		verify(message).setHeader("MimeType", mimetype);
		verify(message).setHeader("BinaryPath", BINARY_BASE + "61/67/3d/" + checksum);
	}

	@Test
	public void nonbinaryTest() throws Exception {
		Model model = ModelFactory.createDefaultModel();
		Resource resc = model.createResource(RESC_ID);
		resc.addProperty(RDF.type, createResource(Fcrepo4Repository.Resource.getURI()));

		setMessageBody(model);

		processor.process(exchange);

		verify(message, never()).setHeader(anyString(), anyString());
	}

	private void setMessageBody(Model model) throws Exception {
		try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
			RDFDataMgr.write(bos, model, RDFFormat.TURTLE_PRETTY);
			when(message.getBody(eq(InputStream.class)))
					.thenReturn(new ByteArrayInputStream(bos.toByteArray()));
		}
	}
}
