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

import edu.unc.lib.boxc.model.api.rdf.Fcrepo4Repository;
import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.ObjectTypeMismatchException;
import edu.unc.lib.dl.fedora.PID;

/**
 * Represents a tombstone object within the repository
 *
 * @author harring
 *
 */
public class Tombstone extends ContentObject {

    public Tombstone(PID pid, RepositoryObjectDriver driver, RepositoryObjectFactory repoObjFactory) {
        super(pid, driver, repoObjFactory);
    }

    @Override
    public RepositoryObject validateType() throws FedoraException {
        if (!isType(Fcrepo4Repository.Tombstone.toString())) {
            throw new ObjectTypeMismatchException("Object " + pid + " is not a tombstone");
        }
        return this;
    }

    @Override
    public RepositoryObject getParent() {
        // tombstone is not in the hierarchy, so doesn't have a parent
        return null;
    }

    @Override
    public PID getParentPid() {
        return null;
    }
}
