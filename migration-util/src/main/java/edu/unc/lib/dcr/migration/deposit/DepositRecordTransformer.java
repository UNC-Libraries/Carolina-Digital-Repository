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

import static edu.unc.lib.dcr.migration.MigrationConstants.toBxc3Uri;
import static edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers.getObjectModel;
import static edu.unc.lib.dcr.migration.paths.PathIndex.MANIFEST_TYPE;
import static edu.unc.lib.dl.model.DatastreamPids.getDepositManifestPid;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.FOXML_NS;
import static edu.unc.lib.dl.xml.SecureXMLFactory.createSAXBuilder;
import static java.nio.file.Files.newInputStream;
import static org.apache.jena.rdf.model.ModelFactory.createDefaultModel;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RecursiveAction;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.DC;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.client.FcrepoOperationFailedException;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.slf4j.Logger;

import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.CDRProperty;
import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.ContentModel;
import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.FedoraProperty;
import edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers;
import edu.unc.lib.dcr.migration.paths.PathIndex;
import edu.unc.lib.dcr.migration.premis.DepositRecordPremisToRdfTransformer;
import edu.unc.lib.dl.exceptions.RepositoryException;
import edu.unc.lib.dl.fcrepo4.DepositRecord;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.Fcrepo4Repository;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;

/**
 * Action to transform a deposit record from bxc3 into a bxc5 repository object.
 *
 * @author bbpennel
 */
public class DepositRecordTransformer extends RecursiveAction {

    private static final long serialVersionUID = 1L;

    private static final Logger log = getLogger(DepositRecordTransformer.class);

    private PathIndex pathIndex;

    private PremisLoggerFactory premisLoggerFactory;

    private RepositoryObjectFactory repoObjFactory;

    private BinaryTransferSession transferSession;

    private PID bxc3Pid;
    private PID bxc5Pid;

    private Document foxml;

    public DepositRecordTransformer(PID bxc3Pid, PID bxc5Pid, BinaryTransferSession transferSession) {
        this.bxc3Pid = bxc3Pid;
        this.bxc5Pid = bxc5Pid;
        this.transferSession = transferSession;
    }

    @Override
    protected void compute() {
        log.info("Tranforming deposit record {}", bxc3Pid.getId());
        Path foxmlPath = pathIndex.getPath(bxc3Pid);
        if (foxmlPath == null) {
            throw new RepositoryException("Unable to find foxml for " + bxc3Pid.getId());
        }

        // Deserialize the foxml document
        try {
            foxml = createSAXBuilder().build(newInputStream(foxmlPath));
        } catch (IOException | JDOMException e) {
            throw new RepositoryException("Failed to read FOXML for " + bxc3Pid, e);
        }

        // Retrieve all properties/relationships for the object
        Model bxc3Model = getObjectModel(foxml);
        Resource bxc3Resc = bxc3Model.getResource(toBxc3Uri(bxc3Pid));

        if (!isDepositRecord(bxc3Resc)) {
            throw new RepositoryException("Skipping transformation of object " + bxc3Pid
                    + ", it is not a deposit record");
        }

        if (isHycRecordType(bxc3Resc)) {
            log.info("Record {} has been moved to the CDR. Transformation skipped.", bxc3Pid.getId());
            return;
        }

        Model bxc5Model = createDefaultModel();
        Resource bxc5Resc = bxc5Model.getResource(bxc5Pid.getRepositoryPath());
        bxc5Resc.addProperty(RDF.type, Cdr.DepositRecord);

        populateDepositProperties(bxc3Resc, bxc5Resc);

        try {
            log.info("Ingesting deposit record {} as {}", bxc3Pid.getId(), bxc5Pid.getRepositoryPath());
            DepositRecord depRecord = repoObjFactory.createDepositRecord(bxc5Pid, bxc5Model);

            log.debug("Adding manifests for {}", bxc3Pid.getId());
            addManifests();
            log.debug("Transforming premis for {}", bxc3Pid.getId());
            transformAndPopulatePremis(depRecord);
            // Need this to be last
            log.debug("Overriding modification time for {}", bxc3Pid.getId());
            overrideLastModified(bxc3Resc, depRecord);
        } catch (FedoraException e) {
            if (e.getCause() instanceof FcrepoOperationFailedException) {
                FcrepoOperationFailedException cause = (FcrepoOperationFailedException) e.getCause();
                if (cause.getStatusCode() == HttpStatus.SC_CONFLICT) {
                    log.warn("Skipping deposit record {} created from {}, conflict during creation",
                            bxc5Pid.getQualifiedId(), bxc3Pid.getId());
                    return;
                }
            }
            throw e;
        }

        log.debug("Finished ingest of deposit record {}", bxc3Pid.getId());
    }

