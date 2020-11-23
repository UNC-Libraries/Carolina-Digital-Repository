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
package edu.unc.lib.dcr.migration.content;

import static edu.unc.lib.dcr.migration.MigrationConstants.convertBxc3RefToPid;
import static edu.unc.lib.dcr.migration.MigrationConstants.toBxc3Uri;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.CDRProperty.defaultWebObject;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.CDRProperty.hasSourceMimeType;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.ContentModel.AGGREGATE_WORK;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.ContentModel.COLLECTION;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.ContentModel.CONTAINER;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.ContentModel.SIMPLE;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.FedoraProperty.createdDate;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.FedoraProperty.hasModel;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.FedoraProperty.lastModifiedDate;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.Relationship.contains;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.Relationship.originalDeposit;
import static edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers.DC_DS;
import static edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers.MODS_DS;
import static edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers.ORIGINAL_DS;
import static edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers.getObjectModel;
import static edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers.listDatastreamVersions;
import static edu.unc.lib.dcr.migration.paths.PathIndex.OBJECT_TYPE;
import static edu.unc.lib.dcr.migration.paths.PathIndex.ORIGINAL_TYPE;
import static edu.unc.lib.dl.fcrepo4.RepositoryPathConstants.DEPOSIT_RECORD_BASE;
import static edu.unc.lib.dl.model.DatastreamType.ORIGINAL_FILE;
import static edu.unc.lib.dl.rdf.CdrDeposit.stagingLocation;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.DC_NS;
import static edu.unc.lib.dl.xml.SecureXMLFactory.createSAXBuilder;
import static java.nio.file.Files.newInputStream;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.substringAfterLast;
import static org.apache.jena.rdf.model.ModelFactory.createDefaultModel;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.slf4j.LoggerFactory.getLogger;
import static org.springframework.util.CollectionUtils.isEmpty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.DC;
import org.apache.jena.vocabulary.RDF;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.output.XMLOutputter;
import org.slf4j.Logger;

import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.FedoraProperty;
import edu.unc.lib.dcr.migration.fcrepo3.DatastreamVersion;
import edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers;
import edu.unc.lib.dcr.migration.paths.PathIndex;
import edu.unc.lib.dcr.migration.premis.ContentPremisToRdfTransformer;
import edu.unc.lib.dl.event.PremisLogger;
import edu.unc.lib.dl.event.PremisLoggerFactory;
import edu.unc.lib.dl.exceptions.RepositoryException;
import edu.unc.lib.dl.fcrepo4.DepositRecord;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.ConflictException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.model.DatastreamType;
import edu.unc.lib.dl.persist.services.deposit.DepositDirectoryManager;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.persist.services.deposit.DepositModelManager;
import edu.unc.lib.dl.persist.services.versioning.DatastreamHistoryLog;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Fcrepo4Repository;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.DateTimeUtil;
import edu.unc.lib.dl.util.DepositMethod;
import edu.unc.lib.dl.util.PackagingType;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;
import edu.unc.lib.dl.xml.SecureXMLFactory;

/**
 * Action to transform a content object from bxc3 into a depositable structure
 * for bxc5.
 *
 * @author bbpennel
 */
public class ContentObjectTransformer extends RecursiveAction {

    private static final long serialVersionUID = 1L;

    private static final Logger log = getLogger(ContentObjectTransformer.class);

    private static final Set<PID> createdDepositRecords = ConcurrentHashMap.newKeySet();

    private PathIndex pathIndex;
    private DepositModelManager modelManager;
    private ContentObjectTransformerManager manager;
    private RepositoryPIDMinter pidMinter;
    private DepositDirectoryManager directoryManager;
    private PremisLoggerFactory premisLoggerFactory;
    private RepositoryObjectFactory repoObjFactory;

    private PID originalPid;
    private PID newPid;
    private PID parentPid;
    private Resource parentType;

    private Document foxml;

    private ContentTransformationOptions options;

