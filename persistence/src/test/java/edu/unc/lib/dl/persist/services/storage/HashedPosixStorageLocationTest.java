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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import edu.unc.lib.dl.persist.api.storage.StorageType;

/**
 * @author bbpennel
 */
public class HashedPosixStorageLocationTest extends HashedFilesystemStorageLocationTest {

    private HashedPosixStorageLocation posixLoc;

    @Override
    @Before
    public void setup() throws Exception {
        storagePath = tmpFolder.newFolder("storage").toPath();

        posixLoc = new HashedPosixStorageLocation();
        posixLoc.setBase(storagePath.toString());
        loc = posixLoc;
    }

    @Override
    @Test
    public void getStorageType() {
        assertEquals(StorageType.POSIX_FS, loc.getStorageType());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setPermissionsInvalidFormat() {
        posixLoc.setPermissions("what");
    }

    @Test
    public void getPermissionsNoPermissions() {
        assertNull(posixLoc.getPermissions());
    }

    @Test
    public void getPermissionsWithOctal() {
        posixLoc.setPermissions("0644");
        Set<PosixFilePermission> perms = posixLoc.getPermissions();
        assertEquals(4, perms.size());
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.GROUP_READ));
        assertTrue(perms.contains(PosixFilePermission.OTHERS_READ));
    }
}
