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
package edu.unc.lib.dl.fcrepo4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoOperationFailedException;
import org.fcrepo.client.FcrepoResponse;
import org.fcrepo.client.PostBuilder;
import org.fcrepo.client.PutBuilder;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.test.SelfReturningAnswer;

/**
 *
 * @author bbpennel
 * @author harring
 *
 */
public class RepositoryObjectFactoryTest {

    @Mock
    private LdpContainerFactory ldpFactory;
    @Mock
    private RepositoryObjectCacheLoader objectCacheLoader;
    @Mock
    private RepositoryObjectDataLoader dataLoader;
    @Mock
    private FcrepoClient fcrepoClient;
    @Mock
    private PutBuilder mockPutBuilder;
    @Mock
    private PostBuilder mockPostBuilder;
    @Mock
    private FcrepoResponse mockResponse;

    private RepositoryObjectFactory repoObjFactory;
    private RepositoryPIDMinter pidMinter;
    private List<URI> linkHeaders;

    @Before
    public void init() throws FcrepoOperationFailedException, URISyntaxException {
        initMocks(this);

        repoObjFactory = new RepositoryObjectFactory();
        repoObjFactory.setClient(fcrepoClient);
        repoObjFactory.setLdpFactory(ldpFactory);
        pidMinter = new RepositoryPIDMinter();
        linkHeaders = new ArrayList<>();
        URI testHeader = new URI("/path/to/resource");
        linkHeaders.add(testHeader);

        mockPutBuilder = mock(PutBuilder.class, new SelfReturningAnswer());
        when(fcrepoClient.put(any(URI.class))).thenReturn(mockPutBuilder);
        when(mockPutBuilder.perform()).thenReturn(mockResponse);

        mockPostBuilder = mock(PostBuilder.class, new SelfReturningAnswer());
        when(fcrepoClient.post(any(URI.class))).thenReturn(mockPostBuilder);
        when(mockPostBuilder.perform()).thenReturn(mockResponse);
        when(mockResponse.getLinkHeaders(any(String.class))).thenReturn(linkHeaders);

    }

    @Test
    public void mintDepositRecordPidTest() {
        PID pid = pidMinter.mintDepositRecordPid();

        assertEquals(pid.getQualifier(), RepositoryPathConstants.DEPOSIT_RECORD_BASE);
        assertTrue(pid.getQualifiedId().startsWith(RepositoryPathConstants.DEPOSIT_RECORD_BASE));
    }

    @Test
    public void createDepositRecordTest() {

        DepositRecord obj = repoObjFactory.createDepositRecord(null);
        assertNotNull(obj);
    }

    @Test
    public void createAdminUnitTest() {

        AdminUnit obj = repoObjFactory.createAdminUnit();
        assertNotNull(obj);
    }

    @Test
    public void createCollectionObjectTest() throws Exception {

        CollectionObject obj = repoObjFactory.createCollectionObject();
        assertNotNull(obj);
    }

    @Test
    public void createFolderObjectTest() throws Exception {

        FolderObject obj = repoObjFactory.createFolderObject();
        assertNotNull(obj);
    }

    @Test
    public void createWorkObjectTest() {

        WorkObject obj = repoObjFactory.createWorkObject();
        assertNotNull(obj);
    }

    @Test
    public void createFileObjectTest() {

        FileObject obj = repoObjFactory.createFileObject();
        assertNotNull(obj);
    }

    @Test
    public void createBinaryTest() throws FcrepoOperationFailedException {
        URI binaryUri = URI.create(RepositoryPaths.getContentBase());
        when(mockResponse.getLocation()).thenReturn(binaryUri);

        String slug = "slug";
        InputStream content = mock(InputStream.class);
        String filename = "file.ext";
        String mimetype = "application/octet-stream";
        String checksum = "checksum";

        BinaryObject obj = repoObjFactory.createBinary(binaryUri, slug, content, filename,
                mimetype, checksum, null);

        assertTrue(obj.getPid().getRepositoryPath().startsWith(binaryUri.toString()));
     // check to see that client creates FcrepoResponse
        verify(mockPostBuilder).perform();
    }

    @Test
    public void createPremisEventTest() throws FcrepoOperationFailedException {

        PID parentPid = pidMinter.mintContentPid();
        PID eventPid = pidMinter.mintPremisEventPid(parentPid);
        URI eventUri = eventPid.getRepositoryUri();
        when(mockResponse.getLocation()).thenReturn(eventUri);

        final Model model = ModelFactory.createDefaultModel();
        Resource resc = model.getResource(eventPid.getRepositoryPath());
        resc.addProperty(Premis.hasEventType, Premis.Ingestion);

        PremisEventObject obj = repoObjFactory.createPremisEvent(eventPid, model);

        assertEquals(eventPid, obj.getPid());
        // check to see that client creates FcrepoResponse
        verify(mockPutBuilder).perform();
    }

    @Test
    public void addMemberTest() {
        PID parentPid = pidMinter.mintContentPid();
        ContentObject parent = mock(ContentObject.class);
        when(parent.getPid()).thenReturn(parentPid);

        PID memberPid = pidMinter.mintContentPid();
        ContentObject member = mock(ContentObject.class);
        when(member.getPid()).thenReturn(memberPid);

        repoObjFactory.addMember(parent, member);
    }
}