    /**
     *
     */
    public ContentObjectTransformer(PID originalPid, PID newPid, PID parentPid, Resource parentType) {
        this.originalPid = originalPid;
        this.newPid = newPid;
        this.parentPid = parentPid;
        this.parentType = parentType;
    }

    @Override
    protected void compute() {
        try {
            transform();
        } finally {
            manager.registerCompletion();
        }
    }

    private void transform() {
        log.info("Tranforming {}", originalPid.getId());
        Path foxmlPath = pathIndex.getPath(originalPid);
        if (foxmlPath == null) {
            log.warn("Unable to find foxml for {}", originalPid.getId());
            ContentTransformationReport.missingFoxml.incrementAndGet();
            return;
        }

        // Deserialize the foxml document
        try {
            foxml = createSAXBuilder().build(newInputStream(foxmlPath));
        } catch (IOException | JDOMException e) {
            throw new RepositoryException("Failed to read FOXML for " + originalPid, e);
        }

        // Retrieve all properties/relationships for the object
        Model model = getObjectModel(foxml);
        Resource bxc3Resc = model.getResource(toBxc3Uri(originalPid));

        if (isMarkedForDeletion(bxc3Resc)) {
            log.info("Skipping transformation of object {}, it is marked for deletion", originalPid);
            ContentTransformationReport.skippedDeleted.incrementAndGet();
            return;
        }

        // Determine what type of resource this should be in boxc5
        Resource resourceType = getResourceType(bxc3Resc);

        Model depositModel = createDefaultModel();

        if (resourceType.equals(Cdr.FileObject)) {
            populateFileObject(bxc3Resc, depositModel);
        } else {
            populateContainerObject(bxc3Resc, resourceType, depositModel);
        }

        Resource depResc = depositModel.getResource(newPid.getRepositoryPath());

        populateTimestamps(bxc3Resc, depResc);
        try {
            populateOriginalDeposit(bxc3Resc, depResc);
        } catch (IOException e) {
            throw new RepositoryException("Failed to create deposit record for " + originalPid, e);
        }

        // set patron access
        ACLTransformationHelpers.transformPatronAccess(bxc3Resc, depResc, parentPid);

        // copy most recent MODS to deposit directory
        extractMods(depResc);

        // Push triples for this object to the shared model for this deposit
        modelManager.addTriples(options.getDepositPid(), depositModel, newPid, parentPid);

        ContentTransformationReport.recordObjectTransformed(resourceType);
    }

    private void populateTimestamps(Resource bxc3Resc, Resource depResc) {
        if (!depResc.hasProperty(CdrDeposit.createTime)) {
            String created = bxc3Resc.getProperty(createdDate.getProperty()).getString();
            depResc.addLiteral(CdrDeposit.createTime, created);
        }
        if (!depResc.hasProperty(CdrDeposit.lastModifiedTime)) {
            String updated = bxc3Resc.getProperty(lastModifiedDate.getProperty()).getString();
            depResc.addLiteral(CdrDeposit.lastModifiedTime, updated);
        }
    }

    private void populateOriginalDeposit(Resource bxc3Resc, Resource depResc) throws IOException {
        Statement originalDepStmt = bxc3Resc.getProperty(originalDeposit.getProperty());
        if (originalDepStmt == null) {
            return;
        }
        PID originalDepPid = convertBxc3RefToPid(DEPOSIT_RECORD_BASE, originalDepStmt.getResource().getURI());
        depResc.addProperty(CdrDeposit.originalDeposit, createResource(originalDepPid.getRepositoryPath()));

        if (options.isCreateMissingDepositRecords()) {
            Path depRecPath = pathIndex.getPath(originalDepPid, OBJECT_TYPE);
            if (depRecPath == null && !createdDepositRecords.contains(originalDepPid)) {
                createdDepositRecords.add(originalDepPid);
                if (!options.isDryRun()) {
                    createDepositRecord(bxc3Resc, originalDepPid);
                }
                ContentTransformationReport.generatedDepositRecords.incrementAndGet();
            }
        }
    }

