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
package edu.unc.lib.boxc.model.fcrepo.objects;

import org.apache.jena.rdf.model.Model;

import edu.unc.lib.boxc.model.api.ResourceType;
import edu.unc.lib.boxc.model.api.exceptions.FedoraException;
import edu.unc.lib.boxc.model.api.exceptions.ObjectTypeMismatchException;
import edu.unc.lib.boxc.model.api.ids.PID;
import edu.unc.lib.boxc.model.api.objects.ContentContainerObject;
import edu.unc.lib.boxc.model.api.objects.ContentObject;
import edu.unc.lib.boxc.model.api.objects.FolderObject;
import edu.unc.lib.boxc.model.api.objects.WorkObject;
import edu.unc.lib.boxc.model.api.rdf.Cdr;
import edu.unc.lib.boxc.model.api.services.RepositoryObjectFactory;
import edu.unc.lib.boxc.model.fcrepo.services.RepositoryObjectDriver;

/**
 * A repository object which represents a Folder. Folders are containers which
 * may hold work objects or folder objects directly inside of them.
 *
 * @author bbpennel
 * @author harring
 *
 */
public class FolderObjectImpl extends AbstractContentContainerObject implements FolderObject {

    protected FolderObjectImpl(PID pid, RepositoryObjectDriver driver,
            RepositoryObjectFactory repoObjFactory) {
        super(pid, driver, repoObjFactory);
    }

    @Override
    public FolderObject validateType() throws FedoraException {
        if (!isType(Cdr.Folder.toString())) {
            throw new ObjectTypeMismatchException("Object " + pid + " is not a Folder object.");
        }
        return this;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.Folder;
    }

    @Override
    public ContentContainerObject addMember(ContentObject member) throws ObjectTypeMismatchException {
        if (!(member instanceof FolderObject || member instanceof WorkObject)) {
            throw new ObjectTypeMismatchException("Cannot add object of type " + member.getClass().getName()
                    + " as a member of FolderObject " + pid.getQualifiedId());
        }

        repoObjFactory.addMember(this, member);
        member.shouldRefresh();
        return this;
    }

    /**
     * Creates and adds a new folder to this folder.
     *
     * @return the newly created folder object
     */
    @Override
    public FolderObject addFolder() {
        return addFolder(null);
    }

    /**
     * Creates and adds a new folder with the provided pid and properties to this
     * folder.
     *
     * @param model
     *            properties for the new folder
     * @return the newly created folder object
     */
    @Override
    public FolderObject addFolder(Model model) {
        FolderObject folder = repoObjFactory.createFolderObject(model);
        repoObjFactory.addMember(this, folder);
        folder.shouldRefresh();

        return folder;
    }

    /**
     * Creates and adds a new work to this folder.
     *
     * @return the newly created work object
     */
    @Override
    public WorkObject addWork() {
        return addWork(null);
    }

    /**
     * Creates and adds a new work with the provided properties to this folder.
     *
     * @param model
     *            optional additional properties for the work
     * @return the newly created work object
     */
    @Override
    public WorkObject addWork(Model model) {
        WorkObject work = repoObjFactory.createWorkObject(model);
        repoObjFactory.addMember(this, work);
        work.shouldRefresh();

        return work;
    }
}