    private boolean isDepositRecord(Resource bxc3Resc) {
        return bxc3Resc.hasProperty(FedoraProperty.hasModel.getProperty(),
                ContentModel.DEPOSIT_RECORD.getResource());
    }

    private boolean isHycRecordType(Resource bxc3Resc) {
        Statement depositPackagingType = bxc3Resc.getProperty(CDRProperty.depositPackageType.getProperty());

        if (depositPackagingType == null) {
            return false;
        }

        String packagingType;
        if (depositPackagingType.getObject().isResource()) {
            packagingType = depositPackagingType.getResource().getURI();
        } else {
            packagingType = depositPackagingType.getString();
        }

        return (packagingType.equals("http://proquest.com") ||
                packagingType.equals("http://purl.org/net/sword-types/METSDSpaceSIP") ||
                packagingType.equals("http://purl.org/net/sword/terms/METSDSpaceSIP"));
    }

    private void overrideLastModified(Resource bxc3Resc, DepositRecord depRec) {
        String val = bxc3Resc.getProperty(FedoraProperty.lastModifiedDate.getProperty()).getString();
        Literal modifiedLiteral = depRec.getModel().createTypedLiteral(val, XSDDatatype.XSDdateTime);
        repoObjFactory.createExclusiveRelationship(depRec, Fcrepo4Repository.lastModified, modifiedLiteral);
    }

    private void populateDepositProperties(Resource bxc3Resc, Resource bxc5Resc) {
        String fedoraLabel = bxc3Resc.getProperty(FedoraProperty.label.getProperty()).getString();
        if (StringUtils.isBlank(fedoraLabel)) {
            bxc5Resc.addLiteral(DC.title, "Deposit Record " + bxc5Pid.getId());
        } else {
            bxc5Resc.addLiteral(DC.title, fedoraLabel);
        }
        if (bxc3Resc.hasProperty(FedoraProperty.createdDate.getProperty())) {
            String val = bxc3Resc.getProperty(FedoraProperty.createdDate.getProperty()).getString();
            bxc5Resc.addProperty(Fcrepo4Repository.created, val, XSDDatatype.XSDdateTime);
        }
        addLiteralIfPresent(bxc3Resc, CDRProperty.depositedOnBehalfOf.getProperty(),
                bxc5Resc, Cdr.depositedOnBehalfOf);
        addLiteralIfPresent(bxc3Resc, CDRProperty.depositMethod.getProperty(),
                bxc5Resc, Cdr.depositMethod);
        addLiteralIfPresent(bxc3Resc, CDRProperty.depositPackageSubType.getProperty(),
                bxc5Resc, Cdr.depositPackageProfile);
        addLiteralIfPresent(bxc3Resc, CDRProperty.depositPackageType.getProperty(),
                bxc5Resc, Cdr.depositPackageType);
    }

    private void addLiteralIfPresent(Resource bxc3Resc, Property bxc3Property,
            Resource bxc5Resc, Property bxc5Property) {
        if (bxc3Resc.hasProperty(bxc3Property)) {
            Statement prop = bxc3Resc.getProperty(bxc3Property);
            String val;
            if (prop.getObject().isResource()) {
                val = prop.getResource().getURI();
            } else {
                val = prop.getString();
            }
            bxc5Resc.addLiteral(bxc5Property, val);
        }
    }

