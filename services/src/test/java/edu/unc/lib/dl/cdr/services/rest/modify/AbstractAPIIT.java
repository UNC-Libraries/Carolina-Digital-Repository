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
package edu.unc.lib.dl.cdr.services.rest.modify;

import static com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance;
import static edu.unc.lib.dl.fcrepo4.RepositoryPaths.getContentRootPid;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;

import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.GroupsThreadStore;
import edu.unc.lib.dl.fcrepo4.ContentRootObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryInitializer;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.test.TestHelper;

/**
 *
 * @author harring
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
public abstract class AbstractAPIIT {

    @Autowired(required = false)
    protected String baseAddress;
    @Autowired
    protected WebApplicationContext context;
    @Autowired(required = false)
    protected AccessControlService aclService;
    @Autowired(required = false)
    protected RepositoryObjectFactory repositoryObjectFactory;
    @Autowired(required = false)
    protected RepositoryObjectLoader repositoryObjectLoader;
    @Autowired(required = false)
    protected RepositoryPIDMinter pidMinter;
    @Autowired(required = false)
    protected RepositoryObjectTreeIndexer treeIndexer;
    @Autowired(required = false)
    protected RepositoryInitializer repoInitializer;

    protected ContentRootObject contentRoot;

    protected MockMvc mvc;

    @Before
    public void init() {

        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .build();

        TestHelper.setContentBase("http://localhost:48085/rest");

        GroupsThreadStore.storeUsername("test_user");
        GroupsThreadStore.storeGroups(new AccessGroupSet("adminGroup"));

    }

    @After
    public void tearDown() {
        GroupsThreadStore.clearStore();
    }

    protected void setupContentRoot() {
        repoInitializer.initializeRepository();
        contentRoot = repositoryObjectLoader.getContentRootObject(getContentRootPid());
    }

    protected PID makePid() {
        return PIDs.get(UUID.randomUUID().toString());
    }

    protected Map<String, Object> getMapFromResponse(MvcResult result) throws Exception {
        MapType type = defaultInstance().constructMapType(HashMap.class, String.class, Object.class);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(result.getResponse().getContentAsString(), type);
    }

    protected byte[] makeRequestBody(Object details) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsBytes(details);
    }
}
