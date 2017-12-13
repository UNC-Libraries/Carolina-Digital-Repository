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
import static edu.unc.lib.dl.acl.util.Permission.reindex;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.Collection;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.GroupsThreadStore;
import edu.unc.lib.dl.cdr.services.processing.IndexingService;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.services.IndexingMessageSender;
import edu.unc.lib.dl.util.IndexingActionType;

/**
 *
 * @author harring
 *
 */
public class IndexingServiceTest {
    @Mock
    private AccessControlService aclService;
    @Mock
    private IndexingMessageSender messageSender;
    @Mock
    private AgentPrincipals agent;
    @Mock
    private AccessGroupSet groups;
    @Mock
    private ContentObject obj;

    @Captor
    private ArgumentCaptor<Collection<PID>> pidsCaptor;

    private IndexingService service;
    private PID objPid;

    @Before
    public void init() throws Exception{
        initMocks(this);

        when(agent.getPrincipals()).thenReturn(groups);
        when(agent.getUsername()).thenReturn("username");
        //when(messageSender.sendIndexingOperation(anyString(), any(Collection.class), any(IndexingActionType.class)))
                //.thenReturn("message_id");

        objPid = PIDs.get(UUID.randomUUID().toString());

        service = new IndexingService();
        service.setAccessControlService(aclService);
        service.setIndexingMessageSender(messageSender);
    }

    @After
    public void tearDown() {
        GroupsThreadStore.clearStore();
    }

    @Test
    public void reindexObjectTest() {
        service.reindexObject(agent, objPid);

        verify(messageSender).sendIndexingOperation(anyString(), pidsCaptor.capture(), eq(IndexingActionType.ADD));

        Collection<PID> collections = pidsCaptor.getValue();
        assertEquals(collections.size(), 1);
        assertTrue(collections.contains(objPid));
    }

    @Test
    public void inplaceReindexObjectAndChildrenTest() {
        service.reindexObjectAndChildren(agent, objPid, true);

        verify(messageSender).sendIndexingOperation(anyString(), pidsCaptor.capture(),
                eq(IndexingActionType.RECURSIVE_REINDEX));

        Collection<PID> collections = pidsCaptor.getValue();
        assertEquals(collections.size(), 1);
        assertTrue(collections.contains(objPid));
    }

    @Test
    public void cleanReindexObjectAndChildrenTest() {
        service.reindexObjectAndChildren(agent, objPid, false);

        verify(messageSender).sendIndexingOperation(anyString(), pidsCaptor.capture(),
                eq(IndexingActionType.CLEAN_REINDEX));

        Collection<PID> collections = pidsCaptor.getValue();
        assertEquals(collections.size(), 1);
        assertTrue(collections.contains(objPid));
    }

    @Test(expected = AccessRestrictionException.class)
    public void insufficientAccessTest() {
        doThrow(new AccessRestrictionException()).when(aclService)
        .assertHasAccess(anyString(), any(PID.class), any(AccessGroupSet.class), eq(reindex));

        service.reindexObject(agent, objPid);
    }

}
