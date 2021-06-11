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

import static edu.unc.lib.deposit.work.DepositGraphUtils.getChildIterator;
import static edu.unc.lib.dl.model.DatastreamPids.getDatastreamHistoryPid;
import static edu.unc.lib.dl.model.DatastreamPids.getTechnicalMetadataPid;
import static edu.unc.lib.dl.model.DatastreamType.TECHNICAL_METADATA;
import static edu.unc.lib.dl.util.RedisWorkerConstants.DepositField.excludeDepositRecord;
import static edu.unc.lib.dl.xml.NamespaceConstants.FITS_URI;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.http.HttpStatus;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.NodeIterator;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.DC;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoOperationFailedException;
import org.fcrepo.client.FcrepoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import edu.unc.lib.deposit.validate.VerifyObjectsAreInFedoraService;
import edu.unc.lib.deposit.work.AbstractDepositJob;
import edu.unc.lib.deposit.work.DepositGraphUtils;
import edu.unc.lib.deposit.work.JobInterruptedException;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.ContentContainerObject;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.FedoraTransactionRefresher;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.ChecksumMismatchException;
import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.model.DatastreamType;
import edu.unc.lib.dl.persist.api.event.PremisEventBuilder;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.persist.services.edit.UpdateDescriptionService;
import edu.unc.lib.dl.persist.services.edit.UpdateDescriptionService.UpdateDescriptionRequest;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrAcl;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Fcrepo4Repository;
import edu.unc.lib.dl.rdf.IanaRelation;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.reporting.ActivityMetricsClient;
import edu.unc.lib.dl.util.DepositException;
import edu.unc.lib.dl.util.ObjectPersistenceException;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositField;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;

/**
 * Ingests all content objects in the deposit into the Fedora repository.
 *
 * @author bbpennel
 * @author harring
 *
 */
public class IngestContentObjectsJob extends AbstractDepositJob {
    private static final Logger log = LoggerFactory.getLogger(IngestContentObjectsJob.class);

    private static final int CHECKSUM_RETRIES = 3;

    private boolean resumed;

    // The set of object paths from this deposit which already exist in the repository
    private Set<String> previouslyIngestedSet;

    @Autowired
    private ActivityMetricsClient metricsClient;

    @Autowired
    private AccessControlService aclService;

    @Autowired
    private FcrepoClient fcrepoClient;

    @Autowired
    private RepositoryObjectFactory repoObjFactory;

    @Autowired
    private RepositoryObjectLoader repoObjLoader;

    @Autowired
    private TransactionManager txManager;

    private BinaryTransferSession logTransferSession;

    @Autowired
    private VerifyObjectsAreInFedoraService verificationService;

    @Autowired
    private UpdateDescriptionService updateDescService;

    private AccessGroupSet groupSet;
    private AgentPrincipals agent;

    private boolean skipDepositLink;
    private Resource depositResc;

    private String depositor;
    private PID depositorPid;

    private boolean overrideTimestamps;

    public IngestContentObjectsJob() {
        super();
    }

    public IngestContentObjectsJob(String uuid, String depositUUID) {
        super(uuid, depositUUID);
    }

    /**
     * Determines how many operations will be required to complete this deposit.
     *
     * @param depositBag
     */
    private void calculateWorkRemaining(Bag depositBag) {
        List<String> contentPaths = new ArrayList<>();
        DepositGraphUtils.walkChildrenDepthFirst(depositBag, contentPaths, true);
        // Number of actions is the number of ingest objects plus deposit record
        setTotalClicks(contentPaths.size());

        // Deposit is restarting from part way through
        resumed = getDepositStatusFactory().isResumedDeposit(getDepositUUID());
        if (resumed) {
            populatePreviouslyIngested(contentPaths);
        }
    }

    /**
     * Generates a list of objects in this deposit which already exist in the
     * repository, updating the status of the job to indicate how much work is
     * left
     *
     * @param contentPaths
     */
    private void populatePreviouslyIngested(List<String> contentPaths) {
        // Generate a list of object paths from this deposit already in fedora
        previouslyIngestedSet = new HashSet<>();
        for (String path : contentPaths) {
            if (objectExists(PIDs.get(path))) {
                previouslyIngestedSet.add(path);
            }
        }
        // Update the ingested object count to reflect the number in the repo
        getJobStatusFactory().setCompletion(getJobUUID(), previouslyIngestedSet.size());
    }

