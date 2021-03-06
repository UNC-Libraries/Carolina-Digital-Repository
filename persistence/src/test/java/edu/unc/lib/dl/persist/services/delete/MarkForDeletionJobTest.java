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
package edu.unc.lib.dl.persist.services.delete;

import static edu.unc.lib.boxc.auth.api.Permission.markForDeletion;
import static edu.unc.lib.boxc.auth.api.Permission.markForDeletionUnit;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import edu.unc.lib.boxc.auth.api.exceptions.AccessRestrictionException;
import edu.unc.lib.boxc.auth.api.models.AccessGroupSet;
import edu.unc.lib.boxc.auth.api.models.AgentPrincipals;
import edu.unc.lib.boxc.auth.api.services.AccessControlService;
import edu.unc.lib.boxc.auth.fcrepo.models.AccessGroupSetImpl;
import edu.unc.lib.boxc.common.test.SelfReturningAnswer;
import edu.unc.lib.boxc.model.api.exceptions.InvalidOperationForObjectType;
import edu.unc.lib.boxc.model.api.ids.PID;
import edu.unc.lib.boxc.model.api.objects.AdminUnit;
import edu.unc.lib.boxc.model.api.objects.ContentObject;
import edu.unc.lib.boxc.model.api.objects.DepositRecord;
import edu.unc.lib.boxc.model.api.objects.RepositoryObjectLoader;
import edu.unc.lib.boxc.model.api.rdf.Premis;
import edu.unc.lib.boxc.model.fcrepo.ids.PIDs;
import edu.unc.lib.dl.persist.api.event.PremisEventBuilder;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.services.OperationsMessageSender;
import edu.unc.lib.dl.sparql.SparqlUpdateService;

/**
 *
 * @author bbpennel
 *
 */
public class MarkForDeletionJobTest {

    private static final String MESSAGE = "Reason for the deletion";

    @Mock
    private AccessControlService aclService;
    @Mock
    private RepositoryObjectLoader repositoryObjectLoader;
    @Mock
    private SparqlUpdateService sparqlUpdateService;
    @Mock
    private OperationsMessageSender operationsMessageSender;
    @Mock
    private ContentObject contentObj;
    @Mock
    private AgentPrincipals agent;
    @Mock
    private AccessGroupSet groups;
    @Mock
    private PremisLogger premisLogger;
    @Mock
    private AdminUnit repoObj;
    @Mock
    private PremisLoggerFactory premisLoggerFactory;

    @Captor
    private ArgumentCaptor<String> messageCaptor;

    private PremisEventBuilder eventBuilder;

    private PID pid;

    private MarkForDeletionJob job;

    @Before
    public void init() {
        initMocks(this);

        when(repositoryObjectLoader.getRepositoryObject(any(PID.class))).thenReturn(contentObj);

        when(agent.getPrincipals()).thenReturn(groups);
        when(agent.getUsername()).thenReturn("user");

        when(contentObj.getMetadataUri()).thenReturn(URI.create(""));

        eventBuilder = mock(PremisEventBuilder.class, new SelfReturningAnswer());
        when(premisLoggerFactory.createPremisLogger(contentObj)).thenReturn(premisLogger);
        when(premisLogger.buildEvent(eq(Premis.Deaccession))).thenReturn(eventBuilder);

        pid = PIDs.get(UUID.randomUUID().toString());

        job = new MarkForDeletionJob(pid, MESSAGE, agent, repositoryObjectLoader, sparqlUpdateService, aclService,
                premisLoggerFactory);
    }

    @Test(expected = AccessRestrictionException.class)
    public void insufficientAccessTest() {
        doThrow(new AccessRestrictionException()).when(aclService)
                .assertHasAccess(anyString(), eq(pid), any(AccessGroupSetImpl.class), eq(markForDeletion));

        job.run();
    }

    @Test(expected = AccessRestrictionException.class)
    public void insufficientAccessAdminUnitTest() {
        when(repositoryObjectLoader.getRepositoryObject(pid)).thenReturn(repoObj);
        doThrow(new AccessRestrictionException()).when(aclService)
                .assertHasAccess(anyString(), eq(pid), any(AccessGroupSetImpl.class), eq(markForDeletionUnit));

        job.run();
    }

    @Test(expected = InvalidOperationForObjectType.class)
    public void invalidObjectTypeTest() {
        DepositRecord depObj = mock(DepositRecord.class);
        when(repositoryObjectLoader.getRepositoryObject(any(PID.class))).thenReturn(depObj);

        job.run();
    }

    @Test
    public void markForDeletionTest() {
        job.run();

        verify(sparqlUpdateService).executeUpdate(anyString(), anyString());

        verify(premisLogger).buildEvent(eq(Premis.Deaccession));

        verify(eventBuilder, times(2)).addEventDetail(messageCaptor.capture());
        List<String> details = messageCaptor.getAllValues();
        assertTrue(details.contains(MESSAGE));

        verify(eventBuilder).writeAndClose();
    }
}
