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
package edu.unc.lib.dl.services.camel.importxml;

import static edu.unc.lib.boxc.model.api.xml.JDOMNamespaceUtil.MODS_V3_NS;
import static edu.unc.lib.dl.persist.services.importxml.XMLImportTestHelper.addObjectUpdate;
import static edu.unc.lib.dl.persist.services.importxml.XMLImportTestHelper.documentToInputStream;
import static edu.unc.lib.dl.persist.services.importxml.XMLImportTestHelper.makeUpdateDocument;
import static edu.unc.lib.dl.persist.services.importxml.XMLImportTestHelper.modsWithTitleAndDate;
import static edu.unc.lib.dl.persist.services.storage.StorageLocationTestHelper.newStorageLocationTestHelper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import javax.mail.internet.MimeMessage;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.NotifyBuilder;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.samskivert.mustache.Template;

import edu.unc.lib.boxc.auth.api.models.AgentPrincipals;
import edu.unc.lib.boxc.model.api.objects.ContentObject;
import edu.unc.lib.boxc.model.api.objects.FolderObject;
import edu.unc.lib.boxc.model.api.objects.RepositoryObjectLoader;
import edu.unc.lib.boxc.model.api.services.RepositoryObjectFactory;
import edu.unc.lib.boxc.model.fcrepo.test.TestHelper;
import edu.unc.lib.dl.persist.api.storage.StorageLocationManager;
import edu.unc.lib.dl.persist.services.importxml.ImportXMLService;
import edu.unc.lib.dl.persist.services.transfer.BinaryTransferServiceImpl;

/**
 * @author bbpennel
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml"),
    @ContextConfiguration("/spring-test/jms-context.xml"),
    @ContextConfiguration("/import-xml-it-context.xml")
})
public class ImportXMLIT {

    private final static String USER_EMAIL = "user@example.com";

    private final static String UPDATED_TITLE = "Updated Work Title";
    private final static String UPDATED_DATE = "2018-04-06";

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    @Autowired
    private String baseAddress;

    @Autowired
    private RepositoryObjectFactory repoObjectFactory;
    @javax.annotation.Resource(name = "repositoryObjectLoader")
    private RepositoryObjectLoader repoObjectLoader;

    @Autowired
    private CamelContext cdrImportXML;

    @Autowired
    private JmsTemplate importXmlJmsTemplate;

    private ImportXMLService importXmlService;

    private StorageLocationManager locationManager;
    @Autowired
    private BinaryTransferServiceImpl binaryTransferService;
    @Autowired
    private ImportXMLProcessor importXMLProcessor;
    @Autowired
    private JavaMailSender mailSender;
    @Mock
    private MimeMessage mimeMessage;
    @Autowired
    private Template updateCompleteTemplate;

    @Mock
    private AgentPrincipals agent;

    @Before
    public void setup() throws Exception {
        initMocks(this);

        TestHelper.setContentBase(baseAddress);

        importXmlService = new ImportXMLService();
        importXmlService.setJmsTemplate(importXmlJmsTemplate);
        importXmlService.setDataDir(tmpFolder.getRoot().getAbsolutePath());
        importXmlService.init();

        locationManager = newStorageLocationTestHelper()
                .addTestLocation()
                .createLocationManager(repoObjectLoader);

        binaryTransferService.setStorageLocationManager(locationManager);
        importXMLProcessor.setLocationManager(locationManager);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(updateCompleteTemplate.execute(any())).thenReturn("");
    }

    @Test
    public void updateDescription() throws Exception {
        FolderObject folderObject = repoObjectFactory.createFolderObject(null);

        Document updateDoc = makeUpdateDocument();
        addObjectUpdate(updateDoc, folderObject.getPid(), null)
            .addContent(modsWithTitleAndDate(UPDATED_TITLE, UPDATED_DATE));
        InputStream importStream = documentToInputStream(updateDoc);

        importXmlService.pushJobToQueue(importStream, agent, USER_EMAIL);

        NotifyBuilder notify = new NotifyBuilder(cdrImportXML)
                .whenCompleted(1)
                .create();

        boolean result = notify.matches(5l, TimeUnit.SECONDS);
        assertTrue("Processing message did not match expectations", result);

        FolderObject updateObject = repoObjectLoader.getFolderObject(folderObject.getPid());
        Document modsDoc = parseDescription(updateObject);
        assertModsFields(modsDoc, UPDATED_TITLE, UPDATED_DATE);
    }

    private Document parseDescription(ContentObject obj) throws Exception {
        SAXBuilder builder = new SAXBuilder();
        return builder.build(obj.getDescription().getBinaryStream());
    }

    private void assertModsFields(Document doc, String expectedTitle, String expectedDate) throws Exception {
        Element rootEl = doc.getRootElement();
        String title = rootEl.getChild("titleInfo", MODS_V3_NS).getChildText("title", MODS_V3_NS);
        String dateCreated = rootEl.getChild("originInfo", MODS_V3_NS).getChildText("dateCreated", MODS_V3_NS);
        assertEquals(expectedTitle, title);
        assertEquals(expectedDate, dateCreated);
    }
}
