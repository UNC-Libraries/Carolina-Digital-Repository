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

import static edu.unc.lib.dl.model.DatastreamPids.getOriginalFilePid;
import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.ingest.IngestSource;
import edu.unc.lib.dl.persist.api.storage.BinaryDetails;
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.transfer.BinaryAlreadyExistsException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;
import edu.unc.lib.dl.persist.services.storage.HashedFilesystemStorageLocation;

/**
 * @author bbpennel
 *
 */
public class FSToFSTransferClientTest {

    protected static final String TEST_UUID = "a168cf29-a2a9-4da8-9b8d-025855b180d5";
    protected static final String FILE_CONTENT = "File content";
    protected static final String FILE_CONTENT_SHA1 = "6c4244329888770c6fa7f3fbf1d3b8baf9ccb7d0";

    protected FSToFSTransferClient client;

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();
    protected Path sourcePath;
    protected Path storagePath;
    @Mock
    protected IngestSource ingestSource;
    protected StorageLocation storageLoc;

    protected PID binPid;

    @Mock
    protected TransactionManager txManager;
    protected FedoraTransaction fedoraTransaction;
    protected final static URI TX_URI = URI.create("http://example.com/tx");

    @Before
    public void setup() throws Exception {
        initMocks(this);
        tmpFolder.create();
        sourcePath = tmpFolder.newFolder("source").toPath();
        storagePath = tmpFolder.newFolder("storage").toPath();

        HashedFilesystemStorageLocation hashedLoc = new HashedFilesystemStorageLocation();
        hashedLoc.setBase(storagePath.toString());
        hashedLoc.setId("loc1");
        storageLoc = hashedLoc;

        client = new FSToFSTransferClient(ingestSource, storageLoc);

        binPid = getOriginalFilePid(PIDs.get(TEST_UUID));
    }

    @After
    public void teardown() throws Exception {
        if (fedoraTransaction != null) {
            fedoraTransaction.close();
        }
    }

    @Test(expected = BinaryTransferException.class)
    public void transferFileThatDoesNotExist() {
        Path sourceFile = sourcePath.resolve("nofilehere.txt");
        client.transfer(binPid, sourceFile.toUri());
    }

    @Test
    public void transferFileReadOnlySource() throws Exception {
        when(ingestSource.isReadOnly()).thenReturn(true);

        Path sourceFile = createSourceFile();

        BinaryTransferOutcome outcome = client.transfer(binPid, sourceFile.toUri());

        assertIsSourceFile(outcome);
        assertIsSourceFile(sourceFile);
        assertOutcome(outcome, FILE_CONTENT_SHA1);
    }

    @Test
    public void transferFileModifiableSource() throws Exception {
        when(ingestSource.isReadOnly()).thenReturn(false);

        Path sourceFile = createSourceFile();

        BinaryTransferOutcome outcome = client.transfer(binPid, sourceFile.toUri());

        assertIsSourceFile(outcome);
        assertOutcome(outcome, FILE_CONTENT_SHA1);
    }

    @Test(expected = BinaryAlreadyExistsException.class)
    public void transferFileAlreadyExists() throws Exception {
        String existingContent = "I exist";

        Path destPath = Paths.get(storageLoc.getNewStorageUri(binPid));
        Files.createDirectories(destPath.getParent());
        createFile(destPath, existingContent);
        Path sourceFile = createSourceFile();

        client.transfer(binPid, sourceFile.toUri());
    }

    @Test(expected = BinaryTransferException.class)
    public void transferReplaceFileThatDoesNotExist() {
        Path sourceFile = sourcePath.resolve("nofilehere.txt");
        client.transferReplaceExisting(binPid, sourceFile.toUri());
    }

    @Test
    public void transferReplaceFileReadOnlySource() throws Exception {
        fedoraTransaction = new FedoraTransaction(TX_URI, txManager);
        when(ingestSource.isReadOnly()).thenReturn(true);

        Path sourceFile = createSourceFile();

        BinaryTransferOutcome outcome = client.transferReplaceExisting(binPid, sourceFile.toUri());

        assertIsSourceFile(outcome);
        assertIsSourceFile(sourceFile);
        assertOutcome(outcome, FILE_CONTENT_SHA1);
    }

