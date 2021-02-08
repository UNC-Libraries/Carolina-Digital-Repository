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
package edu.unc.lib.dl.persist.services.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.storage.StorageType;

/**
 * @author bbpennel
 *
 */
public class HashedFilesystemStorageLocationTest {

    private static final String TEST_UUID = "2e8f7551-ef3c-4387-8c3d-a38609927800";

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();
    protected Path storagePath;

    protected HashedFilesystemStorageLocation loc;

    @Before
    public void setup() throws Exception {
        storagePath = tmpFolder.newFolder("storage").toPath();

        loc = new HashedFilesystemStorageLocation();
        loc.setBase(storagePath.toString());
    }

    @Test
    public void getStorageType() {
        assertEquals(StorageType.FILESYSTEM, loc.getStorageType());
    }

    @Test
    public void baseFieldWithFileScheme() {
        String assignedBase = "file:/path/to/my/stuff/";
        loc.setBase(assignedBase);

        assertEquals(assignedBase, loc.getBase());
        assertEquals(URI.create(assignedBase), loc.getBaseUri());
    }

    @Test
    public void baseFieldWithTripleSlashes() {
        String assignedBase = "file:///path/to/my/stuff/";
        String expectedBase = "file:/path/to/my/stuff/";
        loc.setBase(assignedBase);

        assertEquals(expectedBase, loc.getBase());
        assertEquals(URI.create(expectedBase), loc.getBaseUri());
    }

    @Test
    public void baseFieldWithFileSchemeWithoutTrailingSlash() {
        String assignedBase = "file:/path/to/my/stuff";
        loc.setBase(assignedBase);

        assertEquals(assignedBase + "/", loc.getBase());
        assertEquals(URI.create(assignedBase + "/"), loc.getBaseUri());
    }

    @Test
    public void baseFieldWithNoScheme() {
        String expectedBase = "file:/path/to/my/stuff/";
        String assignedBase = "/path/to/my/stuff/";
        loc.setBase(assignedBase);

        assertEquals(expectedBase, loc.getBase());
        assertEquals(URI.create(expectedBase), loc.getBaseUri());
    }

    @Test(expected = IllegalArgumentException.class)
    public void baseFieldWithInvalidScheme() {
        String assignedBase = "http://example.com/to/my/stuff/";
        loc.setBase(assignedBase);
    }

    @Test
    public void getNewStorageUriForPidWithoutComponent() {
        String assignedBase = "file:/location/path/";

        loc.setId("loc1");
        loc.setBase(assignedBase);

        PID pid = PIDs.get(TEST_UUID);

        URI storageUri = loc.getNewStorageUri(pid);
        assertTrue(storageUri.toString().startsWith(assignedBase + "2e/8f/75/51/" + TEST_UUID));

    }

    @Test
    public void getNewStorageUriForPidWithComponent() {
        String assignedBase = "file:/location/path/";
        String component = "/datafs/original_data";

        loc.setId("loc1");
        loc.setBase(assignedBase);

        PID pid = PIDs.get(TEST_UUID + component);

        URI storageUri = loc.getNewStorageUri(pid);
        assertTrue(storageUri.toString().startsWith(assignedBase + "2e/8f/75/51/" + TEST_UUID + component));
    }

    @Test
    public void getNewStorageUriNoConflicts() {
        PID pid = PIDs.get(TEST_UUID);
        URI storageUri1 = loc.getNewStorageUri(pid);
        URI storageUri2 = loc.getNewStorageUri(pid);
        assertNotNull(storageUri1);
        assertNotNull(storageUri2);
        assertNotEquals(storageUri1, storageUri2);
    }

    @Test
    public void getCurrentStorageUriNoExisting() {
        PID pid = PIDs.get(TEST_UUID);
        assertNull(loc.getCurrentStorageUri(pid));
    }

    @Test
    public void getCurrentStorageUriOneExisting() throws Exception {
        PID pid = PIDs.get(TEST_UUID);
        URI expectedUri = createNewStorageUri(pid);

        URI currentUri = loc.getCurrentStorageUri(pid);
        assertEquals(expectedUri, currentUri);
        assertTrue(currentUri.toString().startsWith(storagePath.toFile().toURI().toString()));
    }

    @Test
    public void getCurrentStorageUriMultipleExisting() throws Exception {
        PID pid = PIDs.get(TEST_UUID);
        createNewStorageUri(pid);
        createNewStorageUri(pid);
        createNewStorageUri(pid);
        URI expectedUri = createNewStorageUri(pid);

        assertEquals(expectedUri, loc.getCurrentStorageUri(pid));
    }

    private URI createNewStorageUri(PID pid) throws IOException {
        URI uri = loc.getNewStorageUri(pid);
        Path destPath = Paths.get(uri);
        Files.createDirectories(destPath.getParent());
        FileUtils.writeStringToFile(destPath.toFile(), "content", "UTF-8");
        return uri;
    }

    @Test
    public void isValidUriInLocation() {
        String assignedBase = "file:/location/path/";
        String subpath = "ab/cd/ef/gh/abcdefgh";
        URI targetUri = URI.create(assignedBase + subpath);

        loc.setId("loc1");
        loc.setBase(assignedBase);

        assertTrue(loc.isValidUri(targetUri));
    }

    @Test
    public void isValidUriNotInLocation() {
        String assignedBase = "file:///location/path/";
        URI targetUri = URI.create("file:///some/other/path/to/my/file.txt");

        loc.setId("loc1");
        loc.setBase(assignedBase);

        assertFalse(loc.isValidUri(targetUri));
    }

    @Test
    public void nameField() {
        loc.setName("My name");
        assertEquals("My name", loc.getName());
    }

    @Test
    public void idField() {
        loc.setId("some_id");
        assertEquals("some_id", loc.getId());
    }
}
