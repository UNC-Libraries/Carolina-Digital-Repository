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
package edu.unc.lib.dl.services.camel.cdrEvents;

import static edu.unc.lib.dl.util.IndexingActionType.ADD_SET_TO_PARENT;
import static edu.unc.lib.dl.util.IndexingActionType.UPDATE_ACCESS_TREE;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.NotifyBuilder;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dl.data.ingest.solr.ChildSetRequest;
import edu.unc.lib.dl.data.ingest.solr.SolrUpdateRequest;
import edu.unc.lib.dl.data.ingest.solr.action.IndexingAction;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.services.OperationsMessageSender;
import edu.unc.lib.dl.services.camel.BinaryMetadataProcessor;
import edu.unc.lib.dl.services.camel.solrUpdate.SolrUpdateProcessor;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.IndexingActionType;

/**
 *
 * @author bbpennel
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml"),
    @ContextConfiguration("/spring-test/jms-context.xml"),
    @ContextConfiguration("/cdr-event-routing-it-context.xml")
})
public class CdrEventRoutingIT {

    private static final String USER_ID = "user";
    private static final String DEPOSIT_ID = "deposit";

    @Autowired
    private String baseAddress;

    @Autowired
    private OperationsMessageSender opsMsgSender;

    @Autowired
    private SolrUpdateProcessor solrUpdateProcessor;

    @Autowired
    private RepositoryObjectFactory repoObjFactory;

    @Autowired
    private CamelContext cdrServiceSolrUpdate;

    @Autowired
    private CamelContext cdrEnhancementRoute;

    @Mock
    private Map<IndexingActionType, IndexingAction> mockActionMap;
    @Mock
    private IndexingAction mockIndexingAction;
    @Captor
    private ArgumentCaptor<SolrUpdateRequest> updateRequestCaptor;

    @Autowired
    protected RepositoryObjectTreeIndexer treeIndexer;

    @Autowired
    protected BinaryMetadataProcessor mdProcessor;

    @Before
    public void init() throws Exception {
        initMocks(this);

        TestHelper.setContentBase(baseAddress);

        solrUpdateProcessor.setSolrIndexingActionMap(mockActionMap);

        when(mockActionMap.get(any(IndexingActionType.class)))
                .thenReturn(mockIndexingAction);
    }

    @Test
    public void testAddAction() throws Exception {
        List<PID> destinations = pidList(1);

        WorkObject newWork = repoObjFactory.createWorkObject(null);
        Path originalPath = Files.createTempFile("file", ".png");
        FileUtils.writeStringToFile(originalPath.toFile(), "stuff", "UTF-8");
        newWork.addDataFile(originalPath.toUri(),
                null, "image/png", null, null);

        treeIndexer.indexAll(baseAddress);

        List<PID> added = asList(newWork.getPid());

        opsMsgSender.sendAddOperation(USER_ID, destinations, added,
                emptyList(), DEPOSIT_ID);

        NotifyBuilder notify = new NotifyBuilder(cdrServiceSolrUpdate)
                .whenCompleted(1)
                .create();

        NotifyBuilder notify2 = new NotifyBuilder(cdrEnhancementRoute)
                .whenCompleted(2)
                .create();

        assertTrue(notify.matches(5l, TimeUnit.SECONDS));

        verify(mockIndexingAction).performAction(updateRequestCaptor.capture());

        ChildSetRequest updateRequest = (ChildSetRequest) updateRequestCaptor.getValue();
        assertEquals(ADD_SET_TO_PARENT, updateRequest.getUpdateAction());

        assertTrue(updateRequest.getChildren().containsAll(added));

        // Verify that enhancement routes were triggered
        assertTrue(notify2.matches(5l, TimeUnit.SECONDS));
        verify(mdProcessor).process(any(Exchange.class));
    }

    @Test
    public void testEditAccessControlAction() throws Exception {
        int numPids = 3;
        List<PID> pids = pidList(numPids);

        opsMsgSender.sendMarkForDeletionOperation(USER_ID, pids);

        NotifyBuilder notify = new NotifyBuilder(cdrServiceSolrUpdate)
                .whenCompleted(1)
                .create();

        notify.matches(3l, TimeUnit.SECONDS);

        verify(mockIndexingAction).performAction(updateRequestCaptor.capture());

        ChildSetRequest updateRequest = (ChildSetRequest) updateRequestCaptor.getValue();
        assertEquals(UPDATE_ACCESS_TREE, updateRequest.getUpdateAction());

        assertTrue(updateRequest.getChildren().containsAll(pids));
    }

    private List<PID> pidList(int count) {
        List<PID> pidList = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            pidList.add(PIDs.get(UUID.randomUUID().toString()));
        }
        return pidList;
    }
}