    @Test
    public void transferReplaceFileModifiableSource() throws Exception {
        fedoraTransaction = new FedoraTransaction(TX_URI, txManager);
        when(ingestSource.isReadOnly()).thenReturn(false);

        Path sourceFile = createSourceFile();

        BinaryTransferOutcome outcome = client.transferReplaceExisting(binPid, sourceFile.toUri());

        assertIsSourceFile(outcome);
        assertOutcome(outcome, FILE_CONTENT_SHA1);
    }

    @Test(expected = BinaryTransferException.class)
    public void transferReplaceFileNotInTx() throws Exception {
        when(ingestSource.isReadOnly()).thenReturn(false);

        Path sourceFile = createSourceFile();

        client.transferReplaceExisting(binPid, sourceFile.toUri());
    }

    @Test
    public void transferReplaceFileAlreadyExists() throws Exception {
        fedoraTransaction = new FedoraTransaction(TX_URI, txManager);
        String existingContent = "I exist";

        Path destPath = Paths.get(storageLoc.getNewStorageUri(binPid));
        Files.createDirectories(destPath.getParent());
        createFile(destPath, existingContent);
        Path sourceFile = createSourceFile();

        BinaryTransferOutcome outcome = client.transferReplaceExisting(binPid, sourceFile.toUri());

        assertIsSourceFile(outcome);
        assertOutcome(outcome, FILE_CONTENT_SHA1);
    }

    @Test
    public void rollbackOnTransferInterruption() throws Exception {
        String existingContent = "I exist";
        Path destPath = Paths.get(storageLoc.getNewStorageUri(binPid));
        Path parentPath = destPath.getParent();
        Files.createDirectories(parentPath);

        createFile(destPath, existingContent);
        Path sourceFile = createSourceFile();

        File destFile = destPath.toFile();
        File parentDir = parentPath.toFile();
        parentDir.setReadOnly();

        try {
            client.transferReplaceExisting(binPid, sourceFile.toUri());
        } catch (BinaryTransferException e) {
            assertTrue("Original file should be present", destFile.exists());
            assertEquals(1, parentDir.listFiles().length);
        } finally {
            parentDir.setWritable(true);
            destFile.delete();
        }
    }

    @Test(expected = NotImplementedException.class)
    public void transferVersion() throws Exception {
        Path sourceFile = createSourceFile();
        client.transferVersion(binPid, sourceFile.toUri());
    }

    // Verify that file larger than file transfer buffer size is transferred
    @Test
    public void transferLargeFile() throws Exception {
        when(ingestSource.isReadOnly()).thenReturn(true);

        long fileSize = 100 * 1024 * 1024;
        // Generate a 100mb file for transferring
        Path sourceFile = sourcePath.resolve("file.txt");
        try (RandomAccessFile filler = new RandomAccessFile(sourceFile.toFile(), "rw")) {
            filler.setLength(fileSize);
        }

        BinaryTransferOutcome outcome = client.transfer(binPid, sourceFile.toUri());

        Path destPath = Paths.get(outcome.getDestinationUri());
        assertTrue(Files.exists(destPath));
        assertEquals(fileSize, Files.size(destPath));
        String expectedSha1 = encodeHexString(DigestUtils.sha1(Files.newInputStream(destPath)));
        assertOutcome(outcome, expectedSha1);
    }

