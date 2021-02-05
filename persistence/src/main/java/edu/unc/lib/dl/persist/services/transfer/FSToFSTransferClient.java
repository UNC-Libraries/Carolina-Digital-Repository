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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
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
import edu.unc.lib.dl.persist.api.ingest.IngestSource;
import edu.unc.lib.dl.persist.api.storage.BinaryDetails;
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.transfer.BinaryAlreadyExistsException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferClient;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;
import edu.unc.lib.dl.util.DigestAlgorithm;
import edu.unc.lib.dl.util.FileTransferHelpers;

/**
 * Client for transferring files from a filesystem ingest source to a filesystem
 * storage location
 *
 * @author bbpennel
 *
 */
public class FSToFSTransferClient implements BinaryTransferClient {

    private IngestSource source;
    protected StorageLocation destination;

    private static final Logger log = LoggerFactory.getLogger(FSToFSTransferClient.class);

    public FSToFSTransferClient(IngestSource source, StorageLocation destination) {
        this.source = source;
        this.destination = destination;
    }

    @Override
    public BinaryTransferOutcome transfer(PID binPid, URI sourceFileUri) {
        return transfer(binPid, sourceFileUri, false);
    }

    @Override
    public BinaryTransferOutcome transferReplaceExisting(PID binPid, URI sourceFileUri) {
        return transfer(binPid, sourceFileUri, true);
    }

    public BinaryTransferOutcome transfer(PID binPid, URI sourceFileUri, boolean allowOverwrite) {
        String digest;
        URI destUri = destination.getNewStorageUri(binPid);
        Path destinationPath = Paths.get(destUri);
        log.debug("Transferring {} to {}", sourceFileUri, destUri);

        if (!allowOverwrite && FileSystemTransferHelpers.versionsExist(destinationPath)) {
            throw new BinaryAlreadyExistsException("Failed to transfer " + sourceFileUri
                    + ", a binary already exists in " + destination.getId() + " at path " + destUri);
        }

        if (allowOverwrite && !FedoraTransaction.isStillAlive()) {
            throw new BinaryTransferException("Must perform binary replacement as part of a transaction");
        }

        Thread cleanupThread = null;

        Path parentPath = destinationPath.getParent();
        try {
            cleanupThread = FileTransferHelpers.registerCleanup(destinationPath);

            // Fill in parent directories if they are not present
            Files.createDirectories(parentPath);

            Path sourcePath = Paths.get(sourceFileUri);

            // Using FileUtils.copyFile since it defers to FileChannel.transferFrom, which is interruptible
            digest = copyFile(sourcePath, destinationPath);
        } catch (IOException e) {
            log.debug("Attempting to cleanup failed transfer of {} to {}",
                    sourceFileUri, destinationPath);
            FileTransferHelpers.cleanupFile(destinationPath);
            throw new BinaryTransferException("Failed to transfer " + sourceFileUri
                    + " to destination " + destination.getId(), e);
        } finally {
            FileTransferHelpers.clearCleanupHook(cleanupThread);
        }

        log.debug("Finished transferring {} to {}", sourceFileUri, destUri);

        return new BinaryTransferOutcomeImpl(binPid, destUri, destination.getId(), digest);
    }

    private static final long FILE_COPY_BUFFER_SIZE = 1024 * 1024 * 30;

    /**
     * Based on org.apache.commons.io.FileUtils.copyFile(File, File, boolean)
     * but adds Digest calculation during transfer
     * @param srcPath
     * @param destPath
     * @throws IOException
     */
    private static String copyFile(Path srcPath, Path destPath) throws IOException {
        if (Files.exists(destPath) && Files.isDirectory(destPath)) {
            throw new IOException("Destination '" + destPath + "' exists but is a directory");
        }

        String digest;
        File srcFile = srcPath.toFile();
        final long srcLen = srcFile.length();
        File destFile = destPath.toFile();
        try (
                FileInputStream fis = new FileInputStream(srcFile);
                DigestInputStream digestStream = new DigestInputStream(
                        fis, MessageDigest.getInstance(DigestAlgorithm.DEFAULT_ALGORITHM.getName()));
                ReadableByteChannel input = Channels.newChannel(digestStream);
                FileOutputStream fos = new FileOutputStream(destFile);
                FileChannel output = fos.getChannel()) {

            long pos = 0;
            long count = 0;
            while (pos < srcLen) {
                final long remain = srcLen - pos;
                count = remain > FILE_COPY_BUFFER_SIZE ? FILE_COPY_BUFFER_SIZE : remain;
                final long bytesCopied = output.transferFrom(input, pos, count);
                if (bytesCopied == 0) {
                    break; // ensure we don't loop forever
                }
                pos += bytesCopied;
            }
            digest = encodeHexString(digestStream.getMessageDigest().digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new UnsupportedAlgorithmException(e);
        }

        final long dstLen = destFile.length();
        if (srcLen != dstLen) {
            throw new IOException("Failed to copy full contents from '" +
                    srcFile + "' to '" + destFile + "' Expected length: " + srcLen + " Actual: " + dstLen);
        }
        destFile.setLastModified(srcFile.lastModified());

        return digest;
    }

    @Override
    public BinaryTransferOutcome transferVersion(PID binPid, URI sourceFileUri) {
        throw new NotImplementedException("Versioning not yet implemented");
    }

    @Override
    public void shutdown() {
        // No finalization needed for FS to FS transfer
    }

    @Override
    public boolean isTransferred(PID binPid, URI sourceUri) {
        BinaryDetails storedDetails = getStoredBinaryDetails(binPid);
        if (storedDetails == null) {
            return false;
        }

        BinaryDetails stagedDetails = FileSystemTransferHelpers.getBinaryDetails(sourceUri);
        if (stagedDetails == null) {
            if (source.isReadOnly()) {
                throw new BinaryTransferException("Source URI " + sourceUri + " does not exist");
            } else {
                log.debug("Source file {} from writable source no longer exists, it may have been moved",
                        sourceUri);
                return true;
            }
        }

        // For the moment, just compare file size. Timestamps can lose precision during copying,
        // and fixity checks are expensive/occur later on in fedora.
        return storedDetails.getSize() == stagedDetails.getSize();
    }

    @Override
    public BinaryDetails getStoredBinaryDetails(PID binPid) {
        return FileSystemTransferHelpers.getStoredBinaryDetails(destination, binPid);
    }
}
