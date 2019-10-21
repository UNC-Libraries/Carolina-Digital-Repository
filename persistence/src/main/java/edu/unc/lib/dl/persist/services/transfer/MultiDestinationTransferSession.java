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
package edu.unc.lib.dl.persist.services.transfer;

import java.net.URI;
import java.nio.file.FileAlreadyExistsException;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.services.storage.StorageLocation;

/**
 * A session for transferring one or more binaries to preservation storage locations
 *
 * @author bbpennel
 *
 */
public interface MultiDestinationTransferSession extends AutoCloseable {

    /**
     * Transfer a binary to the provided storage location. If the
     * binary already exists in the destination, an exception will be thrown.
     *
     * @param binPid PID of the binary object the binary is associated with
     * @param sourceFileUri URI of the binary located in an IngestSource.
     * @param destination storage location to transfer the file to.
     * @return the URI of the binary in its destination.
     * @throws FileAlreadyExistsException thrown if the binary already exists
     */
    URI transfer(PID binPid, URI sourceFileUri, StorageLocation destination) throws FileAlreadyExistsException;

    /**
     * Transfer a binary to the provided storage location. If a binary already
     * exists at the expected destination, it will be overwritten.
     *
     * @param binPid PID of the binary object the binary is associated with
     * @param sourceFileUri URI of the binary located in an IngestSource.
     * @param destination storage location to transfer the file to.
     * @return the URI of the binary in its destination.
     */
    URI transferReplaceExisting(PID binPid, URI sourceFileUri, StorageLocation destination);

    /**
     * Transfer a new version of binary to the provided storage location. Previous
     * versions will not be overwritten.
     *
     * @param binPid PID of the binary object the binary is associated with
     * @param sourceFileUri URI of the binary located in an IngestSource.
     * @param destination storage location to transfer the file to.
     * @return the URI of the binary in its destination.
     */
    URI transferVersion(PID binPid, URI sourceFileUri, StorageLocation destination);
}