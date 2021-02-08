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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.fusesource.hawtbuf.ByteArrayInputStream;
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
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.transfer.BinaryAlreadyExistsException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;
import edu.unc.lib.dl.persist.services.storage.HashedFilesystemStorageLocation;

/**
 * @author bbpennel
 *
 */
public class StreamToFSTransferClientTest {

    protected static final String TEST_UUID = "a168cf29-a2a9-4da8-9b8d-025855b180d5";
    protected static final String ORIGINAL_CONTENT = "Some original stuff";
    protected static final String STREAM_CONTENT = "Stream content";
    protected static final String STREAM_CONTENT_SHA1 = "bf29c7fd7f87fe7395b89012e73d91c85a0cb19b";

    protected StreamToFSTransferClient client;

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();
    protected Path sourcePath;
    protected Path storagePath;
    protected StorageLocation storageLoc;

    protected PID binPid;

    @Mock
    private TransactionManager txManager;
    private FedoraTransaction fedoraTransaction;
    private final static URI TX_URI = URI.create("http://example.com/tx");

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

        client = new StreamToFSTransferClient(storageLoc);

        binPid = getOriginalFilePid(PIDs.get(TEST_UUID));
    }

    @After
    public void teardown() throws Exception {
        if (fedoraTransaction != null) {
            fedoraTransaction.close();
        }
    }

    @Test
    public void transfer_NewFile() throws Exception {
        InputStream sourceStream = toStream(STREAM_CONTENT);

        BinaryTransferOutcome outcome = client.transfer(binPid, sourceStream);

        assertContent(outcome, STREAM_CONTENT);
        assertOutcome(outcome, STREAM_CONTENT_SHA1);
    }

    @Test(expected = BinaryAlreadyExistsException.class)
    public void transfer_ExistingFile() throws Exception {
        // Create existing file content
        Path destPath = createFile();

        // Attempt to transfer new content
        InputStream sourceStream = toStream(STREAM_CONTENT);

        try {
            client.transfer(binPid, sourceStream);
        } finally {
            // Verify that the file was not replaced
            assertContent(destPath, ORIGINAL_CONTENT);
        }
    }

    @Test(expected = BinaryTransferException.class)
    public void transfer_StreamThrowsException() throws Exception {
        InputStream sourceStream = mock(InputStream.class);
        when(sourceStream.read(any(), anyInt(), anyInt())).thenThrow(new IOException());

        try {
            client.transfer(binPid, sourceStream);
        } finally {
            assertNull(storageLoc.getCurrentStorageUri(binPid));
        }
    }

    @Test
    public void transferReplaceExisting_ExistingFile() throws Exception {
        fedoraTransaction = new FedoraTransaction(TX_URI, txManager);
        // Create existing file content
        createFile();

        InputStream sourceStream = toStream(STREAM_CONTENT);

        BinaryTransferOutcome outcome = client.transferReplaceExisting(binPid, sourceStream);
        // Verify that the new file is present
        assertContent(outcome, STREAM_CONTENT);
        assertOutcome(outcome, STREAM_CONTENT_SHA1);
    }

    @Test(expected = BinaryTransferException.class)
    public void transferReplaceExisting_NoTx() throws Exception {
        // Create existing file content
        createFile();

        InputStream sourceStream = toStream(STREAM_CONTENT);

        client.transferReplaceExisting(binPid, sourceStream);
    }

    @Test(expected = BinaryTransferException.class)
    public void transferReplaceExisting_ExistingFile_WriteFails() throws Exception {
        InputStream sourceStream = mock(InputStream.class);
        when(sourceStream.read(any(), anyInt(), anyInt())).thenThrow(new IOException());

        // Create existing file content
        Path destPath = createFile();

        try {
            client.transferReplaceExisting(binPid, sourceStream);
        } finally {
            // Verify that the content was not replaced
            assertContent(destPath, ORIGINAL_CONTENT);
        }
    }

    @Test
    public void rollbackOnTransferInterruption() throws Exception {
        Path destPath = createFile();
        File destFile = destPath.toFile();
        File parentDir = destPath.getParent().toFile();
        parentDir.setReadOnly();

        InputStream sourceStream = toStream(ORIGINAL_CONTENT);

        try {
            client.transferReplaceExisting(binPid, sourceStream);
        } catch (BinaryTransferException e) {
            assertTrue("Original file should be present", destFile.exists());
            assertEquals(1, parentDir.listFiles().length);
        } finally {
            parentDir.setWritable(true);
            destFile.delete();
        }
    }

    @Test
    public void deleteExisting() throws Exception {
        InputStream sourceStream = toStream(STREAM_CONTENT);

        BinaryTransferOutcome outcome = client.transfer(binPid, sourceStream);
        assertTrue(Files.exists(Paths.get(outcome.getDestinationUri())));

        client.delete(outcome.getDestinationUri());
        assertFalse(Files.exists(Paths.get(outcome.getDestinationUri())));
    }

    @Test(expected = BinaryTransferException.class)
    public void deleteNonExistent() throws Exception {
        URI destUri = storageLoc.getNewStorageUri(binPid);
        client.delete(destUri);
    }

    protected void assertContent(BinaryTransferOutcome outcome, String content) throws Exception {
        assertContent(Paths.get(outcome.getDestinationUri()), content);
    }

    protected void assertContent(Path path, String content) throws Exception {
        assertTrue("Source content was not present at " + path, path.toFile().exists());
        assertEquals(content, FileUtils.readFileToString(path.toFile(), "UTF-8"));
    }

    protected InputStream toStream(String content) {
        return new ByteArrayInputStream(content.getBytes());
    }

    private Path createFile() throws Exception {
        Path path = Paths.get(storageLoc.getNewStorageUri(binPid));
        FileUtils.copyInputStreamToFile(toStream(ORIGINAL_CONTENT), path.toFile());
        return path;
    }

    protected void assertOutcome(BinaryTransferOutcome outcome, String expectedSha1) {
        assertNotNull("Outcome was not returned", outcome);
        assertTrue("Destination file must be in the destination storage location",
                Paths.get(outcome.getDestinationUri()).startsWith(storagePath));
        assertEquals("Unexpected outcome digest", expectedSha1, outcome.getSha1());
    }
}
