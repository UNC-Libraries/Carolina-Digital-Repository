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
package edu.unc.lib.dl.cdr.services.rest;

import static edu.unc.lib.dl.acl.util.Permission.runEnhancements;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.ATOM_NS;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.jdom2.Document;
import org.jdom2.Element;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.web.servlet.MvcResult;

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.fcrepo4.AccessControlServiceImpl;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.GroupsThreadStore;
import edu.unc.lib.dl.cdr.services.rest.modify.AbstractAPIIT;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadataBean;
import edu.unc.lib.dl.search.solr.model.SearchRequest;
import edu.unc.lib.dl.search.solr.model.SearchResultResponse;
import edu.unc.lib.dl.search.solr.model.SimpleIdRequest;
import edu.unc.lib.dl.services.MessageSender;
import edu.unc.lib.dl.ui.service.SolrQueryLayerService;

/**
 * @author lfarrell
 *
 */
@ContextHierarchy({
        @ContextConfiguration("/spring-test/test-fedora-container.xml"),
        @ContextConfiguration("/spring-test/cdr-client-container.xml"),
        @ContextConfiguration("/run-enhancements-it-servlet.xml")
})
public class RunEnhancementsIT extends AbstractAPIIT {
    private static final String BINARY_CONTENT = "binary content";
    private static final String USER_NAME = "user";
    private static final String ADMIN_GROUP = "adminGroup";

    @Autowired
    private AccessControlServiceImpl aclServices;
    @Autowired
    private RepositoryObjectFactory repositoryObjectFactory;
    @Autowired
    private SolrQueryLayerService queryLayer;
    @Autowired
    private MessageSender messageSender;

    @Captor
    private ArgumentCaptor<Document> docCaptor;

    private SearchResultResponse results;

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    @Before
    public void setup() throws Exception {
        initMocks(this);
        reset(messageSender);

        AccessGroupSet testPrincipals = new AccessGroupSet(ADMIN_GROUP);

        GroupsThreadStore.storeUsername(USER_NAME);
        GroupsThreadStore.storeGroups(testPrincipals);

        // Non file
        results = mock(SearchResultResponse.class);
        when(queryLayer.performSearch(any(SearchRequest.class))).thenReturn(results);

        setupContentRoot();
    }

    @Test
    public void runEnhancementsFileObject() throws Exception {
        FileObject fileObj = repositoryObjectFactory.createFileObject(null);
        PID filePid = fileObj.getPid();
        fileObj.addOriginalFile(makeContentUri(BINARY_CONTENT), "file.png", "image/png", null, null);
        setResultMetadataObject(filePid);

        MvcResult result = mvc.perform(post("/runEnhancements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"force\":false,\"pids\":[\"" + filePid.getId() + "\"]}")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        assertResponseSuccess(result);

        verify(messageSender).sendMessage(docCaptor.capture());
        Document msgDoc = docCaptor.getValue();
        assertMessageValues(msgDoc, fileObj.getOriginalFile().getPid(), "image/png");
    }

    @Test
    public void runEnhancementsNonFileObject() throws Exception {
        FolderObject folderObj = repositoryObjectFactory.createFolderObject(null);
        FileObject fileObj = folderObj.addWork()
                .addDataFile(makeContentUri(BINARY_CONTENT), "file.png", "image/png", null, null);
        PID filePid = fileObj.getPid();
        setResultMetadataObject(filePid);

        MvcResult result = mvc.perform(post("/runEnhancements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"force\":false,\"pids\":[\"" + filePid.getId() + "\"]}")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        assertResponseSuccess(result);

        verify(messageSender).sendMessage(docCaptor.capture());
        Document msgDoc = docCaptor.getValue();
        assertMessageValues(msgDoc, fileObj.getOriginalFile().getPid(), "image/png");
    }

    @Test
    public void runEnhancementsNoAccess() throws Exception {
        FileObject fileObj = repositoryObjectFactory.createFileObject(null);
        fileObj.addOriginalFile(makeContentUri(BINARY_CONTENT), "file.png", "image/png", null, null);
        setResultMetadataObject(fileObj.getPid());

        PID objPid = fileObj.getPid();
        doThrow(new AccessRestrictionException()).when(aclServices)
                .assertHasAccess(anyString(), eq(objPid), any(AccessGroupSet.class), eq(runEnhancements));

        mvc.perform(post("/runEnhancements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"force\":false,\"pids\":[\"" + objPid.toString() + "\"]}")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andReturn();

        verify(messageSender, never()).sendMessage(any(Document.class));
    }

    private void assertResponseSuccess(MvcResult mvcResult) throws Exception {
        Map<String, Object> resp = getMapFromResponse(mvcResult);
        assertTrue("Missing run enhancements message", resp.containsKey("message"));
        assertEquals("runEnhancements", resp.get("action"));
    }

    private URI makeContentUri(String content) throws Exception {
        File dataFile = tmpFolder.newFile();
        FileUtils.write(dataFile, content, "UTF-8");
        return dataFile.toPath().toUri();
    }

    private void setResultMetadataObject(PID pid) {
        BriefObjectMetadataBean md = new BriefObjectMetadataBean();
        md.setId(pid.getId());
        md.setDatastream(asList("original_file|image/png|small|png|3333||"));

        when(results.getResultList()).thenReturn(Arrays.asList(md));

        when(queryLayer.getObjectById(any(SimpleIdRequest.class))).thenReturn(md);
    }

    private void assertMessageValues(Document msgDoc, PID expectedPid, String expectedType) {
        Element entry = msgDoc.getRootElement();
        String pidString = entry.getChildText("pid", ATOM_NS);
        String mimetype = entry.getChildText("mimeType", ATOM_NS);

        assertEquals(expectedPid, PIDs.get(pidString));
        assertEquals(expectedType, mimetype);
    }
}