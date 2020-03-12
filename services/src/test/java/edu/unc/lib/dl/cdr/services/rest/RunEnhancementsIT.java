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

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.fcrepo4.AccessControlServiceImpl;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.GroupsThreadStore;
import edu.unc.lib.dl.cdr.services.rest.modify.AbstractAPIIT;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadataBean;
import edu.unc.lib.dl.search.solr.model.SearchRequest;
import edu.unc.lib.dl.search.solr.model.SearchResultResponse;
import edu.unc.lib.dl.search.solr.model.SimpleIdRequest;
import edu.unc.lib.dl.ui.service.SolrQueryLayerService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;
import java.util.Map;

import static edu.unc.lib.dl.acl.util.Permission.runEnhancements;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author lfarrell
 *
 */
@ContextHierarchy({
        @ContextConfiguration("/spring-test/test-fedora-container.xml"),
        @ContextConfiguration("/spring-test/cdr-client-container.xml"),
        @ContextConfiguration("/spring-test/acl-service-context.xml"),
        @ContextConfiguration("/run-enhancements-it-servlet.xml")
})
public class RunEnhancementsIT extends AbstractAPIIT {
    private static final String USER_NAME = "user";
    private static final String ADMIN_GROUP = "adminGroup";
    private static final String UUID_TEST = "c9876360-18f0-460e-bcbe-f626c26e851e";

    @Autowired
    private JmsTemplate jmsTemplate;
    @Autowired
    private AccessControlServiceImpl aclServices;
    @Autowired
    private SolrQueryLayerService queryLayer;

    @Before
    public void setup() throws Exception {
        reset(jmsTemplate);

        AccessGroupSet testPrincipals = new AccessGroupSet(ADMIN_GROUP);

        GroupsThreadStore.storeUsername(USER_NAME);
        GroupsThreadStore.storeGroups(testPrincipals);


        BriefObjectMetadataBean md = new BriefObjectMetadataBean();
        md.setId(UUID_TEST);
        md.setTitle("Test Item");
        md.setDatastream(asList("original_file|image/png|small|png|3333||"));

        // Non file
        SearchResultResponse results = mock(SearchResultResponse.class);
        when(queryLayer.performSearch(any(SearchRequest.class))).thenReturn(results);
        when(results.getResultList()).thenReturn(Arrays.asList(md));

        // File
        when(queryLayer.getObjectById(any(SimpleIdRequest.class))).thenReturn(md);

        setupContentRoot();
    }

    @Test
    public void runEnhancementsFileObject() throws Exception {
        MvcResult result = mvc.perform(post("/runEnhancements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"force\":false,\"pids\":[{\"pid\":\"" + UUID_TEST + "\"," +
                        "\"objectType\":\"File\"}]}")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        assertResponseSuccess(result);

        verify(jmsTemplate).send(any(MessageCreator.class));
    }

    @Test
    public void runEnhancementsNonFileObject() throws Exception {
        MvcResult result = mvc.perform(post("/runEnhancements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"force\":false,\"pids\":[{\"pid\":\"" + UUID_TEST + "\"," +
                        "\"objectType\":\"Collection\"}]}")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        assertResponseSuccess(result);

        verify(jmsTemplate).send(any(MessageCreator.class));
    }

    @Test
    public void runEnhancementsNoAccess() throws Exception {
        PID objPid = makePid();
        doThrow(new AccessRestrictionException()).when(aclServices)
                .assertHasAccess(anyString(), eq(objPid), any(AccessGroupSet.class), eq(runEnhancements));

        String uuidNoAccess = objPid.getUUID();
        mvc.perform(post("/runEnhancements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"force\":false,\"pids\":[{\"pid\":\"" + uuidNoAccess + "\"," +
                        "\"objectType\":\"Collection\"}]}")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andReturn();;

        verify(jmsTemplate, never()).send(any(MessageCreator.class));
    }

    private void assertResponseSuccess(MvcResult mvcResult) throws Exception {
        Map<String, Object> resp = getMapFromResponse(mvcResult);
        assertTrue("Missing run enhancements message", resp.containsKey("message"));
        assertEquals("runEnhancements", resp.get("action"));
    }
}
