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
package edu.unc.lib.deposit.transfer;

import static edu.unc.lib.dl.fcrepo4.RepositoryPathConstants.DEPOSIT_RECORD_BASE;
import static edu.unc.lib.dl.model.DatastreamPids.getDepositManifestPid;
import static edu.unc.lib.dl.model.DatastreamPids.getOriginalFilePid;
import static edu.unc.lib.dl.model.DatastreamPids.getTechnicalMetadataPid;
import static edu.unc.lib.dl.rdf.CdrDeposit.hasDatastreamDescriptiveHistory;
import static edu.unc.lib.dl.util.DigestAlgorithm.DEFAULT_ALGORITHM;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toSet;
import static org.apache.jena.rdf.model.ResourceFactory.createStringLiteral;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import edu.unc.lib.deposit.work.AbstractConcurrentDepositJob;
import edu.unc.lib.dl.exceptions.InvalidChecksumException;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.model.DatastreamType;
import edu.unc.lib.dl.persist.api.storage.BinaryDetails;
import edu.unc.lib.dl.persist.api.transfer.BinaryAlreadyExistsException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrDeposit;

/**
 * Job which transfers binaries included in this deposit to the appropriate destination
 * storage location.
 *
 * @author bbpennel
 *
 */
public class TransferBinariesToStorageJob extends AbstractConcurrentDepositJob {

    private static final Logger log = LoggerFactory.getLogger(TransferBinariesToStorageJob.class);

    private static final Set<Resource> TYPES_ALLOWING_DESC = new HashSet<>(asList(
            Cdr.Folder, Cdr.Work, Cdr.Collection, Cdr.AdminUnit, Cdr.FileObject));

    @Autowired
    private RepositoryObjectFactory repoObjFactory;

    private Model model;

    /**
     *
     */
    public TransferBinariesToStorageJob() {
        this.rollbackDatasetOnFailure = false;
    }

    /**
     * @param uuid
     * @param depositUUID
     */
    public TransferBinariesToStorageJob(String uuid, String depositUUID) {
        super(uuid, depositUUID);
        this.rollbackDatasetOnFailure = false;
    }

    @Override
    public void runJob() {
        model = getReadOnlyModel();

        // Count how many objects are being deposited
        initializeClicks();

        // All objects in deposit should have the same destination, so pull storage loc from deposit record
        try (BinaryTransferSession transferSession = getTransferSession(model)) {
            startResultRegistrar();

            ResIterator subjectIterator = model.listResourcesWithProperty(RDF.type);
            while (subjectIterator.hasNext()) {
                Resource resc = subjectIterator.next();

                interruptJobIfStopped();

                waitForQueueCapacity();

                submitTask(new TransferBinariesRunnable(resc.getURI(), transferSession));
            }

            waitForCompletion();
        }
    }

    private void initializeClicks() {
        int i = 0;
        ResIterator subjectIterator = model.listSubjects();
        while (subjectIterator.hasNext()) {
            Resource resc = subjectIterator.next();
            // Only count subjects that have a type defined, which excludes binary resources
            if (resc.hasProperty(RDF.type)) {
                i++;
            }
        }

        resetClicks();
        setTotalClicks(i);
    }

    @Override
    protected void registrationAction() {
        List<Object> results = new ArrayList<>();
        resultsQueue.drainTo(results);
        log.debug("Registering batch of {} transfer results", results.size());
        commit(() -> {
            results.forEach(resultObj -> {
                TransferBinariesResult result = (TransferBinariesResult) resultObj;
                model.add(result.statements);
                addClicks(1);
            });
        });
    }

    private class TransferBinariesRunnable implements Runnable {
        private BinaryTransferSession transferSession;
        private Resource resc;
        private String rescUri;
        private PID objPid;
        private TransferBinariesResult result;

        public TransferBinariesRunnable(String rescUri, BinaryTransferSession transferSession) {
            this.transferSession = transferSession;
            this.rescUri = rescUri;
            result = new TransferBinariesResult();
        }

        @Override
        public void run() {
            if (isInterrupted.get()) {
                return;
            }

            interruptJobIfStopped();

            log.debug("Beginning transfer binaries runnable for {}", rescUri);
            Model model = getReadOnlyModel();
            try {
                resc = model.getResource(rescUri);

                objPid = PIDs.get(resc.toString());

                transferBinaries();
            } finally {
                depositModelManager.end();
                log.debug("Finished transfer binaries runnable for {}", rescUri);
            }
        }

