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
package edu.unc.lib.dl.cdr.services.processing;

import static edu.unc.lib.dl.acl.util.Permission.ingest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyCollectionOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.Collection;
import java.util.UUID;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.impl.ModelCom;
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
import edu.unc.lib.dl.cdr.services.processing.AddContainerService.AddContainerRequest;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.TransactionCancelledException;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.ObjectTypeMismatchException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.storage.StorageLocationManager;
import edu.unc.lib.dl.persist.services.edit.UpdateDescriptionService;
import edu.unc.lib.dl.persist.services.event.PremisEventBuilderImpl;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.services.OperationsMessageSender;
import edu.unc.lib.dl.test.SelfReturningAnswer;
import edu.unc.lib.dl.util.ResourceType;

/**
 *
 * @author harring
 *
 */
public class AddContainerServiceTest {

    private static final String STORAGE_LOC_ID = "loc1";

    @Mock
    private AccessControlService aclService;
    @Mock
    private RepositoryObjectLoader repoObjLoader;
    @Mock
    private RepositoryObjectFactory repoObjFactory;
    @Mock
    private TransactionManager txManager;
    @Mock
    private OperationsMessageSender messageSender;
    @Mock
    private FedoraTransaction tx;
    @Mock
    private AgentPrincipals agent;
    @Mock
    private AccessGroupSet groups;
    @Mock
    private PremisLogger premisLogger;
    @Mock
    private StorageLocationManager storageLocationManager;
    @Mock
    private StorageLocation storageLocation;
    @Mock
    private UpdateDescriptionService updateDescService;

    @Captor
    private ArgumentCaptor<Collection<PID>> destinationsCaptor;
    @Captor
    private ArgumentCaptor<Collection<PID>> addedContainersCaptor;
    @Captor
    private ArgumentCaptor<Model> modelCaptor;

    private PremisEventBuilderImpl eventBuilder;
    private PID parentPid;
    private PID childPid;
    private AddContainerService service;

