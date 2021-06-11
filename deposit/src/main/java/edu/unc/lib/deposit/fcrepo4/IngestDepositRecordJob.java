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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.URI;
import java.util.Map;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import edu.unc.lib.deposit.work.AbstractDepositJob;
import edu.unc.lib.dl.fcrepo4.DepositRecord;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisEventBuilder;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.DcElements;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.rdf.Rdfs;
import edu.unc.lib.dl.util.DigestAlgorithm;
import edu.unc.lib.dl.util.ObjectPersistenceException;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositField;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;

/**
 * Creates and ingests the deposit record object
 *
 * @author bbpennel
 *
 */
public class IngestDepositRecordJob extends AbstractDepositJob {
    @Autowired
    private RepositoryObjectFactory repoObjFactory;
    @Autowired
    private RepositoryObjectLoader repoObjLoader;

    private BinaryTransferSession logTransferSession;

    private static final Logger log = LoggerFactory.getLogger(IngestDepositRecordJob.class);

    public IngestDepositRecordJob() {
        super();
    }

    public IngestDepositRecordJob(String uuid, String depositUUID) {
        super(uuid, depositUUID);
    }

    @Override
    public void runJob() {
        PID depositPID = getDepositPID();
        String depositUri = depositPID.getURI();

        log.debug("Creating record for deposit {}", depositUri);

        Model dModel = getReadOnlyModel();
        Map<String, String> status = getDepositStatus();

        Resource deposit = dModel.getResource(depositUri);

        // Create aip model for the deposit record
        Resource aipObjResc = makeDepositRecord(deposit, status);
        Model aipModel = aipObjResc.getModel();

        addIngestionEvent(aipObjResc, status);

        logTransferSession = getTransferSession(dModel);

        // Create the deposit record object in Fedora
        DepositRecord depositRecord;
        try {
            // In case of a resume, check if object already exists
            if (!repoObjFactory.objectExists(depositPID.getRepositoryUri())) {
                depositRecord = repoObjFactory.createDepositRecord(depositPID, aipModel);
            } else {
                depositRecord = repoObjLoader.getDepositRecord(depositPID);
            }

            addPremisEvents(depositRecord);

            // Add manifest files
            StmtIterator it = deposit.listProperties(CdrDeposit.hasDatastreamManifest);
            while (it.hasNext()) {
                Statement stmt = it.nextStatement();
                Resource manifestResc = stmt.getResource();
                String storageUri = getPropertyValue(manifestResc, CdrDeposit.storageUri);
                if (storageUri == null) {
                    log.error("No storage URI for deposit record manifest {}", manifestResc.getURI());
                    continue;
                }
                URI manifestUri = URI.create(storageUri);

                String sha1 = getPropertyValue(manifestResc, DigestAlgorithm.SHA1.getDepositProperty());
                String md5 = getPropertyValue(manifestResc, DigestAlgorithm.MD5.getDepositProperty());
                String mimetype = getPropertyValue(manifestResc, CdrDeposit.mimetype);
                mimetype = mimetype == null ? "text/plain" : mimetype;
                String filename = getPropertyValue(manifestResc, CdrDeposit.label);
                depositRecord.addManifest(manifestUri, filename, mimetype, sha1, md5);
            }
        } catch (FedoraException e) {
            failJob(e, "Failed to ingest deposit record {0}", depositPID);
        } finally {
            logTransferSession.close();
        }
    }

    /**
     * Generates a model containing the properties for this deposit record
     *
     * @param deposit
     * @param status
     * @return
     */
    private Resource makeDepositRecord(Resource deposit, Map<String, String> status) {
        Model aipModel = ModelFactory.createDefaultModel();

        Resource aipObjResc = aipModel.createResource(deposit.getURI());

        String filename = status.get(DepositField.fileName.name());
        String title = "Deposit record" + (filename == null ? "" : " for " + filename);
        aipObjResc.addProperty(DcElements.title, title);

        aipObjResc.addProperty(Rdfs.type, Cdr.DepositRecord);

        String method = status.get(DepositField.depositMethod.name());
        if (method != null) {
            aipObjResc.addProperty(Cdr.depositMethod, method);
        }
        String onBehalfOf = status.get(DepositField.depositorName.name());
        if (onBehalfOf != null) {
            aipObjResc.addProperty(Cdr.depositedOnBehalfOf, onBehalfOf);
        }
        String depositPackageType = status.get(DepositField.packagingType.name());
        if (depositPackageType != null) {
            aipObjResc.addProperty(Cdr.depositPackageType, depositPackageType);
        }
        String depositPackageProfile = status.get(DepositField.packageProfile.name());
        if (depositPackageProfile != null) {
            aipObjResc.addProperty(Cdr.depositPackageProfile, depositPackageProfile);
        }
        if (deposit.hasProperty(Cdr.storageLocation)) {
            String val = deposit.getProperty(Cdr.storageLocation).getString();
            aipObjResc.addLiteral(Cdr.storageLocation, val);
        }

        return aipObjResc;
    }

    // Add ingestion event to PREMIS log
    private void addIngestionEvent(Resource aipObjResc, Map<String, String> status) {
        PremisLogger premisDepositLogger = getPremisLogger(depositPID);
        PremisEventBuilder eventBuilder = premisDepositLogger.buildEvent(Premis.Ingestion)
                .addEventDetail("ingested as PID: {0}. {1}", depositPID.getId(),
                        aipObjResc.getProperty(DcElements.title).getObject().toString())
                .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.depositService))
                .addAuthorizingAgent(AgentPids.forPerson(status.get(DepositField.depositorName.name())));

        // Add in deposit format if present
        String depositFormat = null;
        String depositPackageType = status.get(DepositField.packagingType.name());
        if (depositPackageType != null) {
            depositFormat = depositPackageType;
            String depositPackageProfile = status.get(DepositField.packageProfile.name());
            if (depositPackageProfile != null) {
                depositFormat += " with profile " + depositPackageProfile;
            }
            eventBuilder.addEventDetail("ingested as format: {0}", depositFormat);
        }

        eventBuilder.write();
    }

    private void addPremisEvents(RepositoryObject obj) {
        File premisFile = getPremisFile(obj.getPid());
        if (!premisFile.exists()) {
            return;
        }

        PremisLogger repoPremisLogger = premisLoggerFactory.createPremisLogger(obj, logTransferSession);
        try {
            repoPremisLogger.createLog(new FileInputStream(premisFile));
        } catch (FileNotFoundException e) {
            throw new ObjectPersistenceException("Cannot find premis file " + premisFile, e);
        }
    }
}