        private void transferBinaries() {
            Set<Resource> rescTypes = resc.listProperties(RDF.type).toList().stream()
                    .map(Statement::getResource).collect(toSet());

            if (TYPES_ALLOWING_DESC.stream().anyMatch(rescTypes::contains)) {
                transferModsHistoryFile();
            }

            if (rescTypes.contains(Cdr.FileObject)) {
                transferOriginalFile();
                transferFitsExtract();
                transferFitsHistoryFile();
            } else if (objPid.getQualifier().equals(DEPOSIT_RECORD_BASE)) {
                transferDepositManifests();
            }

            receiveResult(result);
        }

        private void transferOriginalFile() {
            // add storageUri if doesn't already exist. It will exist in a resume scenario.
            if (datastreamNotTransferred(CdrDeposit.hasDatastreamOriginal)) {
                PID originalPid = getOriginalFilePid(objPid);
                Resource originalResc = DepositModelHelpers.getDatastream(resc);

                URI stagingUri = URI.create(originalResc.getProperty(CdrDeposit.stagingLocation).getString());
                transferFile(originalPid, stagingUri, CdrDeposit.hasDatastreamOriginal);
                log.debug("Finished transferring original file for {}", originalPid.getQualifiedId());
            }
        }

        private void transferModsHistoryFile() {
            if (datastreamNotTransferred(hasDatastreamDescriptiveHistory)) {
                PID modsPid = DatastreamPids.getMdDescriptivePid(objPid);
                PID historyPid = DatastreamPids.getDatastreamHistoryPid(modsPid);

                Resource historyResc = DepositModelHelpers.getDatastream(resc, DatastreamType.MD_DESCRIPTIVE_HISTORY);

                URI stagingUri = URI.create(historyResc.getProperty(CdrDeposit.stagingLocation).getString());
                transferFile(historyPid, stagingUri, hasDatastreamDescriptiveHistory);
                log.debug("Finished transferring MODS history file {}", modsPid.getQualifiedId());
            }
        }

        private void transferFitsHistoryFile() {
            if (datastreamNotTransferred(CdrDeposit.hasDatastreamFitsHistory)) {
                Resource historyResc = DepositModelHelpers.getDatastream(
                        resc, DatastreamType.TECHNICAL_METADATA_HISTORY);
                PID fitsPid = DatastreamPids.getTechnicalMetadataPid(objPid);
                PID historyPid = DatastreamPids.getDatastreamHistoryPid(fitsPid);

                URI stagingUri = URI.create(historyResc.getProperty(CdrDeposit.stagingLocation).getString());
                transferFile(historyPid, stagingUri, CdrDeposit.hasDatastreamFitsHistory);
                log.debug("Finished transferring FITS history file {}", fitsPid.getQualifiedId());
            }
        }

        private void transferFitsExtract() {
            if (datastreamNotPresentOrTransferred(CdrDeposit.hasDatastreamFits)) {
                PID fitsPid = getTechnicalMetadataPid(objPid);

                Path fitsPath = getTechMdPath(objPid, false);
                if (Files.notExists(fitsPath)) {
                    failJob("Missing technical metadata datastream",
                            "Missing technical metadata datastream for FileObject " + objPid);
                }
                URI stagingUri = getTechMdPath(objPid, false).toUri();
                transferFile(fitsPid, stagingUri, CdrDeposit.hasDatastreamFits);
                log.debug("Finished transferring techmd file {}", fitsPid.getQualifiedId());
            }
        }

        private boolean datastreamNotPresentOrTransferred(Property datastreamProp) {
            return !resc.hasProperty(datastreamProp) ||
                   !resc.getPropertyResourceValue(datastreamProp)
                            .hasProperty(CdrDeposit.storageUri);
        }

        private boolean datastreamNotTransferred(Property datastreamProp) {
            return resc.hasProperty(datastreamProp) &&
                   !resc.getPropertyResourceValue(datastreamProp)
                            .hasProperty(CdrDeposit.storageUri);
        }

