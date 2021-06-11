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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.CharMatcher;

import edu.unc.lib.deposit.work.AbstractConcurrentDepositJob;
import edu.unc.lib.dl.exceptions.RepositoryException;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisEventBuilder;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;
import fi.solita.clamav.ClamAVClient;
import fi.solita.clamav.ScanResult;

/**
 * Scans all staged files registered in the deposit for viruses.
 *
 * @author count0
 *
 */
public class VirusScanJob extends AbstractConcurrentDepositJob {
    private static final Logger log = LoggerFactory
            .getLogger(VirusScanJob.class);

    private static final int MAX_RETRIES = 5;

    private ClamAVClient clamClient;

    public VirusScanJob() {
        super();
    }

    public void setClamClient(ClamAVClient clamClient) {
        this.clamClient = clamClient;
    }

    public VirusScanJob(String uuid, String depositUUID) {
        super(uuid, depositUUID);
    }

    @Override
    public void runJob() {
        log.debug("Running virus checks on : {}", getDepositDirectory());

        Map<String, String> failures = new HashMap<>();

        Model model = getReadOnlyModel();
        List<Entry<PID, String>> hrefs = getPropertyPairList(model, CdrDeposit.stagingLocation);

        setTotalClicks(hrefs.size());
        AtomicInteger scannedObjects = new AtomicInteger();

        for (Entry<PID, String> href : hrefs) {
            interruptJobIfStopped();

            PID objPid = href.getKey();

            if (isObjectCompleted(objPid)) {
                log.debug("Skipping already scanned file {} for {}", href.getValue(), objPid);
                scannedObjects.incrementAndGet();
                addClicks(1);
                continue;
            }

            waitForQueueCapacity();

            submitTask(() -> {
                long start = System.nanoTime();
                log.debug("Scanning file {} for object {}", href.getValue(), objPid);

                int retries = MAX_RETRIES;
                while (true) {
                    if (isInterrupted.get()) {
                        return;
                    }

                    URI fileURI = URI.create(href.getValue());
                    Path file = Paths.get(fileURI);

                    ScanResult result;
                    // Clamd is unable to find files with unicode characters in their path
                    if (charactersInBoundsForClam(file)) {
                        result = clamClient.scanWithResult(file);
                    } else {
                        // Scan files with unicode in their paths via streaming
                        try {
                            result = clamClient.scanWithResult(Files.newInputStream(file));
                        } catch (IOException e) {
                            failures.put(fileURI.toString(), "Failed to scan file");
                            log.error("Unable to scan file {}", file, e);
                            return;
                        }
                    }

                    switch (result.getStatus()) {
                    case FOUND:
                        if (StringUtils.isBlank(result.getSignature()) && --retries > 0) {
                            log.warn("Scan of {} indicated an unidentified problem was found, retrying",
                                    href.getValue());
                            break;
                        } else {
                            failures.put(fileURI.toString(), result.getSignature());
                            log.debug("Scanning of file {} failed in {}s", href.getValue(),
                                    (System.nanoTime() - start) / 1e9);
                            return;
                        }
                    case ERROR:
                        Exception ex = result.getException();
                        String message = "Virus checks are producing errors for file '" + file
                                + "': " + result.getResult();
                        throw new RepositoryException(message, ex);
                    case PASSED:
                        PID binPid = href.getKey();
                        PID parentPid = PIDs.get(binPid.getQualifier(), binPid.getId());
                        PremisLogger premisLogger = getPremisLogger(parentPid);
                        PremisEventBuilder premisEventBuilder = premisLogger.buildEvent(Premis.VirusCheck);

                        premisEventBuilder.addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.clamav))
                                .addEventDetail("File passed pre-ingest scan for viruses")
                                .addOutcome(true)
                                .write();

                        scannedObjects.incrementAndGet();

                        addClicks(1);
                        markObjectCompleted(objPid);

                        log.debug("Scanning of file {} passed in {}s", href.getValue(),
                                (System.nanoTime() - start) / 1e9);

                        return;
                    }
                }
            });
        }

        waitForCompletion();

        if (failures.size() > 0) {
            StringBuilder sb = new StringBuilder("Virus checks failed for some files:\n");
            for (String uri : failures.keySet()) {
                sb.append(uri).append(" - ").append(failures.get(uri)).append("\n");
            }
            failJob(failures.size() + " virus check(s) failed.", sb.toString());
        } else {
            if (scannedObjects.get() != hrefs.size()) {
                failJob("Virus scan job did not attempt to scan all files.",
                        (hrefs.size() - scannedObjects.get()) + " objects were not scanned.");
            }

            PID depositPID = getDepositPID();
            PremisLogger premisDepositLogger = getPremisLogger(depositPID);
            premisDepositLogger.buildEvent(Premis.VirusCheck)
                    .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.clamav))
                    .addEventDetail(scannedObjects + "files scanned for viruses.")
                    .write();
        }
    }

    private boolean charactersInBoundsForClam(Path path) {
        return CharMatcher.ascii().matchesAllOf(path.toString());
    }

    // unused, no results to flush
    @Override
    protected void registrationAction() {
    }
}