    @Override
    public void runJob() {

        log.debug("Creating content AIPS for deposit {}", getDepositPID());

        Model model = getReadOnlyModel();

        PID destPid = getDestinationPID();

        // Retrieve the object where this deposit will be ingested to.
        Map<String, String> depositStatus = getDepositStatus();
        depositor = depositStatus.get(DepositField.depositorName.name());
        depositorPid = AgentPids.forPerson(depositor);

        RepositoryObject destObj = repoObjLoader.getRepositoryObject(destPid);
        if (!(destObj instanceof ContentContainerObject)) {
            failJob("Cannot add children to destination", "Cannot deposit to destination " + destPid
                    + ", types does not support children");
        }
        String groups = depositStatus.get(DepositField.permissionGroups.name());
        groupSet = new AccessGroupSet(groups);
        agent = new AgentPrincipals(depositor, groupSet);

        // Verify that the depositor is allowed to ingest to the given destination
        aclService.assertHasAccess(
                "Depositor does not have permissions to ingest to destination " + destPid,
                destPid, groupSet, Permission.ingest);

        Bag depositBag = model.getBag(getDepositPID().getRepositoryPath());

        calculateWorkRemaining(depositBag);

        // Mark the deposit as in progress, if it was not already
        if (!resumed) {
            getDepositStatusFactory().setIngestInprogress(getDepositUUID(), true);
        }

        skipDepositLink = Boolean.parseBoolean(depositStatus.get(excludeDepositRecord.name()));
        overrideTimestamps = Boolean.parseBoolean(depositStatus.get(DepositField.overrideTimestamps.name()));

        depositResc = createResource(getDepositPID().getRepositoryPath());

        // Ingest objects included in this deposit into the destination object
        try {
            logTransferSession = getTransferSession(model);

            ingestChildren((ContentContainerObject) destObj, depositBag);
            // Add ingestion event for the parent container
            addIngestionEventForDestination((ContentContainerObject) destObj, depositBag.asResource());
        } catch (DepositException | FedoraException | IOException e) {
            failJob(e, "Failed to ingest content for deposit {0}", getDepositPID().getQualifiedId());
        } finally {
            logTransferSession.close();
        }

        interruptJobIfStopped();

        // Verify objects from deposit are present in fcrepo
        Collection<String> pids = new ArrayList<>();
        DepositGraphUtils.walkChildrenDepthFirst(depositBag, pids, true);
        List<PID> objectsNotInFedora = verificationService.listObjectsNotInFedora(pids);
        if (objectsNotInFedora.size() > 0) {
            failJob("Some objects from this deposit didn't make it to Fedora:\n",
                    verificationService.listObjectPIDs(getDepositPID().getQualifiedId(), objectsNotInFedora));
        }
    }

    /**
     * Ingest the children of parentResc as children ContentObjects of destObj.
     *
     * @param destObj the repository object which children objects will be added to.
     * @param parentResc the parent resource where children will be listed from
     * @throws DepositException
     * @throws IOException
     */
    private void ingestChildren(ContentContainerObject destObj, Resource parentResc)
            throws DepositException, IOException {
        NodeIterator iterator = getChildIterator(parentResc);
        // No more children, nothing further to do in this tree
        if (iterator == null) {
            return;
        }

        try {
            while (iterator.hasNext()) {
                // Check that the deposit is still running before starting the next ingest
                interruptJobIfStopped();

                Resource childResc = (Resource) iterator.next();

                // Ingest the child according to its object type
                if (!childResc.hasProperty(RDF.type)
                        || childResc.hasProperty(RDF.type, Cdr.FileObject)) {
                    // Assume child is a file if no type is provided
                    if (destObj instanceof WorkObject) {
                        // File object is being added to a work, go ahead
                        ingestFileObject(destObj, parentResc, childResc);
                    }
                } else if (childResc.hasProperty(RDF.type, Cdr.Folder)) {
                    ingestFolder(destObj, parentResc, childResc);
                } else if (childResc.hasProperty(RDF.type, Cdr.Work)) {
                    ingestWork(destObj, parentResc, childResc);
                } else if (childResc.hasProperty(RDF.type, Cdr.Collection)) {
                    ingestCollection(destObj, parentResc, childResc);
                } else if (childResc.hasProperty(RDF.type, Cdr.AdminUnit)) {
                    ingestAdminUnit(destObj, parentResc, childResc);
                }
            }

            markObjectCompleted(destObj.getPid());
        } finally {
            iterator.close();
        }
    }

