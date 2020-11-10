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

import static edu.unc.lib.dl.model.DatastreamPids.getDepositManifestPid;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;

import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.ObjectTypeMismatchException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.util.ResourceType;

/**
 * A Deposit Record repository object, which tracks information pertaining to a single deposit.
 *
 * @author bbpennel
 * @author harring
 *
 */
public class DepositRecord extends RepositoryObject {

    protected DepositRecord(PID pid, RepositoryObjectDriver driver,
            RepositoryObjectFactory repoObjFactory) {
        super(pid, driver, repoObjFactory);
    }

    /**
     *  Adds the given file as a manifest for this deposit.
     *
     * @param manifestUri URI of the binary content for this manifest
     * @param mimetype mimetype string of the manifest file
     * @return BinaryObject representing the newly created manifest object
     * @throws FedoraException
     */
    public BinaryObject addManifest(URI manifestUri, String mimetype)
            throws FedoraException {
        return addManifest(manifestUri, null, mimetype, null, null);
    }

    /**
     * Adds the given inputstream as the content of a manifest for this deposit.
     *
     * @param manifestUri URI of the binary content for this manifest
     * @param filename filename for the manifest
     * @param mimetype mimetype for the content of the manifest
     * @param sha1
     * @param md5
     * @return representing the newly created manifest object
     * @throws FedoraException
     */
    public BinaryObject addManifest(URI manifestUri, String filename, String mimetype, String sha1, String md5)
            throws FedoraException {
        if (filename == null) {
            filename = StringUtils.substringAfterLast(manifestUri.toString(), "/");
        }
        PID manifestPid = getDepositManifestPid(getPid(), filename);
        if (mimetype == null) {
            mimetype = "text/plain";
        }
        return repoObjFactory.createOrUpdateBinary(manifestPid, manifestUri, filename, mimetype, sha1, md5, null);
    }

    /**
     * Retrieves the requested manifest of this deposit record
     *
     * @param pid
     * @return The requested manifest as a BinaryObject or null if the pid was
     *         not a component of this deposit record
     * @throws FedoraException
     */
    public BinaryObject getManifest(PID pid) throws FedoraException {
        if (!this.pid.containsComponent(pid)) {
            return null;
        }
        return driver.getRepositoryObject(pid, BinaryObject.class);
    }

    /**
     * Retrieves a list of pids for manifests contained by this deposit record
     *
     * @return
     * @throws FedoraException
     */
    public List<PID> listManifests() throws FedoraException {
        return addPidsToList(Cdr.hasManifest);
    }

    /**
     * Retrieves a list of pids for objects contained by this deposit record
     * @return
     * @throws FedoraException
     */
    public List<PID> listDepositedObjects() throws FedoraException {
        return this.driver.listRelated(this, Cdr.originalDeposit);
    }

    /**
     * Ensure that the object retrieved has the DepositRecord type
     */
    @Override
    public DepositRecord validateType() throws FedoraException {
        if (!isType(Cdr.DepositRecord.toString())) {
            throw new ObjectTypeMismatchException("Object " + pid + " is not a Deposit Record.");
        }
        return this;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.DepositRecord;
    }

    @Override
    public RepositoryObject getParent() {
        return driver.getParentObject(this);
    }

    private List<PID> addPidsToList(Property p) {
        Resource resource = getResource();
        StmtIterator containsIt = resource.listProperties(p);
        List<PID> pids = new ArrayList<>();
        while (containsIt.hasNext()) {
            String path = containsIt.next().getObject().toString();
            pids.add(PIDs.get(path));
        }
        return pids;
    }
}
