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

import static edu.unc.lib.dl.persist.api.storage.StorageType.FILESYSTEM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.NotImplementedException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.ingest.IngestSource;
import edu.unc.lib.dl.persist.api.ingest.IngestSourceManager;
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;

/**
 * @author bbpennel
 *
 */
public class BinaryTransferSessionImplTest extends AbstractBinaryTransferTest {

    private BinaryTransferSessionImpl session;

    @Mock
    private IngestSourceManager sourceManager;
    @Mock
    private IngestSource ingestSource;
    @Mock
    private StorageLocation storageLoc;

    private PID binPid;
    private Path binDestPath;

    @Before
    public void setup() throws Exception {
        initMocks(this);
        createPaths();

        binPid = makeBinPid();
        binDestPath = storagePath.resolve(binPid.getComponentId());

        when(sourceManager.getIngestSourceForUri(any(URI.class))).thenReturn(ingestSource);
        when(ingestSource.getId()).thenReturn("source1");

        when(storageLoc.getNewStorageUri(binPid)).thenReturn(binDestPath.toUri());
        when(storageLoc.getId()).thenReturn("loc1");
    }

    @Test
    public void transferFSToFSInTry() throws Exception {
        when(ingestSource.getStorageType()).thenReturn(FILESYSTEM);
        when(storageLoc.getStorageType()).thenReturn(FILESYSTEM);

        try (BinaryTransferSessionImpl session = new BinaryTransferSessionImpl(sourceManager, storageLoc)) {
            Path sourceFile = createSourceFile();

            session.transfer(binPid, sourceFile.toUri());

            assertIsSourceFile(binDestPath);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void transferNoDestination() throws Exception {
        session = new BinaryTransferSessionImpl(sourceManager, null);
    }

    @Test(expected = NotImplementedException.class)
    public void transferUnknownStorageTypeMatchup() throws Exception {
        when(ingestSource.getStorageType()).thenReturn(FILESYSTEM);
        when(storageLoc.getStorageType()).thenReturn(null);

        session = new BinaryTransferSessionImpl(sourceManager, storageLoc);

        Path sourceFile = createSourceFile();

        session.transfer(binPid, sourceFile.toUri());
    }

    @Test
    public void transferFSToFSMultipleFilesSameDestination() throws Exception {
        when(ingestSource.getStorageType()).thenReturn(FILESYSTEM);
        when(storageLoc.getStorageType()).thenReturn(FILESYSTEM);

        PID binPid2 = makeBinPid();
        Path binDestPath2 = storagePath.resolve(binPid2.getComponentId());
        when(storageLoc.getNewStorageUri(binPid2)).thenReturn(binDestPath2.toUri());

        Path sourceFile = createSourceFile();
        Path sourceFile2 = createSourceFile("another.txt", "stuff");

        // Initialize session with the destination
        try (BinaryTransferSessionImpl session = new BinaryTransferSessionImpl(sourceManager, storageLoc)) {
            BinaryTransferOutcome outcome1 = session.transfer(binPid, sourceFile.toUri());
            BinaryTransferOutcome outcome2 = session.transfer(binPid2, sourceFile2.toUri());

            assertEquals(Paths.get(outcome1.getDestinationUri()), binDestPath);
            assertEquals(Paths.get(outcome2.getDestinationUri()), binDestPath2);
            assertFileContent(Paths.get(outcome1.getDestinationUri()), FILE_CONTENT);
            assertFileContent(Paths.get(outcome2.getDestinationUri()), "stuff");
        }
    }

    @Test
    public void transferFSToFSMultipleFilesTwoSourcesOneDest() throws Exception {
        when(ingestSource.getStorageType()).thenReturn(FILESYSTEM);
        when(storageLoc.getStorageType()).thenReturn(FILESYSTEM);

        Path sourceFile = createSourceFile();
        Path sourcePath2 = tmpFolder.newFolder("source2").toPath();
        Path sourceFile2 = createFile(sourcePath2.resolve("another.txt"), "stuff");

        PID binPid2 = makeBinPid();
        Path binDestPath2 = storagePath.resolve(binPid2.getComponentId());
        when(storageLoc.getNewStorageUri(binPid2)).thenReturn(binDestPath2.toUri());

        // Create second ingest source, which is not read only
        IngestSource source2 = mock(IngestSource.class);
        when(source2.getId()).thenReturn("source2");
        when(sourceManager.getIngestSourceForUri(sourceFile2.toUri())).thenReturn(source2);
        when(source2.getStorageType()).thenReturn(FILESYSTEM);

        // Make first ingest source read only, so it will be different from the second
        when(ingestSource.isReadOnly()).thenReturn(true);

        try (BinaryTransferSessionImpl session = new BinaryTransferSessionImpl(sourceManager, storageLoc)) {
            BinaryTransferOutcome outcome1 = session.transfer(binPid, sourceFile.toUri());
            BinaryTransferOutcome outcome2 = session.transfer(binPid2, sourceFile2.toUri());

            // Verify that results ended up in the right storage locations
            assertTrue(outcome1.getDestinationUri().toString().contains("storage/"));
            assertTrue(outcome2.getDestinationUri().toString().contains("storage/"));
            assertFileContent(Paths.get(outcome1.getDestinationUri()), FILE_CONTENT);
            assertFileContent(Paths.get(outcome2.getDestinationUri()), "stuff");
            // First source file should still be present
            assertIsSourceFile(sourceFile);
        }
    }

    @Test
    public void replaceBinaryFSToFS() throws Exception {
        when(ingestSource.getStorageType()).thenReturn(FILESYSTEM);
        when(storageLoc.getStorageType()).thenReturn(FILESYSTEM);

        Files.createDirectories(binDestPath.getParent());
        createFile(binDestPath, "some stuff");
        Path sourceFile = createSourceFile();

        try (BinaryTransferSessionImpl session = new BinaryTransferSessionImpl(sourceManager, storageLoc)) {
            session.transferReplaceExisting(binPid, sourceFile.toUri());

            assertIsSourceFile(binDestPath);
        }
    }

    @Test(expected = NotImplementedException.class)
    public void transferVersionFSToFS() throws Exception {
        when(ingestSource.getStorageType()).thenReturn(FILESYSTEM);
        when(storageLoc.getStorageType()).thenReturn(FILESYSTEM);

        Path sourceFile = createSourceFile();

        try (BinaryTransferSessionImpl session = new BinaryTransferSessionImpl(sourceManager, storageLoc)) {
            session.transferVersion(binPid, sourceFile.toUri());
        }
    }

    @Test
    public void deleteBinaryFSToFS() throws Exception {
        when(ingestSource.getStorageType()).thenReturn(FILESYSTEM);
        when(storageLoc.getStorageType()).thenReturn(FILESYSTEM);

        Files.createDirectories(binDestPath.getParent());
        createFile(binDestPath, "some stuff");
        Path sourceFile = createSourceFile();

        try (BinaryTransferSessionImpl session = new BinaryTransferSessionImpl(sourceManager, storageLoc)) {
            session.delete(sourceFile.toUri());
        }
        assertFalse("File must be deleted", Files.exists(sourceFile));
    }
}
