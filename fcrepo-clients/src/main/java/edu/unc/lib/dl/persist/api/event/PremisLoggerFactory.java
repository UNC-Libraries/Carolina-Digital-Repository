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
package edu.unc.lib.dl.persist.api.event;

import java.io.File;

import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;

/**
 * A factory for producing PremisLogger objects
 * @author bbpennel
 */
public interface PremisLoggerFactory {

    /**
     * Create a PREMIS logger for events related to the object identified by pid. Events
     * will be stored to/retrieved from the provided local file.
     *
     * @param pid pid of the subject of the logger
     * @param file file where the event data is stored
     * @return new PremisLogger instance
     */
    PremisLogger createPremisLogger(PID pid, File file);

    /**
     * Create a premis logger for events related to the provided repository object.
     *
     * @param repoObject subject of the logger
     * @return new PremisLogger instance
     */
    PremisLogger createPremisLogger(RepositoryObject repoObject);

    /**
     * Create a PREMIS logger for events related to the provided repository object.
     *
     * @param repoObject subject of the logger
     * @param session session the logger will use for transferring log data to storage
     * @return new PremisLogger instance
     */
    PremisLogger createPremisLogger(RepositoryObject repoObject, BinaryTransferSession session);

}