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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.IOException;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoOperationFailedException;
import org.fcrepo.client.FcrepoResponse;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.DcElements;
import edu.unc.lib.dl.rdf.PcdmModels;

/**
 *
 * @author harring
 *
 */
public class FedoraTransactionIT extends AbstractFedoraIT {

    private Model model;
    @Mock
    private BinaryTransferService binaryTransferService;

    @Before
    public void init() {
        initMocks(this);
        txManager.setBinaryTransferService(binaryTransferService);
        model = ModelFactory.createDefaultModel();
        Resource resc = model.createResource("");
        resc.addProperty(DcElements.title, "Folder Title");
    }

    @Test
    public void createTxTest() throws Exception {
        FedoraTransaction tx = txManager.startTransaction();

        FolderObject obj = null;
        try {
            obj = repoObjFactory.createFolderObject(model);

            assertTrue(FedoraTransaction.hasTxId());
            assertTrue(obj.getTypes().contains(Cdr.Folder.getURI()));
            assertTrue(obj.getTypes().contains(PcdmModels.Object.getURI()));
            assertEquals("Folder Title", obj.getResource()
                    .getProperty(DcElements.title).getString());
        } finally {
            tx.close();
        }

        assertFalse(FedoraTransaction.hasTxId());
        assertNull(tx.getTxUri());
        verifyNonTxStatusCode(obj.getPid(), 200);
    }

    @Test (expected = TransactionCancelledException.class)
    public void createRollbackTxTest() {
        FedoraTransaction tx = txManager.startTransaction();
        FolderObject obj = repoObjFactory.createFolderObject(model);
        tx.cancel();
        assertNull(repoObjLoader.getFolderObject(obj.getPid()));
        assertNull(obj.getUri());
        assertFalse(FedoraTransaction.hasTxId());
        assertFalse(FedoraTransaction.isStillAlive());
    }

    @Test
    public void nestedTxTest() throws Exception {
        FedoraTransaction parentTx = txManager.startTransaction();
        repoObjFactory.createFolderObject(model);

        FedoraTransaction subTx = txManager.startTransaction();
        WorkObject workObj = repoObjFactory.createWorkObject(null);
        subTx.close();

        assertNull(subTx.getTxUri());
        verifyNonTxStatusCode(workObj.getPid(), 404);
        assertNotNull((parentTx.getTxUri()));
        assertTrue(FedoraTransaction.isStillAlive());

        parentTx.close();
        assertNull(parentTx.getTxUri());
        assertFalse(FedoraTransaction.isStillAlive());
        verifyNonTxStatusCode(workObj.getPid(), 200);
    }

    @Test
    public void cannotAccessObjectOutsideTxTest() throws Exception {
        FedoraTransaction tx = txManager.startTransaction();
        FolderObject folder = repoObjFactory.createFolderObject(null);

        verifyNonTxStatusCode(folder.getPid(), 404);
        tx.close();
    }

    private void verifyNonTxStatusCode(PID pid, int statusCode) {
        FcrepoClient nonTxClient = FcrepoClient.client().build();
        try (FcrepoResponse response = nonTxClient.get(pid.getRepositoryUri()).perform()) {
            assertEquals(statusCode, response.getStatusCode());
        } catch (FcrepoOperationFailedException | IOException e) {
            fail();
        }
    }

}
