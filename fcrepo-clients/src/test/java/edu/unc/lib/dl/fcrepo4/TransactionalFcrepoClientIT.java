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
package edu.unc.lib.dl.fcrepo4;

import static edu.unc.lib.dl.util.RDFModelUtil.createModel;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.fcrepo.client.FedoraTypes.LDP_NON_RDF_SOURCE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

import org.apache.http.HttpStatus;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DC;
import org.apache.jena.vocabulary.RDF;
import org.apache.tika.io.IOUtils;
import org.fcrepo.client.FcrepoResponse;
import org.fusesource.hawtbuf.ByteArrayInputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.RDFModelUtil;

/**
 * @author bbpennel
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration({"/spring-test/test-fedora-container.xml"})
public class TransactionalFcrepoClientIT {

    private static final String BASE_PATH = "http://localhost:48085/rest";
    private static final URI BASE_URI = URI.create(BASE_PATH);

    private TransactionalFcrepoClient fcrepoClient;

    private TransactionManager txManager;

    @Before
    public void setup() {
        TestHelper.setContentBase(BASE_PATH);

        BinaryTransferService bts = mock(BinaryTransferService.class);
        txManager = new TransactionManager();
        txManager.setBinaryTransferService(bts);
    }

    @Test
    public void testRequestWithTx() throws Exception {
        fcrepoClient = TransactionalFcrepoClient.client(BASE_PATH)
                .build();

        txManager.setClient(fcrepoClient);

        // Create object in transaction, should return transaction location
        FedoraTransaction tx = txManager.startTransaction();
        URI objUri;
        try (FcrepoResponse response = fcrepoClient.post(BASE_URI).perform()) {
            objUri = response.getLocation();
        }

        assertTrue("Location must be within transaction",
                objUri.toString().startsWith(tx.getTxUri().toString()));

        // Verify that the response triples have non-tx uris
        try (FcrepoResponse response = fcrepoClient.get(objUri).perform()) {
            // Remove tx from obj uri
            String nonTxObjUri = objUri.toString().replaceFirst("/tx:[^/]+", "");

            Model model = createModel(response.getBody());

            Resource resc = model.getResource(nonTxObjUri);
            assertTrue("Subject must be non-tx uri", resc.hasProperty(RDF.type));
        } finally {
            tx.close();
        }
    }

    @Test
    public void testPutTriplesWithTx() throws Exception {
        fcrepoClient = TransactionalFcrepoClient.client(BASE_PATH)
                .build();

        txManager.setClient(fcrepoClient);

        PID pid = PIDs.get(UUID.randomUUID().toString());

        Model model = ModelFactory.createDefaultModel();
        Resource resc = model.getResource(pid.getRepositoryPath());
        resc.addLiteral(DC.title, "My Title");

        FedoraTransaction tx = txManager.startTransaction();
        createAndVerifyObjectWithBody(pid.getRepositoryUri(), model);
        try {
            tx.cancel();
        } catch (TransactionCancelledException e) {
            // Expected
        }
        // Verify that the created resource went away with transaction
        try (FcrepoResponse response = fcrepoClient.get(pid.getRepositoryUri()).perform()) {
            assertEquals(HttpStatus.SC_NOT_FOUND, response.getStatusCode());
        }

        FedoraTransaction tx2 = txManager.startTransaction();
        createAndVerifyObjectWithBody(pid.getRepositoryUri(), model);
        tx2.close();

        try (FcrepoResponse response = fcrepoClient.get(pid.getRepositoryUri()).perform()) {
            assertEquals(HttpStatus.SC_OK, response.getStatusCode());
        }
    }

    @Test
    public void testBinaryRequestWithTx() throws Exception {
        fcrepoClient = TransactionalFcrepoClient.client(BASE_PATH)
                .build();

        txManager.setClient(fcrepoClient);

        PID bodyPid = PIDs.get(UUID.randomUUID().toString());
        String requestBody = "<" + bodyPid.getRepositoryPath() + "> <" + RDF.type.getURI() + "> \"Test\"";
        InputStream requestStream = new ByteArrayInputStream(requestBody.getBytes());

        // Create object in transaction, should return transaction location
        FedoraTransaction tx = txManager.startTransaction();
        URI objUri;
        try (FcrepoResponse response = fcrepoClient.post(BASE_URI)
                .body(requestStream, "application/n-triples")
                .addInteractionModel(LDP_NON_RDF_SOURCE)
                .perform()) {
            objUri = response.getLocation();
        }

        assertTrue("Location must be within transaction",
                objUri.toString().startsWith(tx.getTxUri().toString()));

        tx.close();

        String nonTxObjPath = objUri.toString().replaceFirst("/tx:[^/]+", "");
        URI nonTxObjUri = URI.create(nonTxObjPath);
        // Verify that the binary content was not rewritten with tx uris
        try (FcrepoResponse response = fcrepoClient.get(nonTxObjUri).perform()) {
            String respBody = IOUtils.toString(response.getBody());
            assertTrue(respBody.contains(bodyPid.getRepositoryPath()));
        }
    }

    private URI createAndVerifyObjectWithBody(URI path, Model model) throws Exception {
        InputStream rdfStream = RDFModelUtil.streamModel(model);

        URI objUri;
        try (FcrepoResponse response = fcrepoClient.put(path).body(rdfStream).perform()) {
            assertEquals(HttpStatus.SC_CREATED, response.getStatusCode());
            objUri = response.getLocation();
        }

        try (FcrepoResponse response = fcrepoClient.get(objUri).perform()) {
            assertEquals(HttpStatus.SC_OK, response.getStatusCode());
            Model respModel = RDFModelUtil.createModel(response.getBody());
            respModel.contains(createResource(path.toString()), DC.title, "My Title");
        }

        return objUri;
    }
}