    /**
     * Ingests the object in childResc as a FileObject into an existing
     * WorkObject.
     *
     * @param parent
     * @param parentResc
     * @param childResc
     * @return
     * @throws DepositException
     * @throws IOException
     */
    private void ingestFileObject(ContentObject parent, Resource parentResc, Resource childResc)
            throws DepositException, IOException {

        if (skipResumed(childResc)) {
            return;
        }

        WorkObject work = (WorkObject) parent;

        FedoraTransaction tx = txManager.startTransaction();
        FedoraTransactionRefresher txRefresher = new FedoraTransactionRefresher(tx);
        PID pid = null;
        try {
            txRefresher.start();

            FileObject obj = addFileToWork(work, childResc);
            pid = obj.getPid();

            // Add ingestion event for file object
            addIngestionEventForChild(obj);
            addPremisEvents(obj);
            // add MODS
            addDescription(obj, childResc);

            overrideModifiedTimestamp(obj, childResc);
            log.debug("Finished all updates for file {} in work {}", pid, work.getPid());

            txRefresher.stop();
        } catch (Exception e) {
            txRefresher.interrupt();
            tx.cancelAndIgnore();
            throw e;
        } finally {
            tx.close();
        }

        addClicks(1);
        getDepositStatusFactory().incrIngestedObjects(getDepositUUID(), 1);
        log.info("Created file object {} for deposit {}", pid, getDepositPID());
    }

    /**
     * Ingests the object represented by childResc as a FileObject as the child
     * of the given WorkObject, with the file properties provided with
     * childResc.
     *
     * @param work
     * @param childResc
     *            if true, then acl and other properties from the child resource
     *            will be added to the file object's aip
     * @return
     * @throws DepositException
     */
    private FileObject addFileToWork(WorkObject work, Resource childResc)
            throws DepositException {
        log.debug("Adding file {} to work {}", childResc, work.getPid());
        PID childPid = PIDs.get(childResc.getURI());
        Resource originalResc = DepositModelHelpers.getDatastream(childResc);

        String storageString = originalResc != null ? getPropertyValue(originalResc, CdrDeposit.storageUri) : null;
        if (storageString == null) {
            // throw exception, child must be a file with a staging path
            throw new DepositException("No staging location provided for child ("
                    + childResc.getURI() + ") of Work object (" + work.getPid().getQualifiedId() + ")");
        }
        URI storageUri = URI.create(storageString);

        // Pull out file properties if they are present
        String mimetype = getPropertyValue(originalResc, CdrDeposit.mimetype);

        String sha1 = getPropertyValue(originalResc, CdrDeposit.sha1sum);
        String md5 = getPropertyValue(originalResc, CdrDeposit.md5sum);

        // Label comes from file object if not set for binary
        String label = getPropertyValue(originalResc, CdrDeposit.label);
        if (label == null) {
            label = getPropertyValue(childResc, CdrDeposit.label);
        }

        // Construct a model to store properties about this new fileObject
        Model aipModel = ModelFactory.createDefaultModel();
        Resource aResc = aipModel.getResource(childResc.getURI());

        addAclProperties(childResc, aResc);
        populateAIPProperties(childResc, aResc);

        // Add the file to the work as the datafile of its own FileObject
        FileObject fileObj = null;
        // Retry if there are checksum failures
        for (int retryCnt = 1; retryCnt <= CHECKSUM_RETRIES; retryCnt++) {
            try {
                fileObj = work.addDataFile(childPid, storageUri, label, mimetype, sha1, md5, aipModel);
                break;
            } catch (ChecksumMismatchException e) {
                if ((CHECKSUM_RETRIES - retryCnt) > 0) {
                    log.warn("Failed to ingest file {} due to a checksum mismatch, {} retries remaining: {}",
                            childPid.getQualifiedId(), CHECKSUM_RETRIES - retryCnt, e.getMessage());
                    try {
                        Thread.sleep(retryCnt * 1000);
                    } catch (InterruptedException ef) {
                        throw new JobInterruptedException(e.getMessage());
                    }
                } else {
                    failJob("Unable to ingest " + childPid.getQualifiedId(), e.getMessage());
                }
            }
        }

        // Record the size of the file for throughput stats
        if (storageUri.getScheme().equals("file")) {
            metricsClient.incrDepositFileThroughput(getDepositUUID(), Paths.get(storageUri).toFile().length());
        }

        // Add the FITS report for this file
        addFitsHistory(fileObj, childResc);
        addFitsReport(fileObj, childResc);

        return fileObj;
    }

