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
package edu.unc.lib.deposit.fcrepo4;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.jena.rdf.model.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectDriver;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fcrepo4.RepositoryPathConstants;
import edu.unc.lib.dl.fcrepo4.TransactionCancelledException;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.persist.services.deposit.DepositModelManager;
import edu.unc.lib.dl.persist.services.event.PremisEventBuilderImpl;
import edu.unc.lib.dl.persist.services.event.PremisLoggerFactoryImpl;
import edu.unc.lib.dl.reporting.ActivityMetricsClient;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.DepositStatusFactory;
import edu.unc.lib.dl.util.JobStatusFactory;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositState;

/**
 *
 * @author bbpennel
 *
 */
public class AbstractDepositJobTest {

    protected static final String FEDORA_BASE = "http://localhost:48085/rest/";
    protected static final String TX_URI = "http://localhost:48085/rest/tx:99b58d30-06f5-477b-a44c-d614a9049d38";

    @Mock
    protected RepositoryObjectDriver driver;
    @Mock
    protected TransactionManager txManager;

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    protected File depositsDirectory;
    protected File depositDir;

    @Mock
    protected JobStatusFactory jobStatusFactory;
    @Mock
    protected DepositStatusFactory depositStatusFactory;
    @Mock
    protected PremisLoggerFactoryImpl premisLoggerFactory;
    @Mock
    protected PremisLogger premisLogger;
    @Mock
    protected PremisEventBuilderImpl premisEventBuilder;
    @Mock
    protected ActivityMetricsClient metricsClient;
    @Mock
    protected Resource testResource;
    @Mock
    protected RepositoryPIDMinter pidMinter;

    protected String jobUUID;

    protected String depositUUID;
    protected PID depositPid;
    protected String depositJobId;

    protected DepositModelManager depositModelManager;
    @Mock
    protected FedoraTransaction tx;

    private Set<String> completedIds;

    @Before
    public void initBase() throws Exception {
        initMocks(this);

        TestHelper.setContentBase(FEDORA_BASE);

        when(premisLoggerFactory.createPremisLogger(any(PID.class), any(File.class)))
                .thenReturn(premisLogger);
        when(premisLoggerFactory.createPremisLogger(any(RepositoryObject.class), any(BinaryTransferSession.class)))
                .thenReturn(premisLogger);
        when(premisLogger.buildEvent(any(Resource.class))).thenReturn(premisEventBuilder);
        when(premisEventBuilder.addEventDetail(anyString(), Matchers.<Object>anyVararg())).thenReturn(premisEventBuilder);
        when(premisEventBuilder.addSoftwareAgent(any(PID.class))).thenReturn(premisEventBuilder);
        when(premisEventBuilder.create()).thenReturn(testResource);

        tmpFolder.create();
        depositsDirectory = tmpFolder.newFolder("deposits");

        jobUUID = UUID.randomUUID().toString();

        depositUUID = UUID.randomUUID().toString();
        depositDir = new File(depositsDirectory, depositUUID);
        depositDir.mkdir();
        depositPid = PIDs.get(RepositoryPathConstants.DEPOSIT_RECORD_BASE, depositUUID);

        depositModelManager = DepositModelManager.inMemoryManager();

        when(txManager.startTransaction()).thenReturn(tx);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                throw new TransactionCancelledException("Tx cancelled",
                        invocation.getArgumentAt(0, Exception.class));
            }
        }).when(tx).cancel(any(Exception.class));

        completedIds = new HashSet<>();

        when(depositStatusFactory.getState(anyString())).thenReturn(DepositState.running);

        doAnswer(invocation -> {
            String objId = invocation.getArgumentAt(1, String.class);
            completedIds.add(objId);
            return null;
        }).when(jobStatusFactory).addObjectCompleted(anyString(), anyString());
        when(jobStatusFactory.objectIsCompleted(anyString(), anyString())).thenAnswer(invocation -> {
            String objId = invocation.getArgumentAt(1, String.class);
            return completedIds.contains(objId);
        });
    }

    @After
    public void tearDown() {
        depositModelManager.close();
    }

    protected PID makePid() {
        return makePid(RepositoryPathConstants.CONTENT_BASE);
    }

    protected PID makePid(String qualifier) {
        String uuid = UUID.randomUUID().toString();
        return PIDs.get(qualifier + "/" + uuid);
    }
}
