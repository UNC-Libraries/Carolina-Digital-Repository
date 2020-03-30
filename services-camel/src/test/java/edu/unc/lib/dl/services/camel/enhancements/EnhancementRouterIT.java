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
package edu.unc.lib.dl.services.camel.enhancements;

import static edu.unc.lib.dl.model.DatastreamPids.getTechnicalMetadataPid;
import static edu.unc.lib.dl.services.RunEnhancementsMessageHelpers.makeEnhancementOperationBody;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.camel.BeanInject;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.NotifyBuilder;
import org.apache.commons.io.FileUtils;
import org.jdom2.Document;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.services.edit.UpdateDescriptionService;
import edu.unc.lib.dl.services.MessageSender;
import edu.unc.lib.dl.services.camel.BinaryMetadataProcessor;
import edu.unc.lib.dl.services.camel.fulltext.FulltextProcessor;
import edu.unc.lib.dl.services.camel.images.AddDerivativeProcessor;
import edu.unc.lib.dl.services.camel.solr.SolrIngestProcessor;
import edu.unc.lib.dl.test.RepositoryObjectTreeIndexer;
import edu.unc.lib.dl.test.TestHelper;

/**
 *
 * @author bbpennel
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml"),
    @ContextConfiguration("/spring-test/jms-context.xml"),
    @ContextConfiguration("/enhancement-router-it-context.xml")
})
public class EnhancementRouterIT {

    private final static String FILE_CONTENT = "content";
    private final static String AUTHOR = "theauthor";

    @Autowired
    private String baseAddress;

    @Autowired
    private RepositoryObjectFactory repoObjectFactory;

    @Autowired
    private CamelContext cdrEnhancements;

    @Autowired
    private MessageSender messageSender;

    @BeanInject(value = "addSmallThumbnailProcessor")
    private AddDerivativeProcessor addSmallThumbnailProcessor;

    @BeanInject(value = "addLargeThumbnailProcessor")
    private AddDerivativeProcessor addLargeThumbnailProcessor;

    @BeanInject(value = "addAccessCopyProcessor")
    private AddDerivativeProcessor addAccessCopyProcessor;

    @BeanInject(value = "solrIngestProcessor")
    private SolrIngestProcessor solrIngestProcessor;

    @BeanInject(value = "fulltextProcessor")
    private FulltextProcessor fulltextProcessor;

    @BeanInject(value = "binaryMetadataProcessor")
    private BinaryMetadataProcessor binaryMetadataProcessor;

    @Autowired
    private UpdateDescriptionService updateDescriptionService;
    @Autowired
    protected RepositoryObjectTreeIndexer treeIndexer;

    @Before
    public void init() throws Exception {
        initMocks(this);

        reset(solrIngestProcessor);
        reset(addLargeThumbnailProcessor);
        reset(addSmallThumbnailProcessor);
        reset(addAccessCopyProcessor);

        TestHelper.setContentBase(baseAddress);

        File thumbScriptFile = new File("target/convertScaleStage.sh");
        FileUtils.writeStringToFile(thumbScriptFile, "exit 0", "utf-8");
        thumbScriptFile.deleteOnExit();

        File jp2ScriptFile = new File("target/convertJp2.sh");
        FileUtils.writeStringToFile(jp2ScriptFile, "exit 0", "utf-8");
        jp2ScriptFile.deleteOnExit();
    }

    @Test
    public void testFolderEnhancements_NoChildren() throws Exception {
        FolderObject folderObject = repoObjectFactory.createFolderObject(null);

        treeIndexer.indexAll(baseAddress);

        Document msgBody = makeEnhancementOperationBody(AUTHOR, folderObject.getPid(), null, false);
        messageSender.sendMessage(msgBody);

        NotifyBuilder notify = new NotifyBuilder(cdrEnhancements)
                .whenCompleted(1)
                .create();

        boolean result = notify.matches(3l, TimeUnit.SECONDS);
        assertTrue("Processing message did not match expectations", result);

        verify(solrIngestProcessor, never()).process(any(Exchange.class));
    }

    @Test
    public void testImageFile() throws Exception {
        FileObject fileObj = repoObjectFactory.createFileObject(null);
        Path originalPath = Files.createTempFile("file", ".png");
        FileUtils.writeStringToFile(originalPath.toFile(), FILE_CONTENT, "UTF-8");
        BinaryObject binObj = fileObj.addOriginalFile(originalPath.toUri(),
                null, "image/png", null, null);

        Document msgBody = makeEnhancementOperationBody(AUTHOR, binObj.getPid(), null, false);
        messageSender.sendMessage(msgBody);

        // Separate exchanges when multicasting
        NotifyBuilder notify = new NotifyBuilder(cdrEnhancements)
                .whenCompleted(3)
                .create();

        boolean result = notify.matches(25l, TimeUnit.SECONDS);
        assertTrue("Processing message did not match expectations", result);

        verify(addSmallThumbnailProcessor).process(any(Exchange.class));
        verify(addLargeThumbnailProcessor).process(any(Exchange.class));
        verify(addAccessCopyProcessor).process(any(Exchange.class));
        // Indexing the parent
        verify(solrIngestProcessor).process(any(Exchange.class));
    }

    @Test
    public void testBinaryMetadataFile() throws Exception {
        FileObject fileObj = repoObjectFactory.createFileObject(null);
        Path originalPath = Files.createTempFile("file", ".png");
        FileUtils.writeStringToFile(originalPath.toFile(), FILE_CONTENT, "UTF-8");
        BinaryObject binObj = fileObj.addOriginalFile(originalPath.toUri(),
                null, "image/png", null, null);

        String mdId = binObj.getPid().getRepositoryPath() + "/fcr:metadata";
        PID mdPid = PIDs.get(mdId);

        Document msgBody = makeEnhancementOperationBody(AUTHOR, mdPid, null, false);
        messageSender.sendMessage(msgBody);

        // Separate exchanges when multicasting
        NotifyBuilder notify = new NotifyBuilder(cdrEnhancements)
                .whenFailed(1)
                .create();

        boolean result = notify.matches(5l, TimeUnit.SECONDS);

        assertTrue("Processing message did not match expectations", result);

        verify(addSmallThumbnailProcessor, never()).process(any(Exchange.class));
        verify(addLargeThumbnailProcessor, never()).process(any(Exchange.class));
        verify(addAccessCopyProcessor, never()).process(any(Exchange.class));
        verify(solrIngestProcessor, never()).process(any(Exchange.class));
    }

    @Test
    public void testInvalidFile() throws Exception {
        FileObject fileObj = repoObjectFactory.createFileObject(null);
        PID fitsPid = getTechnicalMetadataPid(fileObj.getPid());
        Path techmdPath = Files.createTempFile("fits", ".xml");
        FileUtils.writeStringToFile(techmdPath.toFile(), FILE_CONTENT, "UTF-8");
        BinaryObject binObj = fileObj.addBinary(fitsPid, techmdPath.toUri(),
                "fits.xml", "text/xml", null, null, null);

        Document msgBody = makeEnhancementOperationBody(AUTHOR, binObj.getPid(), null, false);
        messageSender.sendMessage(msgBody);

        NotifyBuilder notify = new NotifyBuilder(cdrEnhancements)
                .whenCompleted(1)
                .create();

        boolean result = notify.matches(5l, TimeUnit.SECONDS);

        assertTrue("Processing message did not match expectations", result);

        verify(addSmallThumbnailProcessor, never()).process(any(Exchange.class));
        verify(fulltextProcessor,  never()).process(any(Exchange.class));
        verify(solrIngestProcessor, never()).process(any(Exchange.class));
    }

    @Test
    public void testProcessFilterOutDescriptiveMDSolr() throws Exception {
        FileObject fileObj = repoObjectFactory.createFileObject(null);
        BinaryObject descObj = updateDescriptionService.updateDescription(mock(AgentPrincipals.class),
                fileObj.getPid(), new ByteArrayInputStream(FILE_CONTENT.getBytes()));

        Document msgBody = makeEnhancementOperationBody(AUTHOR, descObj.getPid(), null, false);
        messageSender.sendMessage(msgBody);

        NotifyBuilder notify = new NotifyBuilder(cdrEnhancements)
                .whenCompleted(1)
                .create();

        boolean result = notify.matches(5l, TimeUnit.SECONDS);

        assertTrue("Processing message did not match expectations", result);

        verify(solrIngestProcessor, never()).process(any(Exchange.class));
    }

    @Test
    public void testTreeEnhancements() throws Exception {
        FolderObject folderObject = repoObjectFactory.createFolderObject(null);

        WorkObject workObj = folderObject.addWork();
        Path originalPath = Files.createTempFile("file", ".png");
        FileUtils.writeStringToFile(originalPath.toFile(), FILE_CONTENT, "UTF-8");
        workObj.addDataFile(originalPath.toUri(),
                null, "image/png", null, null);

        treeIndexer.indexAll(baseAddress);

        Document msgBody = makeEnhancementOperationBody(AUTHOR, folderObject.getPid(), null, false);
        messageSender.sendMessage(msgBody);

        // Separate exchanges when multicasting
        NotifyBuilder notify = new NotifyBuilder(cdrEnhancements)
                .whenCompleted(8)
                .create();

        boolean result = notify.matches(40l, TimeUnit.SECONDS);
        assertTrue("Processing message did not match expectations", result);

        verify(addSmallThumbnailProcessor).process(any(Exchange.class));
        verify(addLargeThumbnailProcessor).process(any(Exchange.class));
        verify(addAccessCopyProcessor).process(any(Exchange.class));
        // Indexing the parent
        verify(solrIngestProcessor).process(any(Exchange.class));
    }
}
