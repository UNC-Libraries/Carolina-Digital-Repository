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
package edu.unc.lib.dl.persist.services.edit;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.List;
import java.util.UUID;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.TransactionCancelledException;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.services.event.PremisEventBuilderImpl;
import edu.unc.lib.dl.rdf.DcElements;
import edu.unc.lib.dl.rdf.Ebucore;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.services.OperationsMessageSender;
import edu.unc.lib.dl.test.SelfReturningAnswer;

/**
 *
 * @author harring
 *
 */
public class EditFilenameServiceTest {

    @Mock
    private AccessControlService aclService;
    @Mock
    private RepositoryObjectFactory repoObjFactory;
    @Mock
    private RepositoryObjectLoader repoObjLoader;
    @Mock
    private TransactionManager txManager;
    @Mock
    private OperationsMessageSender messageSender;
    @Mock
    private FedoraTransaction tx;
    @Mock
    private FileObject repoObj;
    @Mock
    private BinaryObject binaryObj;
    @Mock
    private WorkObject workObj;
    @Mock
    private Model model;
    @Mock
    private Resource resc;
    @Mock
    private AgentPrincipals agent;
    @Mock
    private AccessGroupSet groups;
    @Mock
    private PremisLogger premisLogger;

    @Captor
    private ArgumentCaptor<String> labelCaptor;
    @Captor
    private ArgumentCaptor<List<PID>> pidCaptor;

    private PremisEventBuilderImpl eventBuilder;

    private PID pid;

    private EditFilenameService service;

    @Before
    public void init() throws Exception {
        initMocks(this);

        pid = PIDs.get(UUID.randomUUID().toString());

        service = new EditFilenameService();

        service.setAclService(aclService);
        service.setRepositoryObjectFactory(repoObjFactory);
        service.setRepositoryObjectLoader(repoObjLoader);
        service.setTransactionManager(txManager);
        service.setOperationsMessageSender(messageSender);

        when(repoObjLoader.getRepositoryObject(any(PID.class))).thenReturn(repoObj);
        when(repoObj.getOriginalFile()).thenReturn(binaryObj);
        when(binaryObj.getModel()).thenReturn(model);
        when(model.getResource(anyString())).thenReturn(resc);
        when(agent.getPrincipals()).thenReturn(groups);
        when(agent.getUsername()).thenReturn("user");

        eventBuilder = mock(PremisEventBuilderImpl.class, new SelfReturningAnswer());
        when(repoObj.getPremisLog()).thenReturn(premisLogger);
        when(premisLogger.buildEvent(eq(Premis.FilenameChange))).thenReturn(eventBuilder);
        when(agent.getUsernameUri()).thenReturn("agentname");
        when(eventBuilder.write()).thenReturn(resc);

        when(txManager.startTransaction()).thenReturn(tx);
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                throw new TransactionCancelledException("", invocation.getArgumentAt(0, Throwable.class));
            }

        }).when(tx).cancel(any(Throwable.class));
    }

    @Test
    public void editFilenameTest() {
        when(binaryObj.getFilename()).thenReturn("Old file name");
        String label = "a brand-new title!";
        service.editLabel(agent, pid, label);

        verify(repoObjFactory).createExclusiveRelationship(eq(binaryObj), eq(Ebucore.filename), eq(label));
        verify(repoObjFactory).createExclusiveRelationship(eq(repoObj), eq(DcElements.title), eq(label));
        verify(premisLogger).buildEvent(eq(Premis.FilenameChange));
        verify(eventBuilder).addEventDetail(labelCaptor.capture());
        assertEquals(labelCaptor.getValue(), "Object renamed from Old file name to " + label);
        verify(eventBuilder).writeAndClose();

        verify(messageSender).sendUpdateDescriptionOperation(anyString(), pidCaptor.capture());
        assertEquals(pid, pidCaptor.getValue().get(0));
    }

    @Test
    public void editNoFilenameTest() {
        String label = "a brand-new title too!";
        service.editLabel(agent, pid, label);

        verify(repoObjFactory).createExclusiveRelationship(eq(binaryObj), eq(Ebucore.filename), eq(label));
        verify(repoObjFactory).createExclusiveRelationship(eq(repoObj), eq(DcElements.title), eq(label));
        verify(premisLogger).buildEvent(eq(Premis.FilenameChange));
        verify(eventBuilder).addEventDetail(labelCaptor.capture());
        assertEquals(labelCaptor.getValue(), "Object renamed from no ebucore:filename to " + label);
        verify(eventBuilder).writeAndClose();

        verify(messageSender).sendUpdateDescriptionOperation(anyString(), pidCaptor.capture());
        assertEquals(pid, pidCaptor.getValue().get(0));
    }

    @Test
    public void editFilenamelNonFileObjTest() {
        when(repoObjLoader.getRepositoryObject(any(PID.class))).thenReturn(workObj);

        try {
            service.editLabel(agent, pid, "label");
        } catch (Exception e) {
           assertEquals(e.getCause().getClass(), IllegalArgumentException.class);
        }
    }

    @Test(expected = TransactionCancelledException.class)
    public void insufficientAccessTest() {
        doThrow(new AccessRestrictionException()).when(aclService)
            .assertHasAccess(anyString(), eq(pid), any(AccessGroupSet.class), eq(Permission.editDescription));

        service.editLabel(agent, pid, "label");
    }
}