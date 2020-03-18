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
package edu.unc.lib.dl.persist.services.versioning;

import static edu.unc.lib.dl.model.DatastreamPids.getDatastreamHistoryPid;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import org.apache.jena.rdf.model.Model;

import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.NotFoundException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.fedora.ServiceException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;

/**
 * Service for managing versions of a datastream using an XML based history log.
 *
 * This form of versioning should only be used for small or text based datastreams.
 *
 * @author bbpennel
 */
public class VersionedDatastreamService {
    private RepositoryObjectLoader repoObjLoader;
    private RepositoryObjectFactory repoObjFactory;
    private BinaryTransferService transferService;

    /**
     * Update a versioned datastream. If the datastream already exists, its previous
     * value will be moved into a history log object.
     *
     * @param newVersion details of the new datastream version
     * @return the BinaryObject representation of the datastream updated
     */
    public BinaryObject addVersion(DatastreamVersion newVersion) {

        PID dsPid = newVersion.getDsPid();

        BinaryObject dsObj = getBinaryObject(dsPid);

        // Get a session for transferring the binary and its history
        BinaryTransferSession session = getTransferSession(newVersion, dsObj);
        try {
            // if datastream is new, go ahead and create it
            if (dsObj == null) {
                return updateHeadVersion(newVersion, session);
            } else {
                // Datastream already exists
                // Add the current head version to the history log
                updateDatastreamHistory(session, dsObj);

                // Replace the head version with the new content
                return updateHeadVersion(newVersion, session);
            }
        } finally {
            // Only close the transfer session if it was created within this method call
            if (newVersion.getTransferSession() == null) {
                session.close();
            }
        }
    }

    /**
     * Get the binary object represented by the given pid, or null if it does not exist.
     * @param dsPid
     * @return
     */
    private BinaryObject getBinaryObject(PID dsPid) {
        try {
            return repoObjLoader.getBinaryObject(dsPid);
        } catch (NotFoundException e) {
            return null;
        }
    }

    /**
     * Add the current state of the datastream to its history log. If no history log exists,
     * one will be created.
     *
     * @param session
     * @param currentDsObj
     */
    private void updateDatastreamHistory(BinaryTransferSession session, BinaryObject currentDsObj) {
        PID currentDsPid = currentDsObj.getPid();

        // Load existing datastream history if present
        PID dsHistoryPid = getDatastreamHistoryPid(currentDsPid);
        BinaryObject dsHistory = getBinaryObject(dsHistoryPid);
        // No history, start new one
        DatastreamHistoryLog historyLog;
        if (dsHistory == null) {
            historyLog = new DatastreamHistoryLog(currentDsPid);
        } else {
            historyLog = new DatastreamHistoryLog(currentDsPid, dsHistory.getBinaryStream());
        }

        historyLog.addVersion(currentDsObj.getBinaryStream(),
                currentDsObj.getMimetype(),
                currentDsObj.getCreatedDate());

        URI historyStorageUri;
        try {
            historyStorageUri = session.transferReplaceExisting(dsHistoryPid, historyLog.toInputStream());
        } catch (IOException e) {
            throw new ServiceException("Failed to serialize history for " + currentDsPid, e);
        }

        // Update the history object in fedora
        repoObjFactory.createOrUpdateBinary(dsHistoryPid,
                historyStorageUri,
                null,
                "text/xml",
                null,
                null,
                null);
    }

    private BinaryObject updateHeadVersion(DatastreamVersion newVersion, BinaryTransferSession session) {
        PID dsPid = newVersion.getDsPid();
        // Transfer the incoming content to its storage location
        URI dsStorageUri;
        if (newVersion.getStagedContentUri() == null) {
            dsStorageUri = session.transferReplaceExisting(dsPid, newVersion.getContentStream());
        } else {
            dsStorageUri = session.transferReplaceExisting(dsPid, newVersion.getStagedContentUri());
        }

        return repoObjFactory.createOrUpdateBinary(newVersion.getDsPid(),
                dsStorageUri,
                newVersion.getFilename(),
                newVersion.getContentType(),
                newVersion.getMd5(),
                newVersion.getSha1(),
                newVersion.getProperties());
    }

    /**
     * Get a transfer session for copying the new content to its destination location.
     * This may either be a new session, or an existing session if one was provided.
     *
     * @param newVersion
     * @param dsObj
     * @return
     */
    private BinaryTransferSession getTransferSession(DatastreamVersion newVersion, RepositoryObject dsObj) {
        if (newVersion.getTransferSession() != null) {
            return newVersion.getTransferSession();
        }

        if (dsObj != null) {
            return transferService.getSession(dsObj);
        }

        PID parentPid = PIDs.get(newVersion.getDsPid().getId());
        if (parentPid.equals(newVersion.getDsPid())) {
            throw new IllegalArgumentException("Unable to determine parent of datastream, the provided PID does not "
                    + "confirm to the expected datastream PID format: " + newVersion.getDsPid().getQualifiedId());
        }
        RepositoryObject parentObj = repoObjLoader.getRepositoryObject(parentPid);
        return transferService.getSession(parentObj);
    }

    public void setRepositoryObjectLoader(RepositoryObjectLoader repoObjLoader) {
        this.repoObjLoader = repoObjLoader;
    }

    public void setRepositoryObjectFactory(RepositoryObjectFactory repoObjFactory) {
        this.repoObjFactory = repoObjFactory;
    }

    public void setBinaryTransferService(BinaryTransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Details of a datastream version
     *
     * @author bbpennel
     */
    public static class DatastreamVersion {
        private PID dsPid;
        private InputStream contentStream;
        private URI stagedContentUri;
        private String contentType;
        private BinaryTransferSession transferSession;
        private String md5;
        private String sha1;
        private String filename;
        private Model properties;

        public DatastreamVersion(PID dsPid) {
            this.dsPid = dsPid;
        }

        public PID getDsPid() {
            return dsPid;
        }

        public InputStream getContentStream() {
            return contentStream;
        }

        public void setContentStream(InputStream contentStream) {
            this.contentStream = contentStream;
        }

        public URI getStagedContentUri() {
            return stagedContentUri;
        }

        public void setStagedContentUri(URI contentUri) {
            this.stagedContentUri = contentUri;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public BinaryTransferSession getTransferSession() {
            return transferSession;
        }

        public void setTransferSession(BinaryTransferSession transferSession) {
            this.transferSession = transferSession;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String md5) {
            this.md5 = md5;
        }

        public String getSha1() {
            return sha1;
        }

        public void setSha1(String sha1) {
            this.sha1 = sha1;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public Model getProperties() {
            return properties;
        }

        public void setProperties(Model properties) {
            this.properties = properties;
        }
    }
}