    private void addFitsHistory(FileObject fileObj, Resource dResc) {
        Resource historyResc = DepositModelHelpers.getDatastream(dResc, DatastreamType.TECHNICAL_METADATA_HISTORY);
        if (historyResc == null || !historyResc.hasProperty(CdrDeposit.storageUri)) {
            return;
        }

        PID fitsPid = DatastreamPids.getTechnicalMetadataPid(fileObj.getPid());
        PID dsHistoryPid = getDatastreamHistoryPid(fitsPid);
        URI storageUri = URI.create(historyResc.getProperty(CdrDeposit.storageUri).getString());
        repoObjFactory.createOrUpdateBinary(dsHistoryPid,
                storageUri,
                DatastreamType.TECHNICAL_METADATA_HISTORY.getDefaultFilename(),
                DatastreamType.TECHNICAL_METADATA_HISTORY.getMimetype(),
                getPropertyValue(historyResc, CdrDeposit.sha1sum),
                getPropertyValue(historyResc, CdrDeposit.md5sum),
                null);
    }

    private void addFitsReport(FileObject fileObj, Resource resc) throws DepositException {
        Resource binResc = DepositModelHelpers.getDatastream(resc, DatastreamType.TECHNICAL_METADATA);
        if (binResc == null || !binResc.hasProperty(CdrDeposit.storageUri)) {
            failJob("Missing FITs extract", "No storage URI for FITS extract for " + fileObj.getPid().getId());
        }

        URI fitsUri = URI.create(binResc.getProperty(CdrDeposit.storageUri).getString());
        PID fitsPid = getTechnicalMetadataPid(fileObj.getPid());
        String sha1 = getPropertyValue(binResc, CdrDeposit.sha1sum);

        fileObj.addBinary(fitsPid, fitsUri, TECHNICAL_METADATA.getDefaultFilename(), TECHNICAL_METADATA.getMimetype(),
                sha1, null, IanaRelation.derivedfrom, DCTerms.conformsTo, createResource(FITS_URI));
    }

    /**
     * Ingest childResc as a FolderObject as a member of the provided parent object.
     *
     * @param parent
     * @param parentResc
     * @param childResc
     * @return
     * @throws DepositException
     * @throws IOException
     */
    private void ingestFolder(ContentContainerObject parent, Resource parentResc, Resource childResc)
            throws DepositException, IOException {

        PID childPid = PIDs.get(childResc.getURI());
        FolderObject obj = null;
        if (skipResumed(childResc)) {
            // Resuming, retrieve the existing folder object
            obj = repoObjLoader.getFolderObject(childPid);
        } else {
            // Create the new folder
            Model model = ModelFactory.createDefaultModel();
            Resource folderResc = model.getResource(childPid.getRepositoryPath());

            populateAIPProperties(childResc, folderResc);
            // Add acls to AIP
            addAclProperties(childResc, folderResc);

            FedoraTransaction tx = txManager.startTransaction();
            try {
                obj = repoObjFactory.createFolderObject(childPid, model);
                addIngestionEventForChild(obj);
                parent.addMember(obj);

                addDescription(obj, childResc);
                log.info("Created folder object {} for deposit {}", childPid, getDepositPID());
            } catch (Exception e) {
                tx.cancelAndIgnore();
                throw e;
            } finally {
                tx.close();
            }

            // Increment the count of objects deposited prior to adding children
            addClicks(1);
            getDepositStatusFactory().incrIngestedObjects(getDepositUUID(), 1);
        }

        // ingest children of the folder
        ingestChildren(obj, childResc);

        if (isObjectCompleted(obj.getPid())) {
            FedoraTransaction txPremis = txManager.startTransaction();

            try {
                // add ingestion event for the new folder
                addIngestionEventForContainer(obj, childResc);
                addPremisEvents(obj);
                overrideModifiedTimestamp(obj, childResc);
            } catch (Exception e) {
                txPremis.cancelAndIgnore();
                throw e;
            } finally {
                txPremis.close();
            }
        }
    }