    private void transformAndPopulatePremis(DepositRecord depRecord) {
        Path originalPremisPath = pathIndex.getPath(bxc3Pid, PathIndex.PREMIS_TYPE);
        if (originalPremisPath == null || !Files.exists(originalPremisPath)) {
            log.info("No premis for {}, skipping transformation", bxc3Pid.getId());
            return;
        }

        try {
            Path transformedPremisPath = Files.createTempFile("premis", ".xml");
            try {
                PID bxc5Pid = depRecord.getPid();
                PremisLogger filePremisLogger = premisLoggerFactory.createPremisLogger(
                        bxc5Pid, transformedPremisPath.toFile());
                DepositRecordPremisToRdfTransformer premisTransformer =
                        new DepositRecordPremisToRdfTransformer(bxc5Pid, filePremisLogger, originalPremisPath);

                premisTransformer.compute();

                // Add migration event
                filePremisLogger.buildEvent(Premis.Ingestion)
                        .addEventDetail("Object migrated from Boxc 3 to Boxc 5")
                        .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.migrationUtil))
                        .writeAndClose();

                PremisLogger repoPremisLogger = premisLoggerFactory.createPremisLogger(depRecord, transferSession);
                repoPremisLogger.createLog(Files.newInputStream(transformedPremisPath));
            } finally {
                Files.delete(transformedPremisPath);
            }
        } catch (IOException e) {
            throw new RepositoryException("Failed to write premis file for " + bxc3Pid, e);
        }
    }

    private void addManifests() {
        List<Path> paths = pathIndex.getPathVersions(bxc3Pid, MANIFEST_TYPE);

        int manifestNum = 0;
        do {
            String dsName = "DATA_MANIFEST" + manifestNum;
            Element dsEl = FoxmlDocumentHelpers.getDatastreamElByName(foxml, dsName);
            if (dsEl == null) {
                break;
            }
            Element versionEl = dsEl.getChild("datastreamVersion", FOXML_NS);
            String created = versionEl.getAttributeValue("CREATED");
            String mimetype = versionEl.getAttributeValue("MIMETYPE");
            String label = versionEl.getAttributeValue("ID");

            String md5 = null;
            Element digestEl = versionEl.getChild("contentDigest", FOXML_NS);
            if (digestEl != null) {
                md5 = digestEl.getAttributeValue("DIGEST");
            }

            // Seek the path to the staged file for this manifest
            Path manifestPath = paths.stream()
                    .filter(p -> p.toString().endsWith(dsName + ".0"))
                    .findFirst()
                    .orElse(null);

            if (manifestPath == null) {
                manifestNum++;
                log.error("Failed to find path for recorded manifest {} on object {}", dsName, bxc3Pid);
                continue;
            }

            if (Files.notExists(manifestPath)) {
                manifestNum++;
                log.error("Manifest file {} does not exist for {}", manifestPath, bxc3Pid);
                continue;
            }

            PID manifestPid = getDepositManifestPid(bxc5Pid, dsName);
            // Transfer the manifest to its permanent storage location
            BinaryTransferOutcome transferOutcome = transferSession
                    .transferReplaceExisting(manifestPid, manifestPath.toUri());
            URI manifestStoredUri = transferOutcome.getDestinationUri();

            // Populate manifest timestamps
            Model manifestModel = ModelFactory.createDefaultModel();
            Resource selfResc = manifestModel.getResource("");
            selfResc.addLiteral(DC.title, StringUtils.isBlank(label) ? dsName : label);
            selfResc.addProperty(Fcrepo4Repository.lastModified, created, XSDDatatype.XSDdateTime);
            selfResc.addProperty(Fcrepo4Repository.created, created, XSDDatatype.XSDdateTime);

            // Create the manifest in fedora
            repoObjFactory.createOrUpdateBinary(manifestPid, manifestStoredUri, dsName,
                    mimetype, transferOutcome.getSha1(), md5, manifestModel);

            manifestNum++;
            // Repeat until no more manifests found
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

    public PID getPid() {
        return bxc3Pid;
    }
}
