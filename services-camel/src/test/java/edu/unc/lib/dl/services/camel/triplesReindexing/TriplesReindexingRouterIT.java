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
package edu.unc.lib.dl.services.camel.triplesReindexing;

import static edu.unc.lib.dl.fcrepo4.RepositoryPaths.getContentRootPid;
import static edu.unc.lib.dl.util.IndexingActionType.RECURSIVE_REINDEX;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.jena.rdf.model.ModelFactory.createDefaultModel;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.builder.NotifyBuilder;
import org.apache.camel.test.spring.CamelSpringRunner;
import org.apache.camel.test.spring.CamelTestContextBootstrapper;
import org.apache.commons.io.FileUtils;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sparql.core.DatasetImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;

import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.CollectionObject;
import edu.unc.lib.dl.fcrepo4.ContentRootObject;
import edu.unc.lib.dl.fcrepo4.DepositRecord;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.RepositoryInitializer;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPaths;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.services.IndexingMessageSender;
import edu.unc.lib.dl.sparql.SparqlQueryService;
import edu.unc.lib.dl.test.TestHelper;

/**
 *
 * @author bbpennel
 *
 */
@RunWith(CamelSpringRunner.class)
@BootstrapWith(CamelTestContextBootstrapper.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml"),
    @ContextConfiguration("/spring-test/jms-context.xml"),
    @ContextConfiguration("/triples-reindexing-it-context.xml")
})
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
public class TriplesReindexingRouterIT {

    @Autowired
    private CamelContext fcrepoTriplestoreIndexer;

    @Autowired
    private String baseAddress;
    @Autowired
    private String fusekiPort;

    @Autowired
    private RepositoryObjectLoader repositoryObjectLoader;
    @Autowired
    private RepositoryObjectFactory repositoryObjectFactory;
    @Autowired
    private RepositoryInitializer repoInitializer;
    @Autowired
    private IndexingMessageSender messageSender;
    @Autowired
    private AccessControlService aclService;
    @Autowired
    private SparqlQueryService sparqlQueryService;

    private Model fusekiModel;
    private FusekiServer fusekiServer;

    @Autowired
    private String indexingEndpoint;

    @Mock
    private Exchange exchange;
    @Mock
    private Message message;

    private ContentRootObject rootObj;
    private AdminUnit unitObj;
    private CollectionObject collObj;
    private FolderObject folderObj1;
    private FolderObject folderObj2;
    private WorkObject workObj;
    private FileObject fileObj;

    @Before
    public void setUp() throws Exception {
        initMocks(this);

        fusekiModel = createDefaultModel();

        final Dataset ds = new DatasetImpl(fusekiModel);
        fusekiServer = FusekiServer.create()
                .setPort(Integer.parseInt(fusekiPort))
                .setContextPath("/fuseki")
                .add("/test", ds)
                .build();
        fusekiServer.start();

        TestHelper.setContentBase(baseAddress);

        when(aclService.hasAccess(any(PID.class), any(AccessGroupSet.class),
                any(Permission.class))).thenReturn(true);

        when(exchange.getIn()).thenReturn(message);

        generateBaseStructure();
    }

    @After
    public void tearDown() throws Exception {
        fusekiServer.stop();
    }

    private void generateBaseStructure() throws Exception {
        repoInitializer.initializeRepository();
        rootObj = repositoryObjectLoader.getContentRootObject(getContentRootPid());

        unitObj = repositoryObjectFactory.createAdminUnit(null);
        rootObj.addMember(unitObj);

        collObj = repositoryObjectFactory.createCollectionObject(null);
        unitObj.addMember(collObj);

        folderObj1 = repositoryObjectFactory.createFolderObject(null);
        folderObj2 = repositoryObjectFactory.createFolderObject(null);
        collObj.addMember(folderObj1);
        collObj.addMember(folderObj2);

        workObj = repositoryObjectFactory.createWorkObject(null);
        folderObj1.addMember(workObj);

        File contentFile = File.createTempFile("test", ".txt");
        FileUtils.write(contentFile, "content", UTF_8);
        fileObj = workObj.addDataFile(contentFile.toPath().toUri(), "file.txt", null, null, null);
    }

    @Test
    public void testIndexingSingle() throws Exception {
        messageSender.sendIndexingOperation("user", folderObj2.getPid(), RECURSIVE_REINDEX);

        // 3 resources compose the folder
        NotifyBuilder notify = new NotifyBuilder(fcrepoTriplestoreIndexer)
                .from(indexingEndpoint)
                .whenDone(2)
                .create();

        notify.matches(5l, TimeUnit.SECONDS);

        assertIndexed(folderObj2);
    }

    @Test
    public void testIndexingFromContentRoot() throws Exception {
        PID contentPid = RepositoryPaths.getContentBasePid();
        messageSender.sendIndexingOperation("user", contentPid, RECURSIVE_REINDEX);

        // Wait for roughly all of the objects to be indexed
        NotifyBuilder notify = new NotifyBuilder(fcrepoTriplestoreIndexer)
                .from(indexingEndpoint)
                .whenCompleted(15)
                .create();

        notify.matches(25l, TimeUnit.SECONDS);

        assertIndexed(rootObj);
        assertIndexed(unitObj);
        assertIndexed(collObj);
        assertIndexed(folderObj1);
        assertIndexed(folderObj2);
        assertIndexed(workObj);
        assertIndexed(fileObj);
    }

    @Test
    public void testIndexingFromRepoRoot() throws Exception {
        // Create a deposit record
        DepositRecord depositRec = repositoryObjectFactory.createDepositRecord(null);

        PID contentPid = RepositoryPaths.getRootPid();
        messageSender.sendIndexingOperation("user", contentPid, RECURSIVE_REINDEX);

        // Wait for roughly all of the objects to be indexed
        NotifyBuilder notify = new NotifyBuilder(fcrepoTriplestoreIndexer)
                .from(indexingEndpoint)
                .whenCompleted(16)
                .create();

        notify.matches(25l, TimeUnit.SECONDS);

        assertIndexed(rootObj);
        assertIndexed(unitObj);
        assertIndexed(collObj);
        assertIndexed(folderObj1);
        assertIndexed(folderObj2);
        assertIndexed(workObj);
        assertIndexed(fileObj);

        assertIndexed(depositRec);
    }

    private void assertIndexed(RepositoryObject repoObj) {
        String query = String.format("select ?pred ?obj where { <%s> ?pred ?obj } limit 1",
                repoObj.getPid().getRepositoryPath());

        try (QueryExecution qExecution = sparqlQueryService.executeQuery(query)) {
            ResultSet resultSet = qExecution.execSelect();

            assertTrue("Object " + repoObj.getPid() + " was not indexed", resultSet.hasNext());
            return;
        }
    }
}
