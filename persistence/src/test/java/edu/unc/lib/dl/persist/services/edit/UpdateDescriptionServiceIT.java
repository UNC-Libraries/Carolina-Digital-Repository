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
package edu.unc.lib.dl.persist.services.edit;

import static edu.unc.lib.dl.model.DatastreamType.MD_DESCRIPTIVE;
import static edu.unc.lib.dl.model.DatastreamType.MD_DESCRIPTIVE_HISTORY;
import static edu.unc.lib.dl.persist.services.importxml.XMLImportTestHelper.documentToInputStream;
import static edu.unc.lib.dl.persist.services.importxml.XMLImportTestHelper.modsWithTitleAndDate;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.MODS_V3_NS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.model.DatastreamType;
import edu.unc.lib.dl.persist.services.edit.UpdateDescriptionService.UpdateDescriptionRequest;
import edu.unc.lib.dl.test.TestHelper;

/**
 * @author bbpennel
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/cdr-client-container.xml"),
    @ContextConfiguration("/spring-test/import-job-it.xml")
})
public class UpdateDescriptionServiceIT {
    @Autowired
    private String baseAddress;
    @Autowired
    private RepositoryObjectFactory repoObjFactory;

    @Autowired
    private UpdateDescriptionService updateService;

    @Mock
    private AgentPrincipals agent;

    @Before
    public void init_() throws Exception {
        initMocks(this);

        TestHelper.setContentBase(baseAddress);
    }

    @Test
    public void addDescriptionToWork() throws Exception {
        WorkObject workObj = repoObjFactory.createWorkObject(null);

        addDescription(workObj, "new title", "2018-04-06");

        List<BinaryObject> mdBins = workObj.listMetadata();
        assertEquals(1, mdBins.size());
        BinaryObject modsBin = mdBins.get(0);
        assertEquals(DatastreamPids.getMdDescriptivePid(workObj.getPid()), modsBin.getPid());
        assertEquals(MD_DESCRIPTIVE.getDefaultFilename(), modsBin.getFilename());

        assertHasMods(modsBin.getBinaryStream(), "new title", "2018-04-06");
    }

    @Test
    public void updateDescriptionOnFolder() throws Exception {
        FolderObject folderObj = repoObjFactory.createFolderObject(null);

        addDescription(folderObj, "new title", "2018-04-06");
        addDescription(folderObj, "updated title", "2018-04-08");

        List<BinaryObject> mdBins = folderObj.listMetadata();
        assertEquals(2, mdBins.size());

        BinaryObject modsBin = findDatastream(mdBins, MD_DESCRIPTIVE);
        assertEquals(DatastreamPids.getMdDescriptivePid(folderObj.getPid()), modsBin.getPid());
        assertEquals(MD_DESCRIPTIVE.getDefaultFilename(), modsBin.getFilename());

        assertHasMods(modsBin.getBinaryStream(), "updated title", "2018-04-08");

        // Make sure the history gets added
        assertNotNull(findDatastream(mdBins, MD_DESCRIPTIVE_HISTORY));
    }

    private BinaryObject findDatastream(List<BinaryObject> mdBins, DatastreamType dsType) {
        return mdBins.stream()
                .filter(bin -> bin.getPid().getComponentId().endsWith(dsType.getId()))
                .findFirst()
                .get();
    }

    private void addDescription(ContentObject contentObj, String title, String date) throws Exception {
        Document doc = new Document()
                .addContent(modsWithTitleAndDate(title, date));
        InputStream modsStream = documentToInputStream(doc);
        updateService.updateDescription(new UpdateDescriptionRequest(agent, contentObj, modsStream));
    }

    private void assertHasMods(InputStream updatedMods, String expectedTitle, String expectedDate)
            throws JDOMException, IOException {
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(updatedMods);
        Element rootEl = doc.getRootElement();
        String title = rootEl.getChild("titleInfo", MODS_V3_NS).getChildText("title", MODS_V3_NS);
        String dateCreated = rootEl.getChild("originInfo", MODS_V3_NS).getChildText("dateCreated", MODS_V3_NS);
        assertEquals(expectedTitle, title);
        assertEquals(expectedDate, dateCreated);
    }
}