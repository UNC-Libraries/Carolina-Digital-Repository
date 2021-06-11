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
package edu.unc.lib.deposit.validate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.deposit.work.AbstractConcurrentDepositJob;
import edu.unc.lib.dl.exceptions.InvalidChecksumException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisEventBuilder;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.DigestAlgorithm;
import edu.unc.lib.dl.util.MultiDigestInputStreamWrapper;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;

/**
 * Calculates digests for staged files, performing a fixity check if existing
 * digests were provided with the deposit.
 *
 * The job will perform fixity checks concurrently using a thread pool. The results of
 * the checks are periodically flushed the deposit model and premis logs. The number of
 * fixity check jobs queued at one time is limited in order to avoid blocking other
 * deposit jobs for long periods of time.
 *
 * @author bbpennel
 */
public class FixityCheckJob extends AbstractConcurrentDepositJob {
    private static final Logger log = LoggerFactory.getLogger(FixityCheckJob.class);

    private static final Collection<DigestAlgorithm> REQUIRED_ALGS = Collections.singleton(
            DigestAlgorithm.DEFAULT_ALGORITHM);

    public FixityCheckJob(String uuid, String depositUUID) {
        super(uuid, depositUUID);
        this.rollbackDatasetOnFailure = false;
    }

    @Override
    public void runJob() {
        log.debug("Performing FixityCheckJob for deposit {}", depositUUID);
        Model model = getReadOnlyModel();

        List<Entry<PID, String>> stagingList = getOriginalStagingPairList(model);
        setTotalClicks(stagingList.size());

        startResultRegistrar();

        try {
            for (Entry<PID, String> stagingEntry : stagingList) {
                PID rescPid = stagingEntry.getKey();
                // Skip already checked files
                if (isObjectCompleted(rescPid)) {
                    log.debug("Skipping over already completed fixity check for {}", rescPid.getId());
                    addClicks(1);
                    continue;
                }

                interruptJobIfStopped();

                // Wait for some of the jobs to finish before queuing more to avoid blocking all other deposits
                waitForQueueCapacity();

                log.debug("Queuing fixity check for {}", rescPid.getId());

                String stagedPath = stagingEntry.getValue();
                URI stagedUri = URI.create(stagedPath);

                Resource objResc = model.getResource(rescPid.getRepositoryPath());
                Resource origResc = DepositModelHelpers.getDatastream(objResc);

                Map<DigestAlgorithm, String> existingDigests = getDigestsForResource(origResc);

                submitTask(new FixityCheckRunnable(rescPid, stagedUri, origResc, existingDigests));
            }

            waitForCompletion();
            log.debug("Completed FixityCheckJob {} in deposit {}", jobUUID, depositUUID);
        } finally {
            awaitRegistrarShutdown();
        }
    }

    private Map<DigestAlgorithm, String> getDigestsForResource(Resource resc) {
        Map<DigestAlgorithm, String> digests = new HashMap<>();
        for (DigestAlgorithm algorithm : DigestAlgorithm.values()) {
            if (resc.hasProperty(algorithm.getDepositProperty())) {
                digests.put(algorithm, resc.getProperty(algorithm.getDepositProperty()).getString());
            }
        }
        return digests;
    }

    @Override
    protected void registrationAction() {
     // Capture the current set of results, in case it grows during registration
        List<Object> results = new ArrayList<>();
        resultsQueue.drainTo(results);
        log.debug("Registering batch of {} fixity check results", results.size());
        // Commit any newly generated digests to the deposit model and record results
        commit(() -> {
            results.forEach(resultObj -> {
                FixityCheckResult result = (FixityCheckResult) resultObj;
                result.digests.forEach((alg, digest) -> {
                    if (result.origResc.hasProperty(alg.getDepositProperty())) {
                        log.debug("{} fixity check for {} passed with value {}",
                                alg.getName(), result.origResc, digest);
                    } else {
                        log.debug("Storing {} digest for {} with value {}",
                                alg.getName(), result.origResc, digest);
                        result.origResc.addLiteral(alg.getDepositProperty(), digest);
                    }
                    result.details.add(alg.getName().toUpperCase() + " checksum calculated: " + digest);
                });
            });
        });
        // Record events and progress state
        results.forEach(resultObj -> {
            FixityCheckResult result = (FixityCheckResult) resultObj;
            // Store event for calculation of checksums
            PremisLogger premisDepositLogger = getPremisLogger(result.rescPid);
            PremisEventBuilder builder = premisDepositLogger.buildEvent(Premis.MessageDigestCalculation)
                    .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.depositService));
            result.details.forEach(builder::addEventDetail);
            builder.write();

            markObjectCompleted(result.rescPid);
            log.debug("Completed fixity recording for {}", result.stagedUri);
            addClicks(1);
        });
    }

    private class FixityCheckRunnable implements Runnable {
        private URI stagedUri;
        private Resource origResc;
        private PID rescPid;
        private Map<DigestAlgorithm, String> existingDigests;

        public FixityCheckRunnable(PID rescPid, URI stagedUri, Resource origResc,
                Map<DigestAlgorithm, String> existingDigests) {
            this.stagedUri = stagedUri;
            this.origResc = origResc;
            this.rescPid = rescPid;
            this.existingDigests = existingDigests;
        }

        @Override
        public void run() {
            if (isInterrupted.get()) {
                return;
            }

            try (InputStream fStream = Files.newInputStream(Paths.get(stagedUri))) {
                log.debug("Calculating digests for {}", stagedUri);
                MultiDigestInputStreamWrapper digestWrapper = new MultiDigestInputStreamWrapper(
                        fStream, existingDigests, REQUIRED_ALGS);
                digestWrapper.checkFixity();
                log.debug("Verified fixity of {}", stagedUri);

                receiveResult(new FixityCheckResult(rescPid, stagedUri, origResc, digestWrapper.getDigests()));
            } catch (InvalidChecksumException e) {
                failJob(String.format("Fixity check failed for %s belonging to %s",
                        stagedUri, origResc.getURI()), e.getMessage());
            } catch (IOException e) {
                failJob(e, "Failed to read file {0} for fixity check", stagedUri);
            }
        }
    }

    /**
     * Result from a fixity check job
     * @author bbpennel
     */
    private class FixityCheckResult {
        private PID rescPid;
        private URI stagedUri;
        private Resource origResc;
        private Map<DigestAlgorithm, String> digests;
        private List<String> details;

        public FixityCheckResult(PID rescPid, URI stagedUri, Resource origResc, Map<DigestAlgorithm, String> digests) {
            this.rescPid = rescPid;
            this.stagedUri = stagedUri;
            this.origResc = origResc;
            this.digests = digests;
            details = new ArrayList<>();
        }
    }
}