    private void createDepositRecord(Resource bxc3Resc, PID originalDepPid) throws IOException {
        log.info("Generating deposit record for missing referenced deposit {}", originalDepPid.getId());
        Model depRecModel = createDefaultModel();
        Resource depRecResc = depRecModel.getResource(originalDepPid.getRepositoryPath());
        depRecResc.addProperty(RDF.type, Cdr.DepositRecord);
        depRecResc.addLiteral(DC.title, "Deposit Record " + originalDepPid.getId());
        // Set the date created for the deposit record to that of the object being migrated
        String val = bxc3Resc.getProperty(FedoraProperty.createdDate.getProperty()).getString();
        depRecResc.addProperty(Fcrepo4Repository.created, val, XSDDatatype.XSDdateTime);
        depRecResc.addLiteral(Cdr.depositMethod, DepositMethod.BXC3_TO_5_MIGRATION_UTIL.getLabel());
        depRecResc.addLiteral(Cdr.depositPackageType, PackagingType.BXC3_TO_5_MIGRATION.getUri());
        depRecResc.addLiteral(Cdr.storageLocation, options.getDefaultStorageLocation());

        Path transformedPremisPath = Files.createTempFile("premis", ".xml");
        try {
            DepositRecord depRecord = repoObjFactory.createDepositRecord(originalDepPid, depRecModel);

            PremisLogger filePremisLogger = premisLoggerFactory.createPremisLogger(
                    originalDepPid, transformedPremisPath.toFile());

            // Add migration event
            filePremisLogger.buildEvent(Premis.Ingestion)
                    .addEventDetail("Deposit record generated by Boxc 3 to 5 migration")
                    .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.migrationUtil))
                    .writeAndClose();

