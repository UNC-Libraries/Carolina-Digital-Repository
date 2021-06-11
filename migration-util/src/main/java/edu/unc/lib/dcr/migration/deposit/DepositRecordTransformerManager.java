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
package edu.unc.lib.dcr.migration.deposit;

import static edu.unc.lib.dcr.migration.utils.DisplayProgressUtil.displayProgress;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import edu.unc.lib.dcr.migration.paths.PathIndex;
import edu.unc.lib.dcr.migration.utils.DisplayProgressUtil;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;

/**
 * Manager for deposit record transformers, constructing and monitoring
 * new transformation jobs.
 *
 * @author bbpennel
 */
public class DepositRecordTransformerManager {

    private static final Logger log = getLogger(DepositRecordTransformerManager.class);

    private PathIndex pathIndex;

    private PremisLoggerFactory premisLoggerFactory;

    private RepositoryObjectFactory repoObjFactory;

    private BlockingQueue<DepositRecordTransformer> createdTransformers;
    private AtomicInteger totalAdded;

    public DepositRecordTransformerManager() {
        this.createdTransformers = new LinkedBlockingQueue<>();
        totalAdded = new AtomicInteger();
    }

    /**
     * Construct new deposit record transformer
     *
     * @param originalPid
     * @param newPid
     * @param transferSession
     * @return
     */
    public DepositRecordTransformer createTransformer(PID originalPid, PID newPid,
            BinaryTransferSession transferSession) {
        DepositRecordTransformer transformer = new DepositRecordTransformer(
                originalPid, newPid, transferSession);
        transformer.setPathIndex(pathIndex);
        transformer.setPremisLoggerFactory(premisLoggerFactory);
        transformer.setRepositoryObjectFactory(repoObjFactory);

        createdTransformers.add(transformer);
        totalAdded.incrementAndGet();
        return transformer;
    }

    /**
     * Waits for all transformers to complete execution, logging any errors
     * which occur
     */
    public int awaitTransformers() {
        int result = 0;
        long completed = 0;
        do {
            DepositRecordTransformer transformer = createdTransformers.poll();
            try {
                displayProgress(completed, totalAdded.get());
                if (transformer == null) {
                    DisplayProgressUtil.finishProgress();
                    return result;
                }
                transformer.join();
                completed++;
            } catch (RuntimeException e) {
                log.error("Failed to transform {}", transformer.getPid(), e);
                result = 1;
            }
        } while (true);
    }

    public void setPathIndex(PathIndex pathIndex) {
        this.pathIndex = pathIndex;
    }

    public void setPremisLoggerFactory(PremisLoggerFactory premisLoggerFactory) {
        this.premisLoggerFactory = premisLoggerFactory;
    }

    public void setRepositoryObjectFactory(RepositoryObjectFactory repoObjFactory) {
        this.repoObjFactory = repoObjFactory;
    }

}