    private void ingestAdminUnit(ContentContainerObject parent, Resource parentResc, Resource childResc)
            throws DepositException, IOException {

        PID childPid = PIDs.get(childResc.getURI());
        AdminUnit obj = null;
        if (skipResumed(childResc)) {
            // Resuming, retrieve the existing admin unit object
            obj = repoObjLoader.getAdminUnit(childPid);
        } else {
            aclService.assertHasAccess(
                    "Depositor does not have permissions to create admin units",
                    parent.getPid(), groupSet, Permission.createAdminUnit);

            // Create the new admin unit
            Model model = ModelFactory.createDefaultModel();
            Resource adminResc = model.getResource(childPid.getRepositoryPath());

            populateAIPProperties(childResc, adminResc);
            // Add acls to AIP
            addAclProperties(childResc, adminResc);

            FedoraTransaction tx = txManager.startTransaction();
            try {
                obj = repoObjFactory.createAdminUnit(childPid, model);
                addIngestionEventForChild(obj);
                parent.addMember(obj);

                addDescription(obj, childResc);
                log.info("Created admin unit {} for deposit {}", childPid, getDepositPID());
            } catch (Exception e) {
                tx.cancelAndIgnore();
                throw e;
            } finally {
                tx.close();
            }

            // Increment the count of objects deposited prior to adding children
            addClicks(1);
            getDepositStatusFactory().incrIngestedObjects(getDepositUUID(), 1);
        }

        // ingest children of the admin unit
        ingestChildren(obj, childResc);

        if (isObjectCompleted(obj.getPid())) {
            FedoraTransaction txPremis = txManager.startTransaction();

            try {
                // add ingestion event for the new folder
                addIngestionEventForContainer(obj, childResc);
                addPremisEvents(obj);
                overrideModifiedTimestamp(obj, childResc);
            } catch (Exception e) {
                txPremis.cancelAndIgnore();
                throw e;
            } finally {
                txPremis.close();
            }
        }
    }

    private void ingestCollection(ContentContainerObject parent, Resource parentResc, Resource childResc)
            throws DepositException, IOException {

        PID childPid = PIDs.get(childResc.getURI());
        CollectionObject obj = null;
        if (skipResumed(childResc)) {
            // Resuming, retrieve the existing collection unit object
            obj = repoObjLoader.getCollectionObject(childPid);
        } else {
            aclService.assertHasAccess(
                    "Depositor does not have permissions to create collections in " + parent.getPid(),
                    parent.getPid(), groupSet, Permission.createCollection);

            // Create the new collection
            Model model = ModelFactory.createDefaultModel();
            Resource collectionResc = model.getResource(childPid.getRepositoryPath());

            populateAIPProperties(childResc, collectionResc);
            // Add acls to AIP
            addAclProperties(childResc, collectionResc);

            FedoraTransaction tx = txManager.startTransaction();
            try {
                obj = repoObjFactory.createCollectionObject(childPid, model);
                addIngestionEventForChild(obj);
                parent.addMember(obj);

                addDescription(obj, childResc);
                log.info("Created collection {} for deposit {}", childPid, getDepositPID());
            } catch (Exception e) {
                tx.cancelAndIgnore();
                throw e;
            } finally {
                tx.close();
            }

            // Increment the count of objects deposited prior to adding children
            addClicks(1);
            getDepositStatusFactory().incrIngestedObjects(getDepositUUID(), 1);
        }

        // ingest children of the admin unit
        ingestChildren(obj, childResc);

        if (isObjectCompleted(obj.getPid())) {
            FedoraTransaction txPremis = txManager.startTransaction();

            try {
                // add ingestion event for the new folder
                addIngestionEventForContainer(obj, childResc);
                addPremisEvents(obj);
                overrideModifiedTimestamp(obj, childResc);
            } catch (Exception e) {
                txPremis.cancelAndIgnore();
                throw e;
            } finally {
                txPremis.close();
            }
        }
    }