            PremisLogger repoPremisLogger = premisLoggerFactory.createPremisLogger(depRecord);
            repoPremisLogger.createLog(Files.newInputStream(transformedPremisPath));
        } catch (ConflictException e) {
            log.debug("Deposit record {} has already been created", originalDepPid);
        } finally {
            Files.delete(transformedPremisPath);
        }
    }

    private void populateContainerObject(Resource bxc3Resc, Resource resourceType, Model depositModel) {
        Bag containerBag = depositModel.createBag(newPid.getRepositoryPath());
        containerBag.addProperty(RDF.type, resourceType);

        Map<PID, PID> oldToNewPids = new HashMap<>();
        if (!options.isSkipMembers()) {
            List<PID> contained = listContained(bxc3Resc);
            for (PID containedPid : contained) {
                // Determine PID to use for transformed child, in case we are generating or preserving ids.
                PID newContainedPid = manager.getTransformedPid(containedPid);
                oldToNewPids.put(containedPid, newContainedPid);

                // Spawn and execute transformer for children
                manager.createTransformer(containedPid, newContainedPid, newPid, resourceType)
                       .fork();
            }
        }

        if (Cdr.Work.equals(resourceType)) {
            Statement dwoStmt = bxc3Resc.getProperty(defaultWebObject.getProperty());
            if (dwoStmt != null) {
                PID primaryObjPid = convertBxc3RefToPid(dwoStmt.getResource());
                // Determine out the new pid of the primary object
                primaryObjPid = oldToNewPids.get(primaryObjPid);
                containerBag.addProperty(Cdr.primaryObject, createResource(primaryObjPid.getRepositoryPath()));
            }
        }

        String label = getLabel(bxc3Resc, null);
        if (label != null) {
            containerBag.addLiteral(CdrDeposit.label, label);
        }

        // transform PREMIS and copy to deposit directory
        transformPremis(originalPid, newPid);

        // set staff access if a unit or collection
        if (Cdr.AdminUnit.equals(resourceType) || Cdr.Collection.equals(resourceType)) {
            ACLTransformationHelpers.transformStaffRoles(bxc3Resc, containerBag);
        }
    }

    private void populateFileObject(Resource bxc3Resc, Model depositModel) {
        Resource fileResc;
        PID filePid;
        Bag workBag = null;
        boolean isNewWork = false;
        if (Cdr.Work.equals(parentType)) {
            fileResc = depositModel.getResource(newPid.getRepositoryPath());
            filePid = newPid;
        } else {
            // Use the pid of the current object to make a new work object
            workBag = depositModel.createBag(newPid.getRepositoryPath());
            workBag.addProperty(RDF.type, Cdr.Work);

            // Build a new resource for the file object
            filePid = pidMinter.mintContentPid();
            fileResc = depositModel.getResource(filePid.getRepositoryPath());

            // Add the new file resource as the primary object of the work
            workBag.add(fileResc);
            workBag.addProperty(Cdr.primaryObject, fileResc);

            isNewWork = true;
            ContentTransformationReport.fileToWork.getAndIncrement();
        }

        fileResc.addProperty(RDF.type, Cdr.FileObject);

        Resource origResc = DepositModelHelpers.addDatastream(fileResc, ORIGINAL_FILE);

        Statement mimeTypeStmt = bxc3Resc.getProperty(hasSourceMimeType.getProperty());
        if (mimeTypeStmt != null) {
            String mimeType = mimeTypeStmt.getString();
            if (!mimeType.contains("cannot open")) {
                origResc.addProperty(CdrDeposit.mimetype, mimeTypeStmt.getString());
            } else {
                log.debug("Ignoring unusable mimetype for {}", originalPid);
            }
        }

        List<DatastreamVersion> originalVersions = listDatastreamVersions(foxml, ORIGINAL_DS);
        if (originalVersions.size() > 0) {
            DatastreamVersion lastV = originalVersions.get(originalVersions.size() - 1);
            addLiteralIfNotNull(origResc, CdrDeposit.md5sum, lastV.getMd5());
            addLiteralIfNotNull(fileResc, CdrDeposit.createTime, lastV.getCreated());
            addLiteralIfNotNull(fileResc, CdrDeposit.lastModifiedTime, lastV.getCreated());
        }

        // Populate the original file path
        Path originalPath = pathIndex.getPath(originalPid, ORIGINAL_TYPE);
        if (originalPath == null) {
            ContentTransformationReport.noOriginalDatastream.incrementAndGet();
            log.error("No original file path for {}", originalPid);
        } else {
            origResc.addLiteral(stagingLocation, originalPath.toUri().toString());
        }

        String label = getLabel(bxc3Resc, originalVersions);
        if (label != null) {
            fileResc.addLiteral(CdrDeposit.label, label);
            if (workBag != null) {
                workBag.addLiteral(CdrDeposit.label, label);
            }
        }

        // transform existing PREMIS as the events for the file object, rather than work
        transformPremis(originalPid, filePid);
        if (isNewWork) {
            addMigrationEvent(getPremisLogger(newPid));
        }

        addFitsHistory(filePid, fileResc);
    }

    private void addFitsHistory(PID filePid, Resource fileResc) {
        List<DatastreamVersion> fitsVersions = listDatastreamVersions(foxml, FoxmlDocumentHelpers.FITS_DS);
        if (fitsVersions != null && fitsVersions.size() > 0) {
            DatastreamVersion lastV = fitsVersions.get(fitsVersions.size() - 1);
            Path fitsPath = pathIndex.getPath(originalPid, PathIndex.FITS_TYPE);
            Document fitsDoc;
            try {
                fitsDoc = SecureXMLFactory.createSAXBuilder().build(fitsPath.toFile());
            } catch (JDOMException | IOException e) {
                throw new RepositoryException("Failed to create deposit record for " + originalPid, e);
            }
            PID fitsPid = DatastreamPids.getTechnicalMetadataPid(filePid);
            DatastreamHistoryLog dsHistory = new DatastreamHistoryLog(fitsPid);

            addHistoryVersion(dsHistory, lastV, fitsDoc.detachRootElement());
            writeHistoryFile(fitsPid, fileResc, DatastreamType.TECHNICAL_METADATA_HISTORY, dsHistory);
        }
    }

    private void transformPremis(PID bxc3Pid, PID bxc5Pid) {
        Path originalPremisPath = pathIndex.getPath(bxc3Pid, PathIndex.PREMIS_TYPE);
        if (originalPremisPath == null || !Files.exists(originalPremisPath)) {
            log.info("No premis for {}, skipping transformation", bxc3Pid.getId());
            ContentTransformationReport.noPremis.incrementAndGet();
            return;
        }

        PremisLogger premisLogger = getPremisLogger(bxc5Pid);
        ContentPremisToRdfTransformer premisTransformer =
                new ContentPremisToRdfTransformer(bxc5Pid, premisLogger, originalPremisPath);

        premisTransformer.compute();

        // Add migration event
        addMigrationEvent(premisLogger);
    }

    private PremisLogger getPremisLogger(PID bxc5Pid) {
        Path transformedPremisPath = directoryManager.getPremisPath(bxc5Pid);
        return premisLoggerFactory.createPremisLogger(bxc5Pid, transformedPremisPath.toFile());
    }

    private void addMigrationEvent(PremisLogger premisLogger) {
        premisLogger.buildEvent(Premis.Ingestion)
                .addEventDetail("Object migrated from Boxc 3 to Boxc 5")
                .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.migrationUtil))
                .writeAndClose();
    }

    private void addLiteralIfNotNull(Resource subjResc, Property property, Object object) {
        if (object != null) {
            subjResc.addLiteral(property, object);
        }
    }

    /**
     * Retrieve the label for this object, first pulling from dc:title, falling back to
     * the fedora label, then the DATA_FILE ALT_IDS.
     *
     * @param bxc3Resc
     * @param originalVersions
     * @return
     */
    private String getLabel(Resource bxc3Resc, List<DatastreamVersion> originalVersions) {
        List<DatastreamVersion> dcVersions = listDatastreamVersions(foxml, DC_DS);
        String filename;

        Statement labelStmt = bxc3Resc.getProperty(FedoraProperty.label.getProperty());
        if (labelStmt != null) {
            filename = labelStmt.getString();
            if (!isEmpty(filename)) {
                return filename;
            }
        }

        if (dcVersions != null && !dcVersions.isEmpty() ) {
            DatastreamVersion dcVersion = dcVersions.get(0);
            Element dcEl = dcVersion.getBodyEl();
            filename = dcEl.getChildTextTrim("title", DC_NS);
            if (!isEmpty(filename)) {
                return filename;
            }
        }

        if (!isEmpty(originalVersions)) {
            DatastreamVersion dsVersion = originalVersions.get(originalVersions.size() - 1);
            String altIds = dsVersion.getAltIds();
            if (altIds != null) {
                try {
                    URI altUri = URI.create(altIds);
                    String path = altUri.getPath();
                    filename = substringAfterLast(path, "/");
                    if (!isEmpty(filename)) {
                        return filename;
                    }
                } catch (IllegalArgumentException e) {
                    log.debug("Unable to parse alt id for {}", originalPid);
                }
            }
        }

        return null;
    }

    private Resource getResourceType(Resource bxc3Resc) {
        Set<Resource> contentModels = new HashSet<>();
        StmtIterator it = bxc3Resc.listProperties(hasModel.getProperty());
        while (it.hasNext()) {
            contentModels.add(it.next().getResource());
        }

        if (contentModels.contains(AGGREGATE_WORK.getResource())) {
            return Cdr.Work;
        }
        if (contentModels.contains(COLLECTION.getResource())) {
            if (Cdr.Collection.equals(parentType)) {
                log.warn("Parent object is a collection. Transforming current object {} from a collection " +
                                "into a folder", originalPid);
                ContentTransformationReport.collectionToFolder.incrementAndGet();
                return Cdr.Folder;
            } else if (Cdr.AdminUnit.equals(parentType) || !options.isTopLevelAsUnit()) {
                return Cdr.Collection;
            } else {
                return Cdr.AdminUnit;
            }
        }
        if (contentModels.contains(SIMPLE.getResource())) {
            return Cdr.FileObject;
        }
        if (contentModels.contains(CONTAINER.getResource())) {
            return Cdr.Folder;
        }

        throw new RepositoryException("Unsupported content type for " + bxc3Resc + " with models: " + contentModels);
    }

    private List<PID> listContained(Resource bxc3Resc) {
        List<PID> contained = new ArrayList<>();
        StmtIterator it = bxc3Resc.listProperties(contains.getProperty());
        while (it.hasNext()) {
            Resource containedResc = it.next().getResource();
            contained.add(convertBxc3RefToPid(containedResc));
        }
        return contained;
    }

    private void extractMods(Resource depResc) {
        log.debug("Checking for MODS {}", originalPid);
        List<DatastreamVersion> modsVersions = listDatastreamVersions(foxml, MODS_DS);
        if (modsVersions == null || modsVersions.isEmpty()) {
            log.debug("No MODS for {}" , originalPid);
            return;
        }

        log.info("Found mods for {}", originalPid);

        DatastreamVersion current = modsVersions.get(modsVersions.size() - 1);
        directoryManager.writeMods(newPid, current.getBodyEl());

        PID modsPid = DatastreamPids.getMdDescriptivePid(newPid);

        log.debug("Number of MODS versions {} for {}", modsVersions.size(), originalPid);

        // If more than one version, then put all versions but the last one in history
        if (modsVersions.size() > 1) {
            DatastreamHistoryLog dsHistory = new DatastreamHistoryLog(modsPid);
            for (int i = 0; i < modsVersions.size() - 1; i++) {
                DatastreamVersion dsVersion = modsVersions.get(i);
                addHistoryVersion(dsHistory, dsVersion, dsVersion.getBodyEl());
            }
            writeHistoryFile(modsPid, depResc, DatastreamType.MD_DESCRIPTIVE_HISTORY, dsHistory);
        }
    }

    private void addHistoryVersion(DatastreamHistoryLog dsHistory, DatastreamVersion versionBody, Element bodyEl) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            new XMLOutputter().output(bodyEl, bos);
            InputStream bodyInputStream = new ByteArrayInputStream(bos.toByteArray());
            Date created = DateTimeUtil.parseUTCToDate(versionBody.getCreated());
            dsHistory.addVersion(bodyInputStream, versionBody.getMimeType(), created);
        } catch (IOException e) {
            throw new RepositoryException("Failed to add history version", e);
        }
    }

    private void writeHistoryFile(PID dsPid, Resource parentResc, DatastreamType type, DatastreamHistoryLog dsHistory) {
        try (InputStream historyStream = dsHistory.toInputStream()) {
            Path historyPath = directoryManager.writeHistoryFile(newPid, type, dsHistory.toInputStream());
            Resource historyResc = DepositModelHelpers.addDatastream(parentResc, type);
            historyResc.addProperty(CdrDeposit.stagingLocation, historyPath.toUri().toString());

            log.debug("Wrote history to {} for {}", historyPath, newPid);
        } catch (IOException e) {
            throw new RepositoryException("Failed to write history for " + originalPid, e);
        }
    }

    private boolean isMarkedForDeletion(Resource resc) {
        return resc.hasLiteral(FedoraProperty.state.getProperty(), "Deleted");
    }

    public void setPathIndex(PathIndex pathIndex) {
        this.pathIndex = pathIndex;
    }

    public void setModelManager(DepositModelManager modelManager) {
        this.modelManager = modelManager;
    }

    public void setManager(ContentObjectTransformerManager manager) {
        this.manager = manager;
    }

    public void setPidMinter(RepositoryPIDMinter pidMinter) {
        this.pidMinter = pidMinter;
    }

    public void setDirectoryManager(DepositDirectoryManager directoryManager) {
        this.directoryManager = directoryManager;
    }

    public void setPremisLoggerFactory(PremisLoggerFactory premisLoggerFactory) {
        this.premisLoggerFactory = premisLoggerFactory;
    }

    public PID getPid() {
        return originalPid;
    }

    public void setRepositoryObjectFactory(RepositoryObjectFactory repoObjFactory) {
        this.repoObjFactory = repoObjFactory;
    }

    public void setOptions(ContentTransformationOptions options) {
        this.options = options;
    }
}
