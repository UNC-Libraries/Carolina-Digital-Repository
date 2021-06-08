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

import static java.util.Arrays.asList;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.transfer.BinaryAlreadyExistsException;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.persist.api.transfer.MultiDestinationTransferSession;
import edu.unc.lib.dl.persist.services.ingest.IngestSourceManagerImpl;
import edu.unc.lib.dl.persist.services.ingest.IngestSourceTestHelper;
import edu.unc.lib.dl.persist.services.storage.HashedFilesystemStorageLocation;
import edu.unc.lib.dl.persist.services.storage.StorageLocationManagerImpl;
import edu.unc.lib.dl.test.TestHelper;

/**
 * @author bbpennel
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/cdr-client-container.xml")
})
public class BinaryTransferServiceImplIT {

    @Autowired
    private String baseAddress;
    @Autowired
    private BinaryTransferServiceImpl transferService;

    private IngestSourceManagerImpl sourceManager;
    @Autowired
    private StorageLocationManagerImpl storageManager;
    @Autowired
    private RepositoryPIDMinter pidMinter;
    @Autowired
    private TransactionManager txManager;
    @Autowired
    private RepositoryObjectFactory repoObjFactory;
    @Autowired
    private RepositoryObjectLoader repoObjLoader;

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();
    private Path sourceConfigPath;
    private Path storageConfigPath;

    private Path sourcePath1;
    private Path sourcePath2;
    private Path storagePath1;
    private Path storagePath2;

    @Before
    public void setup() throws Exception {
        tmpFolder.create();

        TestHelper.setContentBase(baseAddress);

        File sourceMappingFile = new File(tmpFolder.getRoot(), "sourceMapping.json");
        FileUtils.writeStringToFile(sourceMappingFile, "[]", "UTF-8");

        sourcePath1 = tmpFolder.newFolder("source_wr").toPath();
        sourcePath2 = tmpFolder.newFolder("source_ro").toPath();
        sourceConfigPath = IngestSourceTestHelper.createConfigFile(
                IngestSourceTestHelper.createFilesystemConfig("source_wr", "Mod", sourcePath1, asList("*")),
                IngestSourceTestHelper.createFilesystemConfig("source_ro", "RO", sourcePath2, asList("*"), true));

        sourceManager = new IngestSourceManagerImpl();
        sourceManager.setConfigPath(sourceConfigPath.toString());
        sourceManager.setMappingPath(sourceMappingFile.toString());
        sourceManager.init();

        transferService.setIngestSourceManager(sourceManager);

        File storageMappingFile = new File(tmpFolder.getRoot(), "storageMapping.json");
        FileUtils.writeStringToFile(storageMappingFile, "[]", "UTF-8");

        storagePath1 = tmpFolder.newFolder("storage1").toPath();
        storagePath2 = tmpFolder.newFolder("storage2").toPath();
        storageConfigPath = createStorageConfigFile(
                addStorageLocation("loc1", "Loc 1", storagePath1),
                addStorageLocation("loc2", "Loc 2", storagePath2));

        storageManager.setConfigPath(storageConfigPath.toString());
        storageManager.setMappingPath(storageMappingFile.toString());
        storageManager.init();
    }

    @Test
    public void singleDestinationTransfer() throws Exception {
        PID binPid = pidMinter.mintContentPid();

        StorageLocation destination = storageManager.getStorageLocationById("loc1");
        Path sourceFile = createSourceFile(sourcePath1, "myfile.txt", "some content");

        try (BinaryTransferSession session = transferService.getSession(destination)) {
            BinaryTransferOutcome outcome = session.transfer(binPid, sourceFile.toUri());

            assertTrue(new File(outcome.getDestinationUri()).exists());
        }
    }

    @Test
    public void multiSourceAndDestinationTransfer() throws Exception {
        PID binPid1 = pidMinter.mintContentPid();
        PID binPid2 = pidMinter.mintContentPid();

        Path sourceFile1 = createSourceFile(sourcePath1, "myfile.txt", "some content");
        Path sourceFile2 = createSourceFile(sourcePath2, "other.txt", "stuff");

        try (MultiDestinationTransferSession session = transferService.getSession()) {
            StorageLocation dest1 = storageManager.getStorageLocationById("loc1");
            BinaryTransferOutcome outcome1 = session.forDestination(dest1).transfer(binPid1, sourceFile1.toUri());
            assertTrue(new File(outcome1.getDestinationUri()).exists());

            StorageLocation dest2 = storageManager.getStorageLocationById("loc2");
            BinaryTransferOutcome outcome2 = session.forDestination(dest2).transfer(binPid2, sourceFile2.toUri());
            assertTrue(new File(outcome2.getDestinationUri()).exists());
            assertTrue("Transfer from read only source should not delete source file",
                    sourceFile2.toFile().exists());
        }
    }

    @Test(expected = BinaryAlreadyExistsException.class)
    public void repeatTransfer() throws Exception {
        PID binPid = pidMinter.mintContentPid();

        StorageLocation destination = storageManager.getStorageLocationById("loc1");
        Path sourceFile = createSourceFile(sourcePath2, "myfile.txt", "some content");

        try (BinaryTransferSession session = transferService.getSession(destination)) {
            BinaryTransferOutcome outcome = session.transfer(binPid, sourceFile.toUri());

            assertTrue(new File(outcome.getDestinationUri()).exists());
            assertTrue("Transfer from read only source should not delete source file",
                    sourceFile.toFile().exists());

            // Try to transfer the file again
            session.transfer(binPid, sourceFile.toUri());
        }
    }

    @Test
    public void checkIfTransferred() throws Exception {
        PID binPid = pidMinter.mintContentPid();
        StorageLocation destination = storageManager.getStorageLocationById("loc1");
        Path sourceFile = createSourceFile(sourcePath1, "myfile.txt", "some content");
        URI sourceUri = sourceFile.toUri();

        try (BinaryTransferSession session = transferService.getSession(destination)) {
            assertFalse(session.isTransferred(binPid, sourceUri));

            // Perform transfer, should now return true
            session.transfer(binPid, sourceFile.toUri());
            assertTrue(session.isTransferred(binPid, sourceUri));

            // Change the file and see if its still considered transferred
            FileUtils.writeStringToFile(sourceFile.toFile(), "updated", "UTF-8");
            assertFalse(session.isTransferred(binPid, sourceUri));
        }
    }

    @Test
    public void rollbackNewFiles() throws Exception {
        PID binPid1 = pidMinter.mintContentPid();
        PID binPid2 = pidMinter.mintContentPid();
        StorageLocation destination = storageManager.getStorageLocationById("loc1");
        Path sourceFile1 = createSourceFile(sourcePath1, "myfile.txt", "some content");
        Path sourceFile2 = createSourceFile(sourcePath1, "myfile2.txt", "some more");
        URI sourceUri1 = sourceFile1.toUri();
        URI sourceUri2 = sourceFile2.toUri();

        FedoraTransaction tx = txManager.startTransaction();
        try (BinaryTransferSession session = transferService.getSession(destination)) {
            session.transfer(binPid1, sourceUri1);
            session.transfer(binPid2, sourceUri2);

            assertTrue(session.isTransferred(binPid1, sourceUri1));
            assertTrue(session.isTransferred(binPid2, sourceUri2));

            tx.cancelAndIgnore();

            // Binaries should no longer exist, after a short delay
            Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> !session.isTransferred(binPid1, sourceUri1)
                        && !session.isTransferred(binPid2, sourceUri2));
        } finally {
            tx.close();
        }
    }

    @Test
    public void rollbackUpdatedFile() throws Exception {
        StorageLocation destination = storageManager.getStorageLocationById("loc1");
        String filename = "myfile.txt";
        Path sourceFile1 = createSourceFile(sourcePath1, filename, "some content");
        Path sourceFile2 = createSourceFile(sourcePath2, filename, "updated content");
        URI sourceUri1 = sourceFile1.toUri();
        URI sourceUri2 = sourceFile2.toUri();

        // Create work with the initial state of the binary
        WorkObject workObj = repoObjFactory.createWorkObject(null);
        FileObject fileObj = repoObjFactory.createFileObject(null);
        workObj.addMember(fileObj);
        PID originalPid = DatastreamPids.getOriginalFilePid(fileObj.getPid());
        URI firstVersionContentUri;
        try (BinaryTransferSession session = transferService.getSession(destination)) {
            BinaryTransferOutcome outcome = session.transfer(originalPid, sourceUri1);
            firstVersionContentUri = outcome.getDestinationUri();
            BinaryObject binObj = fileObj.addOriginalFile(firstVersionContentUri, filename, "text/plain", null, null);

            assertTrue(session.isTransferred(originalPid, sourceUri1));
            assertTrue(FileUtils.contentEquals(new File(sourceUri1), new File(binObj.getContentUri())));
        }

        // Change the contents of the file in a transaction
        FedoraTransaction tx = txManager.startTransaction();
        try (BinaryTransferSession session = transferService.getSession(destination)) {

            BinaryTransferOutcome outcome = session.transferReplaceExisting(originalPid, sourceUri2);

            repoObjFactory.createOrUpdateBinary(originalPid,
                    outcome.getDestinationUri(), filename, "text/plain", null, null, null);

            BinaryObject originalBinary = repoObjLoader.getBinaryObject(originalPid);
            assertTrue("Content of the binary in tx must match the updated content",
                    FileUtils.contentEquals(new File(sourceUri2), new File(originalBinary.getContentUri())));

            assertTrue("First version must still exist", new File(firstVersionContentUri).exists());
            assertTrue("New file version must exist", new File(outcome.getDestinationUri()).exists());

            // Rollback the transaction
            tx.cancelAndIgnore();

            originalBinary = repoObjLoader.getBinaryObject(originalPid);
            assertTrue("Content of the binary after canceling tx must be the original file",
                    FileUtils.contentEquals(new File(sourceUri1), new File(originalBinary.getContentUri())));

            // old binary should still exist
            assertTrue(new File(firstVersionContentUri).exists());
            // New binary should no longer exist
            Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> !new File(outcome.getDestinationUri()).exists());
        } finally {
            tx.close();
        }
    }

    // Ensure that rollback succeeds when there is nothing to undo
    @Test
    public void rollbackWithNoBinaryChanges() throws Exception {
        FedoraTransaction tx = txManager.startTransaction();
        try {
            WorkObject workObj = repoObjFactory.createWorkObject(null);
            assertTrue(repoObjFactory.objectExists(workObj.getUri()));
            tx.cancelAndIgnore();
            assertFalse(repoObjFactory.objectExists(workObj.getUri()));
        } finally {
            tx.close();
        }
    }

    private Map<String, Object> addStorageLocation(String id, String name, Path base) throws IOException {
        Map<String, Object> info = new HashMap<>();
        info.put("id", id);
        info.put("name", name);
        info.put("type", HashedFilesystemStorageLocation.TYPE_NAME);
        info.put("base", base.toUri().toString());
        return info;
    }

    @SafeVarargs
    public static final Path createStorageConfigFile(Map<String, Object>... configs) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path configPath = Files.createTempFile("storageConfig", ".json");
        mapper.writeValue(configPath.toFile(), configs);
        return configPath;
    }

    private Path createSourceFile(Path sourcePath, String filename, String content) throws Exception {
        Path sourceFile = sourcePath.resolve(filename);
        FileUtils.writeStringToFile(sourceFile.toFile(), content, "UTF-8");
        return sourceFile;
    }
}
