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
package edu.unc.lib.dl.fcrepo4;

import java.net.URI;
import java.util.Date;
import java.util.List;

import org.apache.jena.datatypes.xsd.XSDDateTime;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;

import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.rdf.Fcrepo4Repository;
import edu.unc.lib.dl.util.ResourceType;

/**
 * A generic repository object, with properties common to objects in the repository.
 *
 * @author bbpennel
 * @author harring
 *
 */
public abstract class RepositoryObject {

    // Loader for lazy loading data about this object when requested
    protected RepositoryObjectDriver driver;
    protected RepositoryObjectFactory repoObjFactory;

    // The identifier and path information for this object
    protected PID pid;

    protected Model model;

    protected Date lastModified;
    protected Date created;
    protected String etag;

    protected List<String> types;

    protected PremisLogger premisLog;

    protected RepositoryObject(PID pid, RepositoryObjectDriver driver,
            RepositoryObjectFactory repoObjFactory) {
        this.pid = pid;
        this.driver = driver;
        this.repoObjFactory = repoObjFactory;
    }

    /**
     * Get the URI of this object in the repository
     *
     * @return
     */
    public URI getUri() {
        return pid.getRepositoryUri();
    }

    /**
     * Get a model containing properties and relations held by this object.
     *
     * @return
     * @throws FedoraException
     */
    public Model getModel() throws FedoraException {
        driver.loadModel(this, false);
        return model;
    }

    public Model getModel(boolean checkForUpdates) throws FedoraException {
        driver.loadModel(this, checkForUpdates);
        return model;
    }

    /**
     * Return true if the model for this object has been populated
     *
     * @return
     */
    public boolean hasModel() {
        return model != null;
    }

    /**
     * Store the relationships and properties belonging to this object
     *
     * @param model
     * @return
     */
    public void storeModel(Model model) {
        this.model = model;
        // Clear the cached types list
        this.types = null;
    }

    public Resource getResource() throws FedoraException {
        return getResource(false);
    }

    public Resource getResource(boolean checkForUpdates) throws FedoraException {
        return getModel(checkForUpdates).getResource(getUri().toString());
    }

    /**
     * Get the PREMIS event log for this object
     *
     * @return
     */
    public PremisLogger getPremisLog() {
        if (premisLog == null || premisLog.isClosed()) {
            premisLog = driver.getPremisLog(this);
        }
        return premisLog;
    }

    /**
     * @return the ResourceType which describes this object
     */
    public ResourceType getResourceType() {
        return ResourceType.getResourceTypeForUris(getTypes());
    }

    /**
     * Returns true if this object is assigned the given RDF type
     *
     * @param type
     *            URI for the RDF type being checked for
     * @return
     * @throws FedoraException
     */
    protected boolean isType(String type) throws FedoraException {
        return getTypes().contains(type);
    }

    /**
     * Throws a Fedora exception if the object does not match the expected RDF types
     *
     * @return
     * @throws FedoraException
     */
    public abstract RepositoryObject validateType() throws FedoraException;

    /**
     * Get the PID of this object
     *
     * @return
     */
    public PID getPid() {
        return pid;
    }

    /**
     * Set the PID
     *
     * @param pid
     */
    public void setPid(PID pid) {
        this.pid = pid;
    }

    /**
     * Get the parent of the current object
     * @return
     */
    public abstract RepositoryObject getParent();

    /**
     * Get the last modified date
     *
     * @return
     */
    public Date getLastModified() {
        if (lastModified == null) {
            lastModified = ((XSDDateTime) getResource().getProperty(Fcrepo4Repository.lastModified).getLiteral()
                    .getValue()).asCalendar().getTime();
        }
        return lastModified;
    }

    /**
     * Set the last modified date
     *
     * @param lastModified
     */
    public void setLastModified(Date lastModified) {
        this.lastModified = lastModified;
    }

    /**
     * Get the creation date for this object
     * @return
     */
    public Date getCreatedDate() {
        if (created == null) {
            created = ((XSDDateTime) getResource().getProperty(Fcrepo4Repository.created)
                    .getLiteral().getValue()).asCalendar().getTime();
        }
        return created;
    }

    /**
     * Get the ETag representing the version of the object retrieved
     *
     * @return
     */
    public String getEtag() {
        return etag;
    }

    /**
     * Set the etag
     *
     * @param etag
     */
    public void setEtag(String etag) {
        this.etag = etag;
    }

    /**
     * Get a list of the RDF types for this object
     *
     * @return
     * @throws FedoraException
     */
    public List<String> getTypes() throws FedoraException {
        if (types == null) {
            driver.loadTypes(this);
        }
        return types;
    }

    /**
     * Set the list of RDF types
     *
     * @param types
     */
    public void setTypes(List<String> types) {
        this.types = types;
    }

    /**
     * The URI where RDF metadata about this object can be retrieved from.
     *
     * @return
     */
    public URI getMetadataUri() {
        return pid.getRepositoryUri();
    }

    /**
     * Returns true if this object  is unmodified according to etag by
     * verifying if the locally held etag matches the current one in the
     * repository
     *
     * @return
     */
    public boolean isUnmodified() {
        if (getEtag() == null) {
            return false;
        }

        String remoteEtag = driver.getEtag(this);
        if (remoteEtag == null) {
            return false;
        }
        return remoteEtag.equals(getEtag());
    }

    /**
     * Indicate that the state of this object should be refreshed
     */
    public void shouldRefresh() {
        model = null;
        premisLog = null;
        types = null;
        etag = null;
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof RepositoryObject) {
            RepositoryObject repoObj = (RepositoryObject) object;
            return repoObj.getPid().equals(pid);
        } else {
            return false;
        }
    }
}