        private void transferDepositManifests() {
            List<Statement> manifestStmts = resc.listProperties(CdrDeposit.hasDatastreamManifest).toList();
            for (Statement manifestStmt : manifestStmts) {
                Resource manifestResc = manifestStmt.getResource();

                String manifestPath = manifestResc.getProperty(CdrDeposit.stagingLocation).getString();
                URI manifestUri = URI.create(manifestPath);
                File manifestFile = new File(manifestUri);
                if (!manifestFile.exists()) {
                    log.warn("Manifest {} does not exist, it may have already been transferred in deposit {}",
                            manifestPath, getDepositUUID());
                    continue;
                }

                PID manifestPid = getDepositManifestPid(objPid, manifestFile.getName());
                transferFile(manifestPid, manifestUri, CdrDeposit.hasDatastreamManifest);
            }
        }

        private void transferFile(PID binPid, URI stagingUri, Property datastreamProperty) {
            URI storageUri = null;
            String digest = null;
            Resource binResc = model.getResource(binPid.getRepositoryPath());

            // Already has storageUri, skip transfer
            if (binResc.hasProperty(CdrDeposit.storageUri)) {
                return;
            }

            log.debug("Transferring file from {} for {}", stagingUri, binPid.getQualifiedId());

            Statement digestStmt = binResc.getProperty(DEFAULT_ALGORITHM.getDepositProperty());
            try {
                BinaryTransferOutcome outcome = transferSession.transfer(binPid, stagingUri);
                digest = outcome.getSha1();
                storageUri = outcome.getDestinationUri();
            } catch (BinaryAlreadyExistsException e) {
                // Make sure a PID collision with an existing repository object isn't happening
                if (repoObjFactory.objectExists(binPid.getRepositoryUri())) {
                    failJob(e, "Cannot transfer binary {0}, an object with PID {1} already exists in the repository",
                            stagingUri, binPid.getQualifiedId());
                }
                // Check if the binary at the destination matches the staged copy
                if (transferSession.isTransferred(binPid, stagingUri)) {
                    // binary was previously fully transferred so all we need to do is record the destination uri
                    log.debug("Binary {} was already transferred, recording and moving on", binPid.getQualifiedId());
                    BinaryDetails details = transferSession.getStoredBinaryDetails(binPid);
                    storageUri = details.getDestinationUri();
                    digest = details.getDigest();
                } else {
                    // binary was not previously fully transferred, so retry with replacement enabled
                    log.debug("Retransferring file from {} for {} with replacement enabled",
                            stagingUri, binPid.getQualifiedId());
                    BinaryDetails details = transferSession.getStoredBinaryDetails(binPid);
                    // Clear out the old file
                    transferSession.delete(details.getDestinationUri());
                    // Transfer the new one
                    BinaryTransferOutcome outcome = transferSession.transfer(binPid, stagingUri);
                    storageUri = outcome.getDestinationUri();
                    digest = outcome.getSha1();
                }
            } finally {
                if (storageUri != null) {
                    assertProvidedDigestMatches(digestStmt, digest, binPid, stagingUri);

                    result.statements.add(ResourceFactory.createStatement(
                            binResc, CdrDeposit.storageUri, createStringLiteral(storageUri.toString())));
                    if (!binResc.hasProperty(DEFAULT_ALGORITHM.getDepositProperty())) {
                        result.statements.add(ResourceFactory.createStatement(
                                binResc, DEFAULT_ALGORITHM.getDepositProperty(), createStringLiteral(digest)));
                    }
                    if (!resc.hasProperty(datastreamProperty, binResc)) {
                        result.statements.add(ResourceFactory.createStatement(
                                resc, datastreamProperty, binResc));
                    }
                }
            }

            log.debug("Finished transferring file from {} to {}", stagingUri, storageUri);
        }
    }

    private class TransferBinariesResult {
        private List<Statement> statements = new ArrayList<>();
    }

    private void assertProvidedDigestMatches(Statement providedStmt, String generatedDigest,
            PID binPid, URI stagingUri) {
        if (providedStmt != null) {
            String provided = providedStmt.getString();
            if (!provided.equals(generatedDigest)) {
                throw new InvalidChecksumException("Checksum of copied file for " + binPid
                        + " from " + stagingUri + " did not match expected SHA1: expected "
                        + provided + ", calculated " + generatedDigest);
            }
        }
    }
}
