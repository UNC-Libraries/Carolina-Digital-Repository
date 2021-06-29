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

import static edu.unc.lib.boxc.common.xml.SecureXMLFactory.createSAXBuilder;
import static edu.unc.lib.boxc.model.api.xml.JDOMNamespaceUtil.MODS_V3_NS;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.output.XMLOutputter;

import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.fedora.ServiceException;
import edu.unc.lib.boxc.common.metrics.TimerFactory;
import edu.unc.lib.dl.persist.services.edit.UpdateDescriptionService.UpdateDescriptionRequest;
import edu.unc.lib.dl.services.OperationsMessageSender;
import io.dropwizard.metrics5.Timer;

/**
 * Service that manages editing of the mods:title property on an object
 *
 * @author smithjp
 *
 */
public class EditTitleService {

    private AccessControlService aclService;
    private RepositoryObjectLoader repoObjLoader;
    private UpdateDescriptionService updateDescriptionService;
    private OperationsMessageSender operationsMessageSender;

    private static final Timer timer = TimerFactory.createTimerForClass(EditTitleService.class);

    public EditTitleService() {
    }

    /**
     * Changes an object's mods:title
     *
     * @param agent security principals of the agent making request
     * @param pid the pid of the object whose label is to be changed
     * @param title the new label (dc:title) of the given object
     */
    public void editTitle(AgentPrincipals agent, PID pid, String title) {
        try (Timer.Context context = timer.time()) {

            aclService.assertHasAccess(
                    "User does not have permissions to edit titles",
                    pid, agent.getPrincipals(), Permission.editDescription);

            ContentObject obj = (ContentObject) repoObjLoader.getRepositoryObject(pid);
            BinaryObject mods = obj.getDescription();

            Document newMods;

            if (mods != null) {
                try (InputStream modsStream = mods.getBinaryStream()) {
                    Document document = createSAXBuilder().build(modsStream);
                    Element rootEl = document.getRootElement();

                    if (hasExistingTitle(rootEl)) {
                        newMods = updateTitle(document, title);
                    } else {
                        newMods = addTitleToMODS(document, title);
                    }
                }
            } else {
                Document document = new Document();
                document.addContent(new Element("mods", MODS_V3_NS));
                newMods = addTitleToMODS(document, title);
            }

            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            new XMLOutputter().output(newMods, outStream);
            InputStream newModsStream = new ByteArrayInputStream(outStream.toByteArray());

            updateDescriptionService.updateDescription(new UpdateDescriptionRequest(agent, pid, newModsStream));
        } catch (JDOMException e) {
            throw new ServiceException("Unable to build mods document for " + pid, e);
        } catch (IOException e) {
            throw new ServiceException("Unable to build new mods stream for " + pid, e);
        }

        // Send message that the action completed
        operationsMessageSender.sendUpdateDescriptionOperation(
                agent.getUsername(), Arrays.asList(pid));
    }

    /**
     * @param aclService the aclService to set
     */
    public void setAclService(AccessControlService aclService) {
        this.aclService = aclService;
    }

    public RepositoryObjectLoader getRepoObjLoader() {
        return repoObjLoader;
    }

    /**
     *
     * @param repoObjLoader
     */
    public void setRepoObjLoader(RepositoryObjectLoader repoObjLoader) {
        this.repoObjLoader = repoObjLoader;
    }

    /**
     *
     * @param mods the mods record to be edited
     * @return true if mods has title
     */
    private boolean hasExistingTitle(Element mods) {
        try {
            mods.getChild("titleInfo", MODS_V3_NS).getChild("title", MODS_V3_NS);
            return true;
        } catch (NullPointerException e) {
            return false;
        }
    }

    /**
     *
     * @param doc the mods document to be edited
     * @param title the new title to be added to the mods document
     * @return updated mods document
     */
    private Document addTitleToMODS(Document doc, String title) {
        doc.getRootElement()
                .addContent(new Element("titleInfo", MODS_V3_NS)
                        .addContent(new Element("title", MODS_V3_NS)
                                .setText(title)));

        return doc;
    }

    /**
     *
     * @param doc
     * @param title
     * @return updated mods document
     */
    private Document updateTitle(Document doc, String title) {
        Element oldTitle = doc.getRootElement().getChild("titleInfo", MODS_V3_NS).getChild("title",
                MODS_V3_NS);
        oldTitle.setText(title);

        return doc;
    }

    /**
     * @param updateDescriptionService the updateDescriptionService to set
     */
    public void setUpdateDescriptionService(UpdateDescriptionService updateDescriptionService) {
        this.updateDescriptionService = updateDescriptionService;
    }

    /**
     * @param operationsMessageSender
     */
    public void setOperationsMessageSender(OperationsMessageSender operationsMessageSender) {
        this.operationsMessageSender = operationsMessageSender;
    }
}
