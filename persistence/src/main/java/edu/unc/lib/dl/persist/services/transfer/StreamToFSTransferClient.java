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

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.apache.commons.io.FileUtils.copyInputStreamToFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.exceptions.UnsupportedAlgorithmException;
import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.storage.BinaryDetails;
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.transfer.BinaryAlreadyExistsException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;
import edu.unc.lib.dl.persist.api.transfer.StreamTransferClient;
import edu.unc.lib.dl.util.DigestAlgorithm;
import edu.unc.lib.dl.util.FileTransferHelpers;

/**
 * Client for transferring content to a filesystem storage location from input streams
 *
 * @author bbpennel
 *
 */
public class StreamToFSTransferClient implements StreamTransferClient {

    protected StorageLocation destination;
    private static final Logger log = LoggerFactory.getLogger(StreamToFSTransferClient.class);

    /**
     * @param destination destination storage location
     */
    public StreamToFSTransferClient(StorageLocation destination) {
        this.destination = destination;
    }

    @Override
    public BinaryTransferOutcome transfer(PID binPid, InputStream sourceStream) {
        return writeStream(binPid, sourceStream, false);
    }

    @Override
    public BinaryTransferOutcome transferReplaceExisting(PID binPid, InputStream sourceStream) {
        return writeStream(binPid, sourceStream, true);
    }

    protected BinaryTransferOutcome writeStream(PID binPid, InputStream sourceStream, boolean allowOverwrite) {
        URI destUri = destination.getNewStorageUri(binPid);
        Path destPath = Paths.get(destUri);

        if (!allowOverwrite && FileSystemTransferHelpers.versionsExist(destPath)) {
            throw new BinaryAlreadyExistsException("Failed to write stream, a binary already exists in "
                    + destination.getId() + " at path " + destUri);
        }

        if (allowOverwrite && !FedoraTransaction.isStillAlive()) {
            throw new BinaryTransferException("Must perform binary replacement as part of a transaction");
        }

        Thread cleanupThread = null;

        Path parentPath = destPath.getParent();
        try {
            cleanupThread = FileTransferHelpers.registerCleanup(destPath);

            // Fill in parent directories if they are not present
            Files.createDirectories(parentPath);

            DigestInputStream digestStream = new DigestInputStream(
                    sourceStream, MessageDigest.getInstance(DigestAlgorithm.DEFAULT_ALGORITHM.getName()));
            copyInputStreamToFile(digestStream, destPath.toFile());

            String digest = encodeHexString(digestStream.getMessageDigest().digest());

            return new BinaryTransferOutcomeImpl(binPid, destUri, destination.getId(), digest);
        } catch (IOException e) {
            log.debug("Attempting to cleanup {}", destPath);
            FileTransferHelpers.cleanupFile(destPath);
            throw new BinaryTransferException("Failed to write stream to destination "
                    + destination.getId(), e);
        } catch (final NoSuchAlgorithmException e) {
            throw new UnsupportedAlgorithmException(e);
        } finally {
            FileTransferHelpers.clearCleanupHook(cleanupThread);
        }
    }

    @Override
    public BinaryTransferOutcome transferVersion(PID binPid, InputStream sourceStream) {
        throw new NotImplementedException("Versioning not yet implemented");
    }

    @Override
    public void shutdown() {
        // No finalization needed at this time
    }

    @Override
    public void delete(URI fileUri) {
        try {
            Files.delete(Paths.get(fileUri));
        } catch (IOException e) {
            throw new BinaryTransferException("Failed to delete file from destination "
                    + destination.getId(), e);
        }
    }

    @Override
    public BinaryDetails getStoredBinaryDetails(PID binPid) {
        return FileSystemTransferHelpers.getStoredBinaryDetails(destination, binPid);
    }
}