    /**
     * Ingest childResc as a WorkObject containing all of its child objects, as
     * a member of the provided parent object. Establishes the primaryObject
     * relationship to one of its children if specified.
     *
     * @param parent
     * @param parentResc
     * @param childResc
     * @return
     * @throws DepositException
     * @throws IOException
     */
    private void ingestWork(ContentContainerObject parent, Resource parentResc, Resource childResc)
            throws DepositException, IOException {
        PID childPid = PIDs.get(childResc.getURI());

        WorkObject obj = null;
        boolean skip = skipResumed(childResc);
        if (!skip) {
            Model model = ModelFactory.createDefaultModel();
            Resource workResc = model.getResource(childPid.getRepositoryPath());

            populateAIPProperties(childResc, workResc);
            // Add acls to AIP
            addAclProperties(childResc, workResc);

            // send txid along with uris for the following actions
            FedoraTransaction tx = txManager.startTransaction();
            try {
                obj = repoObjFactory.createWorkObject(childPid, model);
                // Add ingestion event for the work itself
                addIngestionEventForChild(obj);
                parent.addMember(obj);

                addDescription(obj, childResc);

                log.info("Created work object {} for deposit {}", childPid, getDepositPID());
            } catch (Exception e) {
                tx.cancelAndIgnore();
                throw e;
            } finally {
                tx.close();
            }

            addClicks(1);
            getDepositStatusFactory().incrIngestedObjects(getDepositUUID(), 1);

        }
        // Get non-transactional instance of the work
        obj = repoObjLoader.getWorkObject(childPid);
        ingestChildren(obj, childResc);

        log.debug("Ingested children for work {}", obj.getPid());

        // Avoid adding primaryObject relation for a resuming deposit if already present
        if (!skip || !obj.getResource().hasProperty(Cdr.primaryObject)) {
            // Set the primary object for this work if one was specified
            addPrimaryObject(obj, childResc);
        }

        if (isObjectCompleted(obj.getPid())) {
            FedoraTransaction txPremis = txManager.startTransaction();
            try {
                // add ingestion event for the new folder
                addIngestionEventForContainer(obj, childResc);
                addPremisEvents(obj);
                overrideModifiedTimestamp(obj, childResc);
            } catch (Exception e) {
                txPremis.cancelAndIgnore();
                throw e;
            } finally {
                txPremis.close();
            }
            log.debug("Finished all updates for work {}", obj.getPid());
        }
    }

    private void addPrimaryObject(WorkObject obj, Resource childResc) {
        Statement primaryStmt = childResc.getProperty(Cdr.primaryObject);
        if (primaryStmt != null) {
            String primaryPath = primaryStmt.getResource().getURI();
            obj.setPrimaryObject(PIDs.get(primaryPath));
        }
    }

    private void populateAIPProperties(Resource dResc, Resource aResc) {
        String label = getPropertyValue(dResc, CdrDeposit.label);
        if (label != null) {
            aResc.addProperty(DC.title, label);
        }
        if (dResc.hasProperty(CdrDeposit.originalDeposit)) {
            // Assign deposit record from provided original deposit resource
            aResc.addProperty(Cdr.originalDeposit,
                    dResc.getProperty(CdrDeposit.originalDeposit).getResource());
        } else if (!skipDepositLink) {
            // default to linking to the current deposit since no override provided
            aResc.addProperty(Cdr.originalDeposit, depositResc);
        }
        if (overrideTimestamps && dResc.hasProperty(CdrDeposit.createTime)) {
            String val = dResc.getProperty(CdrDeposit.createTime).getString();
            Literal createdLiteral = aResc.getModel().createTypedLiteral(val, XSDDatatype.XSDdateTime);
            aResc.addLiteral(Fcrepo4Repository.created, createdLiteral);
        }
        if (dResc.hasProperty(Cdr.storageLocation)) {
            String val = dResc.getProperty(Cdr.storageLocation).getString();
            aResc.addLiteral(Cdr.storageLocation, val);
        }
    }

    private void overrideModifiedTimestamp(ContentObject contentObj, Resource dResc) {
        if (overrideTimestamps && dResc.hasProperty(CdrDeposit.lastModifiedTime)) {
            String val = dResc.getProperty(CdrDeposit.lastModifiedTime).getString();
            Literal modifiedLiteral = dResc.getModel().createTypedLiteral(val, XSDDatatype.XSDdateTime);
            repoObjFactory.createExclusiveRelationship(contentObj, Fcrepo4Repository.lastModified, modifiedLiteral);
        }
    }

    /**
     * Returns true if the resource represents an object that does not need to
     * be deposited due to having been previously ingested
     *
     * @param resc
     * @return
     */
    private boolean skipResumed(Resource resc) {
        if (!resumed) {
            return false;
        }
        String path = resc.getURI();
        return previouslyIngestedSet.contains(path);
    }

    private void addAclProperties(Resource dResc, Resource aResc) {
        StmtIterator stmtIt = dResc.listProperties();

        while (stmtIt.hasNext()) {
            Statement stmt = stmtIt.nextStatement();
            Property pred = stmt.getPredicate();
            if (!CdrAcl.NS.equals(pred.getNameSpace())) {
                continue;
            }

            aResc.addProperty(pred, stmt.getObject());
        }
    }