    @Test
    public void interruptTransfer() throws Exception {
        when(ingestSource.isReadOnly()).thenReturn(true);

        // Generate a 100mb file for transferring
        Path sourceFile = sourcePath.resolve("file.txt");
        try (RandomAccessFile filler = new RandomAccessFile(sourceFile.toFile(), "rw")) {
            filler.setLength(100 * 1024 * 1024);
        }

        Thread copyThread = new Thread() {
            @Override
            public void run() {
                try {
                    client.transfer(binPid, sourceFile.toUri());
                    fail("Transfer must be interrupted");
                } catch (BinaryTransferException e) {
                    // expected
                }
            }
        };

        copyThread.start();
        // Wait briefly to let transfer start
        Thread.sleep(10);

        copyThread.interrupt();

        Path aDestPath = Paths.get(storageLoc.getNewStorageUri(binPid));
        // Expect the parent directory where the file should be written to to have been cleaned up
        Awaitility.await().atMost(Duration.ofSeconds(1))
            .until(() -> !Files.exists(aDestPath.getParent()));
    }

    @Test
    public void shutdownClientSucceeds() throws Exception {
        client.shutdown();
    }

    @Test
    public void getStoredBinaryDetailsForAbsentFile() {
        assertNull(client.getStoredBinaryDetails(binPid));
    }

    @Test
    public void getStoredBinaryDetailsTransferredFile() throws Exception {
        Path sourceFile = createSourceFile();

        client.transfer(binPid, sourceFile.toUri());

        BinaryDetails details = client.getStoredBinaryDetails(binPid);
        assertNotNull(details);
        assertNotNull(details.getLastModified());
        assertEquals(12, details.getSize());
    }

    @Test
    public void isTransferredNotPresent() throws Exception {
        assertFalse(client.isTransferred(binPid, sourcePath.resolve("absent.txt").toUri()));
    }

    @Test
    public void isTransferredMatch() throws Exception {
        Path sourceFile = createSourceFile();
        client.transfer(binPid, sourceFile.toUri());

        assertTrue(client.isTransferred(binPid, sourceFile.toUri()));
    }

    @Test
    public void isTransferredDoNotMatch() throws Exception {
        Path sourceFile = createSourceFile();
        client.transfer(binPid, sourceFile.toUri());

        FileUtils.writeStringToFile(sourceFile.toFile(), "changed content", "UTF-8");
        assertFalse(client.isTransferred(binPid, sourceFile.toUri()));
    }

    @Test
    public void isTransferredSourceGoneReadOnly() throws Exception {
        when(ingestSource.isReadOnly()).thenReturn(true);

        Path sourceFile = createSourceFile();
        client.transfer(binPid, sourceFile.toUri());
        Files.delete(sourceFile);

        try {
            client.isTransferred(binPid, sourceFile.toUri());
            fail("Expected BinaryTransferException");
        } catch (BinaryTransferException e) {
            // expected
        }
    }

    @Test
    public void isTransferredSourceGoneWritable() throws Exception {
        when(ingestSource.isReadOnly()).thenReturn(false);

        Path sourceFile = createSourceFile();
        client.transfer(binPid, sourceFile.toUri());

        assertTrue(client.isTransferred(binPid, sourceFile.toUri()));
    }

    protected Path createSourceFile() throws Exception {
        return createFile(sourcePath.resolve("file.txt"), FILE_CONTENT);
    }

    private Path createFile(Path filePath, String content) throws Exception {
        FileUtils.writeStringToFile(filePath.toFile(), content, "UTF-8");
        return filePath;
    }

    protected void assertIsSourceFile(BinaryTransferOutcome outcome) throws Exception {
        assertIsSourceFile(Paths.get(outcome.getDestinationUri()));
    }

    protected void assertIsSourceFile(Path path) throws Exception {
        assertTrue("Source file was not present at " + path, path.toFile().exists());
        assertEquals(FILE_CONTENT, FileUtils.readFileToString(path.toFile(), "UTF-8"));
    }

    protected void assertOutcome(BinaryTransferOutcome outcome, String expectedSha1) {
        assertNotNull("Outcome was not returned", outcome);
        assertTrue("Destination file must be in the destination storage location",
                Paths.get(outcome.getDestinationUri()).startsWith(storagePath));
        assertEquals("Unexpected outcome digest", expectedSha1, outcome.getSha1());
    }
}