    @Before
    public void init() {
        initMocks(this);

        when(agent.getPrincipals()).thenReturn(groups);
        when(agent.getUsername()).thenReturn("user");

        eventBuilder = mock(PremisEventBuilderImpl.class, new SelfReturningAnswer());
        when(premisLogger.buildEvent(eq(Premis.Creation))).thenReturn(eventBuilder);

        when(txManager.startTransaction()).thenReturn(tx);

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                throw new TransactionCancelledException("", invocation.getArgumentAt(0, Throwable.class));
            }

        }).when(tx).cancel(any(Throwable.class));

        when(storageLocationManager.getStorageLocation(any(PID.class))).thenReturn(storageLocation);
        when(storageLocation.getId()).thenReturn(STORAGE_LOC_ID);

        parentPid = PIDs.get(UUID.randomUUID().toString());
        childPid = PIDs.get(UUID.randomUUID().toString());

        service = new AddContainerService();
        service.setAclService(aclService);
        service.setRepositoryObjectFactory(repoObjFactory);
        service.setRepositoryObjectLoader(repoObjLoader);
        service.setTransactionManager(txManager);
        service.setOperationsMessageSender(messageSender);
        service.setStorageLocationManager(storageLocationManager);
        service.setUpdateDescriptionService(updateDescService);
    }

    private AddContainerRequest createRequest(String label, boolean staffOnly, ResourceType containerType) {
        AddContainerRequest req = new AddContainerRequest();
        req.setParentPid(parentPid);
        req.setLabel(label);
        req.setStaffOnly(staffOnly);
        return req.withContainerType(containerType).withAgent(agent);
    }

    @Test(expected = TransactionCancelledException.class)
    public void insufficientAccessTest() {
        doThrow(new AccessRestrictionException()).when(aclService)
                .assertHasAccess(anyString(), eq(parentPid), any(AccessGroupSet.class), eq(ingest));

        try {
            service.addContainer(createRequest("folder", false, ResourceType.Folder));
        } catch (TransactionCancelledException e) {
            assertEquals(AccessRestrictionException.class, e.getCause().getClass());
            throw new TransactionCancelledException();
        }
    }

    @Test(expected = TransactionCancelledException.class)
    public void addCollectionToFolderTest() {
        FolderObject folder = mock(FolderObject.class);
        CollectionObject collection = mock(CollectionObject.class);
        when(repoObjLoader.getRepositoryObject(eq(parentPid))).thenReturn(folder);
        when(repoObjFactory.createCollectionObject(any(PID.class), any(ModelCom.class))).thenReturn(collection);
        doThrow(new ObjectTypeMismatchException("")).when(folder).addMember(collection);

        try {
            service.addContainer(createRequest("collection", false, ResourceType.Collection));
        } catch (TransactionCancelledException e) {
            assertEquals(ObjectTypeMismatchException.class, e.getCause().getClass());
            throw new TransactionCancelledException();
        }
    }

    @Test
    public void addFolderToCollectionTest() {
        CollectionObject collection = mock(CollectionObject.class);
        FolderObject folder = mock(FolderObject.class);
        when(repoObjLoader.getRepositoryObject(eq(parentPid))).thenReturn(collection);
        when(repoObjFactory.createFolderObject(any(PID.class), any(Model.class))).thenAnswer(new Answer<FolderObject>() {
            @Override
            public FolderObject answer(InvocationOnMock invocation) throws Throwable {
                childPid = (PID) invocation.getArguments()[0];
                when(folder.getPid()).thenReturn(childPid);
                return folder;
            }
        });
        when(folder.getPremisLog()).thenReturn(premisLogger);

        service.addContainer(createRequest("folder", false, ResourceType.Folder));

        verify(premisLogger).buildEvent(eq(Premis.Creation));
        verify(eventBuilder).writeAndClose();
        verify(messageSender).sendAddOperation(anyString(), destinationsCaptor.capture(),
                addedContainersCaptor.capture(), anyCollectionOf(PID.class), anyString());

        verify(repoObjFactory).createFolderObject(any(PID.class), modelCaptor.capture());
        Model model = modelCaptor.getValue();
        Resource folderResc = model.getResource(childPid.getRepositoryPath());
        assertTrue(folderResc.hasLiteral(Cdr.storageLocation, STORAGE_LOC_ID));

        Collection<PID> collections = destinationsCaptor.getValue();
        assertEquals(collections.size(), 1);
        assertTrue(collections.contains(parentPid));
        Collection<PID> folders = addedContainersCaptor.getValue();
        assertEquals(folders.size(), 1);
        assertTrue(folders.contains(childPid));
    }

    @Test
    public void addWorkToFolderTest() {
        WorkObject work = mock(WorkObject.class);
        FolderObject folder = mock(FolderObject.class);

        when(repoObjLoader.getRepositoryObject(eq(parentPid))).thenReturn(folder);
        when(repoObjFactory.createWorkObject(any(PID.class), any(Model.class))).thenAnswer(new Answer<WorkObject>() {
            @Override
            public WorkObject answer(InvocationOnMock invocation) throws Throwable {
                childPid = (PID) invocation.getArguments()[0];
                when(work.getPid()).thenReturn(childPid);
                return work;
            }
        });
        when(work.getPremisLog()).thenReturn(premisLogger);

        service.addContainer(createRequest("work", false, ResourceType.Work));

        verify(premisLogger).buildEvent(eq(Premis.Creation));
        verify(eventBuilder).writeAndClose();
        verify(messageSender).sendAddOperation(anyString(), destinationsCaptor.capture(),
                addedContainersCaptor.capture(), anyCollectionOf(PID.class), anyString());

        verify(repoObjFactory).createWorkObject(any(PID.class), modelCaptor.capture());
        Model model = modelCaptor.getValue();
        Resource folderResc = model.getResource(childPid.getRepositoryPath());
        assertTrue(folderResc.hasLiteral(Cdr.storageLocation, STORAGE_LOC_ID));

        Collection<PID> folders = destinationsCaptor.getValue();
        assertEquals(folders.size(), 1);
        assertTrue(folders.contains(parentPid));
        Collection<PID> works = addedContainersCaptor.getValue();
        assertEquals(works.size(), 1);
        assertTrue(works.contains(childPid));
    }
}
