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

import static edu.unc.lib.dl.data.ingest.solr.test.MockRepositoryObjectHelpers.addMembers;
import static edu.unc.lib.dl.data.ingest.solr.test.MockRepositoryObjectHelpers.makeContainer;
import static edu.unc.lib.dl.data.ingest.solr.test.MockRepositoryObjectHelpers.makeFileObject;
import static edu.unc.lib.dl.data.ingest.solr.test.MockRepositoryObjectHelpers.makePid;
import static edu.unc.lib.dl.util.IndexingActionType.ADD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import edu.unc.lib.dl.fcrepo4.ContentContainerObject;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.services.IndexingMessageSender;
import edu.unc.lib.dl.util.IndexingActionType;

/**
 *
 * @author bbpennel
 *
 */
public class RecursiveTreeIndexerTest {
    private static final String USER = "user";

    private RecursiveTreeIndexer indexer;

    @Mock
    private ContentContainerObject containerObj;

    @Mock
    private RepositoryObjectLoader repositoryObjectLoader;
    @Mock
    private IndexingMessageSender messageSender;

    @Captor
    protected ArgumentCaptor<PID> pidCaptor;

    @Before
    public void setup() throws Exception {
        initMocks(this);

        containerObj = makeContainer(makePid(), repositoryObjectLoader);

        indexer = new RecursiveTreeIndexer();
        indexer.setIndexingMessageSender(messageSender);
    }

    @Test
    public void testNonContainer() throws Exception {
        FileObject fileObj = makeFileObject(makePid(), repositoryObjectLoader);

        indexer.index(fileObj, ADD, USER);

        verify(messageSender).sendIndexingOperation(eq(USER), pidCaptor.capture(),
                eq(IndexingActionType.ADD));

        assertEquals(fileObj.getPid(), pidCaptor.getValue());
    }

    @Test
    public void testNoChildren() throws Exception {
        ContentContainerObject containerObj = makeContainer(makePid(), repositoryObjectLoader);

        indexer.index(containerObj, ADD, USER);

        verify(messageSender).sendIndexingOperation(eq(USER), pidCaptor.capture(),
                eq(IndexingActionType.ADD));

        assertEquals(containerObj.getPid(), pidCaptor.getValue());
    }

    @Test
    public void testHierarchy() throws Exception {
        ContentContainerObject containerObj = makeContainer(makePid(), repositoryObjectLoader);
        ContentContainerObject child1Obj = makeContainer(makePid(), repositoryObjectLoader);
        FileObject fileObj = makeFileObject(makePid(), repositoryObjectLoader);
        ContentContainerObject child2Obj = makeContainer(makePid(), repositoryObjectLoader);

        addMembers(containerObj, child1Obj, child2Obj);
        addMembers(child1Obj, fileObj);

        indexer.index(containerObj, ADD, USER);

        verify(messageSender, times(4)).sendIndexingOperation(eq(USER), pidCaptor.capture(),
                eq(IndexingActionType.ADD));

        List<PID> pids = pidCaptor.getAllValues();
        assertTrue(pids.contains(containerObj.getPid()));
        assertTrue(pids.contains(child1Obj.getPid()));
        assertTrue(pids.contains(fileObj.getPid()));
        assertTrue(pids.contains(child2Obj.getPid()));
    }
}
