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

import static edu.unc.lib.dl.test.TestHelpers.setField;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.apache.http.Header;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.fcrepo.client.FcrepoClient.FcrepoClientBuilder;
import org.fcrepo.client.FcrepoResponse;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;

/**
 *
 * @author harring
 *
 */
public class TransactionalFcrepoClientTest extends AbstractFedoraTest {

    private static final String BASE_URI = "http://localhost:48085/rest/";
    private static final String TX_URI = "http://localhost:48085/rest/tx:99b58d30-06f5-477b-a44c-d614a9049d38";
    private static final String RESC_URI = "http://localhost:48085/rest/some/resource/id";
    private static final String REQUEST_URI =
            "http://localhost:48085/rest/tx:99b58d30-06f5-477b-a44c-d614a9049d38/some/resource/id";

    private TransactionalFcrepoClient txClient;
    private FedoraTransaction tx;
    private TransactionManager txManager;

    @Mock
    private HttpRequestBase request;
    @Mock
    private CloseableHttpClient httpClient;
    @Mock
    private StatusLine statusLine;
    @Mock
    private CloseableHttpResponse httpResponse;
    @Mock
    private Header header;
    @Mock
    private BinaryTransferService binaryTransferService;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        URI uri = URI.create(TX_URI);
        FcrepoClientBuilder builder = TransactionalFcrepoClient.client(BASE_URI);
        txClient = (TransactionalFcrepoClient) builder.build();
        txManager= new TransactionManager();
        txManager.setClient(txClient);
        txManager.setBinaryTransferService(binaryTransferService);
        tx = new FedoraTransaction(uri, txManager);

        setField(txClient, "httpclient", httpClient);

        when(httpClient.execute(any(HttpRequestBase.class))).thenReturn(httpResponse);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_NO_CONTENT);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(header.getName()).thenReturn("Location");
        when(header.getValue())
            .thenReturn(REQUEST_URI);
        when(httpResponse.getAllHeaders()).thenReturn(new Header[]{header});
        when(request.getMethod()).thenReturn("GET");
    }

    @Test
    public void executeRequestWithTxTest() throws Exception {
        URI  rescUri = URI.create(RESC_URI);
        assertFalse(rescUri.toString().contains("tx:"));
        assertNotEquals(rescUri.toString(), REQUEST_URI);

        try (FcrepoResponse response = txClient.executeRequest(rescUri, request)) {
            rescUri = response.getLocation();
        } finally {
            tx.close();
        }

        assertTrue(rescUri.toString().contains("tx:"));
        assertEquals(rescUri.toString(), REQUEST_URI);
    }

}
