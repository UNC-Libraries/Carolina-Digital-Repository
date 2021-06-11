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
package edu.unc.lib.dl.persist.services.event;

import java.io.File;

import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;

/**
 * A factory class for creating PremisLogger instances
 *
 * @author harring
 */

public class PremisLoggerFactoryImpl implements PremisLoggerFactory {

    private RepositoryPIDMinter pidMinter;
    private RepositoryObjectLoader repoObjLoader;
    private RepositoryObjectFactory repoObjFactory;
    private BinaryTransferService transferService;

    /**
     * Create a PREMIS logger for events related to the object identified by pid. Events
     * will be stored to/retrieved from the provided local file.
     *
     * @param pid pid of the subject of the logger
     * @param file file where the event data is stored
     * @return new PremisLogger instance
     */
    @Override
    public PremisLogger createPremisLogger(PID pid, File file) {
        return new FilePremisLogger(pid, file, pidMinter);
    }

    /**
     * Create a premis logger for events related to the provided repository object.
     *
     * @param repoObject subject of the logger
     * @return new PremisLogger instance
     */
    @Override
    public PremisLogger createPremisLogger(RepositoryObject repoObject) {
        return createPremisLogger(repoObject, transferService.getSession(repoObject));
    }

    /**
     * Create a PREMIS logger for events related to the provided repository object.
     *
     * @param repoObject subject of the logger
     * @param session session the logger will use for transferring log data to storage
     * @return new PremisLogger instance
     */
    @Override
    public PremisLogger createPremisLogger(RepositoryObject repoObject, BinaryTransferSession session) {
        return new RepositoryPremisLogger(repoObject, session, pidMinter, repoObjLoader, repoObjFactory);
    }

    /**
     * @param pidMinter the pidMinter to set
     */
    public void setPidMinter(RepositoryPIDMinter pidMinter) {
        this.pidMinter = pidMinter;
    }

    /**
     * @param repoObjLoader the repoObjLoader to set
     */
    public void setRepoObjLoader(RepositoryObjectLoader repoObjLoader) {
        this.repoObjLoader = repoObjLoader;
    }

    /**
     * @param repoObjFactory the repoObjFactory to set
     */
    public void setRepoObjFactory(RepositoryObjectFactory repoObjFactory) {
        this.repoObjFactory = repoObjFactory;
    }

    public void setBinaryTransferService(BinaryTransferService transferService) {
        this.transferService = transferService;
    }
}