    private void addDescription(ContentObject obj, Resource dResc) throws IOException {
        addDescriptionHistory(obj, dResc);

        Path modsPath = getModsPath(obj.getPid());
        if (!Files.exists(modsPath)) {
            return;
        }

        InputStream modsStream = Files.newInputStream(modsPath);
        updateDescService.updateDescription(
                new UpdateDescriptionRequest(agent, obj, modsStream).withTransferSession(logTransferSession));
    }

    private void addDescriptionHistory(ContentObject obj, Resource dResc) throws IOException {
        Resource historyResc = DepositModelHelpers.getDatastream(dResc, DatastreamType.MD_DESCRIPTIVE_HISTORY);
        if (historyResc == null || !historyResc.hasProperty(CdrDeposit.storageUri)) {
            return;
        }

        PID modsPid = DatastreamPids.getMdDescriptivePid(obj.getPid());
        PID dsHistoryPid = getDatastreamHistoryPid(modsPid);
        URI storageUri = URI.create(historyResc.getProperty(CdrDeposit.storageUri).getString());
        repoObjFactory.createOrUpdateBinary(dsHistoryPid,
                storageUri,
                DatastreamType.MD_DESCRIPTIVE_HISTORY.getDefaultFilename(),
                DatastreamType.MD_DESCRIPTIVE_HISTORY.getMimetype(),
                getPropertyValue(historyResc, CdrDeposit.sha1sum),
                getPropertyValue(historyResc, CdrDeposit.md5sum),
                null);
    }

    private void addPremisEvents(ContentObject obj) {
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

    private void addIngestionEventForDestination(ContentContainerObject obj, Resource parentResc) throws IOException {
        addIngestionEventForContainer(obj, parentResc, true);
    }

    private void addIngestionEventForContainer(ContentContainerObject obj, Resource parentResc) throws IOException {
        addIngestionEventForContainer(obj, parentResc, false);
    }

    private void addIngestionEventForContainer(ContentContainerObject obj, Resource parentResc, boolean isDestination)
            throws IOException {
        NodeIterator childIt = getChildIterator(parentResc);
        int numChildren = 0;
        PID childPid = null;
        while (childIt.hasNext()) {
            Resource resc = (Resource) childIt.next();
            // we need the pid only if there is exactly one child
            if (numChildren < 1) {
                childPid = PIDs.get(resc.getURI());
            }
            numChildren++;
        }
        // don't add event unless at least one child is ingested
        if (numChildren > 0) {
            // If target for this event is the existing destination object, then write to live log
            try (PremisLogger premisLogger = isDestination ? obj.getPremisLog() : getPremisLogger(obj.getPid())) {
                PremisEventBuilder builder = premisLogger.buildEvent(Premis.Ingestion);
                if (numChildren == 1 && childPid != null) {
                    builder.addEventDetail("added child object {0} to this container",
                            childPid.toString());
                } else {
                    builder.addEventDetail("added {0} child objects to this container", numChildren);
                }
                builder.addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.depositService))
                        .addAuthorizingAgent(depositorPid)
                        .write();
            }
        }
    }

    private void addIngestionEventForChild(ContentObject obj) throws IOException {
        PremisLogger premisLogger = getPremisLogger(obj.getPid());
        PremisEventBuilder builder = premisLogger.buildEvent(Premis.Ingestion);

        if (obj instanceof FileObject) {
            builder.addEventDetail("ingested as PID: {0}\n ingested as filename: {1}",
                    obj.getPid().getQualifiedId(), ((FileObject) obj).getOriginalFile().getFilename());
        } else if (obj instanceof ContentContainerObject) {
            builder.addEventDetail("ingested as PID: {0}",
                    obj.getPid().getQualifiedId());
        }
        builder.addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.depositService))
                .addAuthorizingAgent(depositorPid)
                .write();
    }

    private boolean objectExists(PID pid) {
        try (FcrepoResponse response = fcrepoClient.head(pid.getRepositoryUri())
                .perform()) {
            return true;
        } catch (IOException e) {
            throw new FedoraException("Failed to close HEAD response for " + pid, e);
        } catch (FcrepoOperationFailedException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return false;
            }
            throw new FedoraException("Failed to check on object " + pid
                    + " during initialization", e);
        }
    }
}
