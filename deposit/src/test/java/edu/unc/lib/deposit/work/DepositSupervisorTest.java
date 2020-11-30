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
package edu.unc.lib.deposit.work;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.MethodMode;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.deposit.work.DepositSupervisor.ActionMonitoringTask;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.ingest.DepositData;
import edu.unc.lib.dl.persist.services.ingest.AbstractDepositHandler;
import edu.unc.lib.dl.util.DepositException;
import edu.unc.lib.dl.util.DepositPipelineStatusFactory;
import edu.unc.lib.dl.util.DepositStatusFactory;
import edu.unc.lib.dl.util.PackagingType;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositAction;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositField;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositPipelineAction;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositPipelineState;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositState;
import edu.unc.lib.dl.util.RedisWorkerConstants.Priority;
import net.greghaines.jesque.worker.WorkerPool;

/**
 * @author bbpennel
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration( locations = { "/spring-test/cdr-client-container.xml",
    "/spring-test/deposit-supervisor-test-context.xml"} )
public class DepositSupervisorTest {

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    @Autowired
    private RepositoryPIDMinter pidMinter;

    @Autowired
    private DepositStatusFactory depositStatusFactory;

    @Autowired
    private DepositPipelineStatusFactory pipelineStatusFactory;

    @Autowired
    private List<WorkerPool> depositWorkerPools;

    @Autowired
    private DepositSupervisor supervisor;

    private PID depositDestination;

    private ActionMonitoringTask actionMonitor;

    private AgentPrincipals agent;

    @Before
    public void setup() throws Exception {
        depositDestination = pidMinter.mintContentPid();
        agent = new AgentPrincipals("user", new AccessGroupSet());

        actionMonitor = supervisor.actionMonitoringTask;

        pipelineStatusFactory.setPipelineState(DepositPipelineState.active);
        // Turn up monitoring speed so tests are shorter
        supervisor.setActionMonitorDelay(25l);
    }

    @Test
    public void queueNewDepositRequested() throws Exception {
        PID depositPid = queueDeposit();

        assertDepositStatus(DepositState.unregistered, depositPid);
        assertDepositAction(DepositAction.register, depositPid);

        // Run once to process the submitted deposit
        actionMonitor.run();

        assertDepositStatus(DepositState.queued, depositPid);
        assertDepositAction(null, depositPid);
    }

    @Test
    public void queueNewMigrationDepositRequested() throws Exception {
        PID depositPid = queueDeposit(PackagingType.BXC3_TO_5_MIGRATION, Priority.normal);

        assertDepositStatus(DepositState.unregistered, depositPid);
        assertDepositAction(DepositAction.register, depositPid);

        // Run once to process the submitted deposit
        actionMonitor.run();

        assertDepositStatus(DepositState.queued, depositPid);
        assertDepositAction(null, depositPid);
    }

    @Test
    public void noNewActionsRequested() throws Exception {
        PID depositPid = queueDeposit(true, DepositState.queued);

        actionMonitor.run();

        assertDepositStatus(DepositState.queued, depositPid);
        assertPipelineStatus(DepositPipelineState.active);
    }

    @Test
    public void pauseAndResumeDeposit() throws Exception {
        PID depositPid = queueDeposit(true, DepositState.queued);

        requestDepositAction(depositPid, DepositAction.pause);

        actionMonitor.run();

        assertDepositStatus(DepositState.paused, depositPid);

        requestDepositAction(depositPid, DepositAction.resume);

        actionMonitor.run();

        // Allow time for redis to sync up
        Thread.sleep(10);

        assertDepositStatus(DepositState.queued, depositPid);
    }

    @Test
    public void quietPipelineInInvalidState() throws Exception {
        pipelineStatusFactory.setPipelineState(DepositPipelineState.shutdown);

        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.quiet);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.shutdown);
        assertPipelineAction(null);
    }

    @Test
    public void unquietPipelineInInvalidState() throws Exception {
        pipelineStatusFactory.setPipelineState(DepositPipelineState.active);

        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.unquiet);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.active);
        assertPipelineAction(null);
    }

    @Test
    public void quietAndUnquietWithRunningDeposit() throws Exception {
        PID depositPid = queueDeposit(true, DepositState.running);
        actionMonitor.run();

        assertWorkersPaused(false);

        // Quiet the pipeline
        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.quiet);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.quieted);
        assertPipelineAction(null);
        assertWorkersPaused(true);

        assertDepositStatus(DepositState.quieted, depositPid);

        // Unquiet pipeline
        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.unquiet);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.active);
        assertPipelineAction(null);

        assertDepositStatus(DepositState.quieted, depositPid);
        assertDepositAction(DepositAction.resume, depositPid);

        assertWorkersPaused(false);

        // One more pass to resume the deposits
        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.active);

        assertDepositStatus(DepositState.queued, depositPid);
        assertDepositAction(null, depositPid);
    }

    @Test
    public void quietAndUnquietWithQueuedDeposit() throws Exception {
        PID depositPid = queueDeposit(true, DepositState.queued);

        // Quiet the pipeline
        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.quiet);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.quieted);
        assertWorkersPaused(true);

        // Queued state should be unaffected
        assertDepositStatus(DepositState.queued, depositPid);

        // Unquiet pipeline
        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.unquiet);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.active);
        assertWorkersPaused(false);

        assertDepositStatus(DepositState.queued, depositPid);
        assertDepositAction(null, depositPid);
    }

    @Test
    public void quietAndUnquietWithPauseAction() throws Exception {
        PID depositPid = queueDeposit(true, DepositState.running);

        // Quiet the pipeline
        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.quiet);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.quieted);
        assertWorkersPaused(true);

        assertDepositStatus(DepositState.quieted, depositPid);

        // Attempt to pause deposit, which should have no effect currently
        requestDepositAction(depositPid, DepositAction.pause);

        actionMonitor.run();

        assertDepositStatus(DepositState.quieted, depositPid);
        assertDepositAction(DepositAction.pause, depositPid);

        // Unquiet pipeline
        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.unquiet);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.active);
        assertPipelineAction(null);
        assertWorkersPaused(false);

        // Requested pause action should survive the unquieting
        assertDepositStatus(DepositState.quieted, depositPid);
        assertDepositAction(DepositAction.pause, depositPid);

        // One more pass to trigger the pause action
        actionMonitor.run();

        assertDepositStatus(DepositState.paused, depositPid);
        assertDepositAction(null, depositPid);
    }

    @Test
    public void stopPipelineInInvalidState() throws Exception {
        pipelineStatusFactory.setPipelineState(DepositPipelineState.shutdown);

        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.stop);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.shutdown);
        assertPipelineAction(null);
    }

    @DirtiesContext(methodMode = MethodMode.BEFORE_METHOD)
    @Test
    public void stopPipeline() throws Exception {
        assertWorkersShutdown(false);

        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.stop);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.stopped);
        assertPipelineAction(null);

        assertWorkersShutdown(true);

        // Queue a deposit and show that the action is not performed while stopped
        PID depositPid = queueDeposit();

        actionMonitor.run();

        assertDepositStatus(DepositState.unregistered, depositPid);
        assertDepositAction(DepositAction.register, depositPid);

        // Attempt to unquiet in order to resume, which should have no affect
        pipelineStatusFactory.requestPipelineAction(DepositPipelineAction.unquiet);

        actionMonitor.run();

        assertPipelineStatus(DepositPipelineState.stopped);
        assertPipelineAction(null);

        assertWorkersShutdown(true);
    }

    @DirtiesContext(methodMode = MethodMode.BEFORE_METHOD)
    @Test
    public void startSupervisorProcessAndPauseDeposit() throws Exception {
        supervisor.setActionMonitorDelay(25l);

        supervisor.start();

        PID depositPid = queueDeposit();

        Thread.sleep(50l);

        assertDepositStatus(DepositState.queued, depositPid);
        assertDepositAction(null, depositPid);

        requestDepositAction(depositPid, DepositAction.pause);

        Thread.sleep(50l);

        assertDepositStatus(DepositState.paused, depositPid);
        assertDepositAction(null, depositPid);
    }

    @DirtiesContext(methodMode = MethodMode.BEFORE_METHOD)
    @Test
    public void startSupervisorWithRunningDeposit() throws Exception {
        PID depositPid = queueDeposit(true, DepositState.running);

        supervisor.start();

        assertDepositAction(DepositAction.resume, depositPid);

        Thread.sleep(50l);

        assertDepositStatus(DepositState.queued, depositPid);
        assertDepositAction(null, depositPid);
    }

    @DirtiesContext(methodMode = MethodMode.BEFORE_METHOD)
    @Test
    public void startSupervisorWithQuietedDeposit() throws Exception {
        PID depositPid = queueDeposit(true, DepositState.quieted);

        supervisor.start();

        assertDepositAction(DepositAction.resume, depositPid);

        Thread.sleep(50l);

        assertDepositStatus(DepositState.queued, depositPid);
        assertDepositAction(null, depositPid);
    }

    @DirtiesContext(methodMode = MethodMode.BEFORE_METHOD)
    @Test
    public void startSupervisorWithPausedDeposit() throws Exception {
        PID depositPid = queueDeposit(true, DepositState.paused);

        supervisor.start();

        assertDepositAction(null, depositPid);

        Thread.sleep(50l);

        assertDepositStatus(DepositState.paused, depositPid);
        assertDepositAction(null, depositPid);
    }

    @DirtiesContext(methodMode = MethodMode.BEFORE_METHOD)
    @Test
    public void startSupervisorWithNewQueuedDeposit() throws Exception {
        PID depositPid = queueDeposit(true, DepositState.queued);

        supervisor.start();

        assertDepositAction(DepositAction.register, depositPid);

        Thread.sleep(50l);

        assertDepositStatus(DepositState.queued, depositPid);
        assertDepositAction(null, depositPid);
    }

    /*
     * TODO tests for the following:
     * * queue different priorities
     * * next jobs come back in expected orders
     * * normalization jobs selected correctly
     * * handles case of no matching normalization job
     * * job started event processing
     * * job success event processing
     * * job failure event tests
     * * deposit completion
     */

    private void assertWorkersPaused(boolean expectedValue) {
        for (WorkerPool workerPool : depositWorkerPools) {
            assertEquals("Expected worker to be " + (expectedValue ? "" : "un") + "paused, but it was not",
                    expectedValue, workerPool.isPaused());
        }
    }

    private void assertWorkersShutdown(boolean expectedValue) {
        for (WorkerPool workerPool : depositWorkerPools) {
            assertEquals("Expected worker to " + (expectedValue ? "not " : "") + " be shutdown, but it was",
                    expectedValue, workerPool.isShutdown());
        }
    }

    private void assertDepositStatus(DepositState expectedState, PID depositPid) {
        assertEquals(expectedState, depositStatusFactory.getState(depositPid.getId()));
    }

    private void assertDepositAction(DepositAction expectedAction, PID depositPid) {
        Map<String, String> status = depositStatusFactory.get(depositPid.getId());
        DepositAction action;
        if (status.containsKey(DepositField.actionRequest.name())) {
            action = DepositAction.valueOf(status.get(DepositField.actionRequest.name()));
        } else {
            action = null;
        }
        assertEquals(expectedAction, action);
    }

    private void assertPipelineStatus(DepositPipelineState expectedState) {
        assertEquals(expectedState, pipelineStatusFactory.getPipelineState());
    }

    private void assertPipelineAction(DepositPipelineAction expectedAction) {
        assertEquals(expectedAction, pipelineStatusFactory.getPipelineAction());
    }

    private void requestDepositAction(PID depositPid, DepositAction action) {
        depositStatusFactory.requestAction(depositPid.getId(), action);
    }

    private PID queueDeposit() throws DepositException {
        return queueDeposit(false, null);
    }

    private PID queueDeposit(boolean clearAction, DepositState finalState) throws DepositException {
        PID depositPid = queueDeposit(PackagingType.DIRECTORY, Priority.normal);
        if (clearAction) {
            depositStatusFactory.clearActionRequest(depositPid.getId());
        }
        if (finalState != null) {
            depositStatusFactory.setState(depositPid.getId(), finalState);
        }
        return depositPid;
    }

    private PID queueDeposit(PackagingType packagingType, Priority priority) throws DepositException {
        AbstractDepositHandler depositHandler = new AbstractDepositHandler() {
            @Override
            public PID doDeposit(PID destination, DepositData deposit) throws DepositException {
                PID depositPID = pidMinter.mintDepositRecordPid();
                registerDeposit(depositPID, destination, deposit, null);
                return depositPID;
            }

        };
        depositHandler.setDepositStatusFactory(depositStatusFactory);
        depositHandler.setPidMinter(pidMinter);
        depositHandler.setDepositsDirectory(tmpFolder.getRoot());

        DepositData deposit = new DepositData(null, null, packagingType, null, agent);
        deposit.setPriority(priority);
        return depositHandler.doDeposit(depositDestination, deposit);
    }
}
