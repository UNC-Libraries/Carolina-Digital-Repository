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
package edu.unc.lib.dl.data.ingest.solr.action;

import static edu.unc.lib.dl.data.ingest.solr.test.MockRepositoryObjectHelpers.addContainerToParent;
import static edu.unc.lib.dl.data.ingest.solr.test.MockRepositoryObjectHelpers.addFileObjectToParent;
import static edu.unc.lib.dl.data.ingest.solr.test.MockRepositoryObjectHelpers.makeContainer;
import static edu.unc.lib.dl.util.IndexingActionType.RECURSIVE_ADD;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import edu.unc.lib.dl.data.ingest.solr.SolrUpdateRequest;
import edu.unc.lib.dl.data.ingest.solr.test.TestCorpus;
import edu.unc.lib.dl.fcrepo4.ContentContainerObject;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.services.IndexingMessageSender;
import edu.unc.lib.dl.util.IndexingActionType;

/**
 *
 * @author bbpennel
 *
 */
public class UpdateTreeActionTest {
    protected static final String USER = "user";

    protected TestCorpus corpus;

    @Mock
    private RepositoryObjectLoader repositoryObjectLoader;

    protected UpdateTreeAction action;

    protected RecursiveTreeIndexer treeIndexer;

    @Mock
    protected IndexingMessageSender messageSender;

    @Captor
    protected ArgumentCaptor<PID> pidCaptor;

    @Before
    public void setupTreeAction() throws Exception {
        initMocks(this);

        corpus = new TestCorpus();

        // Establish basic containment relations
        ContentContainerObject obj1 = makeContainer(corpus.pid1, repositoryObjectLoader);
        ContentContainerObject obj2 = addContainerToParent(obj1, corpus.pid2, repositoryObjectLoader);
        addFileObjectToParent(obj1, corpus.pid3, repositoryObjectLoader);
        addFileObjectToParent(obj2, corpus.pid4, repositoryObjectLoader);
        addFileObjectToParent(obj2, corpus.pid6, repositoryObjectLoader);

        treeIndexer = new RecursiveTreeIndexer();
        treeIndexer.setMessageSender(messageSender);

        action = getAction();
        action.setRepositoryObjectLoader(repositoryObjectLoader);
        action.setTreeIndexer(treeIndexer);
        action.setActionType(IndexingActionType.ADD.name());
    }

    protected UpdateTreeAction getAction() {
        return new UpdateTreeAction();
    }

    @Test
    public void testVerifyQueued() throws Exception {
        action.performAction(new SolrUpdateRequest(corpus.pid2.getRepositoryPath(),
                RECURSIVE_ADD, "1", USER));

        verify(messageSender, times(3)).sendIndexingOperation(eq(USER), pidCaptor.capture(),
                eq(IndexingActionType.ADD));

        List<PID> pids = pidCaptor.getAllValues();
        assertTrue(pids.contains(corpus.pid2));
        assertTrue(pids.contains(corpus.pid4));
        assertTrue(pids.contains(corpus.pid6));
    }

    @Test
    public void testDanglingContains() throws Exception {
        // Add containment of non-existent object
        ContentContainerObject obj4 = makeContainer(corpus.pid4, repositoryObjectLoader);
        addFileObjectToParent(obj4, corpus.nonExistentPid, repositoryObjectLoader);

        action.performAction(new SolrUpdateRequest(corpus.pid2.getRepositoryPath(),
                RECURSIVE_ADD, "1", USER));

        verify(messageSender, times(3)).sendIndexingOperation(eq(USER), pidCaptor.capture(),
                eq(IndexingActionType.ADD));

        List<PID> pids = pidCaptor.getAllValues();
        assertTrue(pids.contains(corpus.pid2));
        assertTrue(pids.contains(corpus.pid4));
        assertTrue(pids.contains(corpus.pid6));
    }

    @Test
    public void testNoDescendents() throws Exception {
        ContentObject obj6 = mock(ContentObject.class);
        when(obj6.getPid()).thenReturn(corpus.pid6);
        when(repositoryObjectLoader.getRepositoryObject(eq(corpus.pid6))).thenReturn(obj6);

        action.performAction(new SolrUpdateRequest(corpus.pid6.getRepositoryPath(), IndexingActionType.RECURSIVE_ADD));

        verify(messageSender).sendIndexingOperation(eq(null),
                pidCaptor.capture(), eq(IndexingActionType.ADD));

        List<PID> pids = pidCaptor.getAllValues();
        assertTrue(pids.contains(corpus.pid6));
    }
}
