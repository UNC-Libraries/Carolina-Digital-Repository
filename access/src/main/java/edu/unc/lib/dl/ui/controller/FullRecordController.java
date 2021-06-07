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
package edu.unc.lib.dl.ui.controller;

import static edu.unc.lib.dl.acl.util.GroupsThreadStore.getAgentPrincipals;
import static edu.unc.lib.dl.search.solr.util.FacetConstants.MARKED_FOR_DELETION;
import static edu.unc.lib.dl.xml.SecureXMLFactory.createSAXBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.NotFoundException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadataBean;
import edu.unc.lib.dl.search.solr.model.SimpleIdRequest;
import edu.unc.lib.dl.search.solr.service.ChildrenCountService;
import edu.unc.lib.dl.search.solr.service.GetCollectionIdService;
import edu.unc.lib.dl.search.solr.service.NeighborQueryService;
import edu.unc.lib.dl.ui.exception.InvalidRecordRequestException;
import edu.unc.lib.dl.ui.exception.RenderViewException;
import edu.unc.lib.dl.ui.service.AccessCopiesService;
import edu.unc.lib.dl.ui.service.FindingAidUrlService;
import edu.unc.lib.dl.ui.util.ModsUtil;
import edu.unc.lib.dl.ui.view.XSLViewResolver;

/**
 * Controller which retrieves data necessary for populating the full record page, retrieving supplemental information
 * according to the specifics of the object being retrieved.
 *
 * @author bbpennel
 */
@Controller
@RequestMapping("/record")
public class FullRecordController extends AbstractSolrSearchController {
    private static final Logger LOG = LoggerFactory.getLogger(FullRecordController.class);

    @Autowired
    private AccessControlService aclService;
    @Autowired
    private ChildrenCountService childrenCountService;
    @Autowired
    private NeighborQueryService neighborService;
    @Autowired
    private GetCollectionIdService collectionIdService;
    @Autowired
    private FindingAidUrlService findingAidUrlService;
    @Autowired
    private AccessCopiesService accessCopiesService;

    @Autowired(required = true)
    private XSLViewResolver xslViewResolver;
    @Autowired
    private RepositoryObjectLoader repositoryObjectLoader;

    @RequestMapping(value = "/{pid}", method = RequestMethod.GET)
    public String handleRequest(@PathVariable("pid") String pid, Model model, HttpServletRequest request) {
        return getFullRecord(pid, model, request);
    }

    @RequestMapping(method = RequestMethod.GET)
    public String handleOldRequest(@RequestParam("id") String id, Model model, HttpServletRequest request) {
        return getFullRecord(id, model, request);
    }

    @RequestMapping(value = "/{pid}/metadataView", method = RequestMethod.GET)
    @ResponseBody
    public String handleFullObjectRequest(@PathVariable("pid") String pid, Model model, HttpServletRequest request,
                                          HttpServletResponse response) {
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        return getFullObjectView(pid);
    }


    public String getFullObjectView(String pidString) {
        PID pid = PIDs.get(pidString);

        AccessGroupSet principals = getAgentPrincipals().getPrincipals();

        try {
            aclService.assertHasAccess("Insufficient permissions to access full record metadata for " + pidString,
                    pid, principals, Permission.viewMetadata);
        } catch (AccessRestrictionException e) {
            LOG.info("{}", e.getMessage());
            throw new InvalidRecordRequestException();
        }

        SimpleIdRequest idRequest = new SimpleIdRequest(pidString, principals);

        // Retrieve the objects description from Fedora
        String fullObjectView = null;
        try {
            ContentObject contentObj = (ContentObject) repositoryObjectLoader.getRepositoryObject(pid);

            BinaryObject modsObj = contentObj.getDescription();
            if (modsObj != null) {
                SAXBuilder builder = createSAXBuilder();
                try (InputStream modsStream = modsObj.getBinaryStream()) {
                    Document modsDoc = builder.build(modsStream);
                    fullObjectView = xslViewResolver.renderView("external.xslView.fullRecord.url",
                            ModsUtil.removeEmptyNodes(modsDoc));
                }
            }
        } catch (NotFoundException e) {
            throw new InvalidRecordRequestException(e);
        } catch (FedoraException e) {
            LOG.error("Failed to retrieve object {} from fedora", idRequest.getId(), e);
        } catch (RenderViewException e) {
            LOG.error("Failed to render full record view for {}", idRequest.getId(), e);
        } catch (JDOMException | IOException e) {
            LOG.error("Failed to parse MODS document for {}", idRequest.getId(), e);
        }

        if (fullObjectView == null) {
            return "No metadata is available for this item";
        }

        return fullObjectView;
    }

    public String getFullRecord(String pidString, Model model, HttpServletRequest request) {
        PID pid = PIDs.get(pidString);

        AccessGroupSet principals = getAgentPrincipals().getPrincipals();

        try {
            aclService.assertHasAccess("Insufficient permissions to access full record for " + pidString,
                    pid, principals, Permission.viewMetadata);
        } catch (AccessRestrictionException e) {
            LOG.info("{}", e.getMessage());
            throw new InvalidRecordRequestException();
        }

        // Retrieve the objects record from Solr
        SimpleIdRequest idRequest = new SimpleIdRequest(pidString, principals);
        BriefObjectMetadataBean briefObject = queryLayer.getObjectById(idRequest);
        if (briefObject == null) {
            throw new InvalidRecordRequestException();
        }

        // Get path information.
        model.addAttribute("briefObject", briefObject);

        Date embargoUntil = briefObject.getActiveEmbargo();
        if (embargoUntil != null) {
            model.addAttribute("embargoDate", embargoUntil);
        }

        // Get additional information depending on the type of object since the user has access
        String resourceType = briefObject.getResourceType();
        boolean retrieveChildrenCount = !resourceType.equals(searchSettings.resourceTypeFile);

        if (retrieveChildrenCount) {
            briefObject.getCountMap().put("child", childrenCountService.getChildrenCount(briefObject, principals));
        }

        if (resourceType.equals(searchSettings.resourceTypeFolder) ||
                resourceType.equals(searchSettings.resourceTypeFile) ||
                resourceType.equals(searchSettings.resourceTypeAggregate) ||
                resourceType.equals(searchSettings.resourceTypeCollection)) {
            String collectionId = collectionIdService.getCollectionId(briefObject);
            String faUrl = findingAidUrlService.getFindingAidUrl(collectionId);
            model.addAttribute("collectionId", collectionId);
            model.addAttribute("findingAidUrl", faUrl);
        }

        if (briefObject.getResourceType().equals(searchSettings.resourceTypeFile) ||
                briefObject.getResourceType().equals(searchSettings.resourceTypeAggregate)) {
            List<BriefObjectMetadataBean> neighbors = neighborService.getNeighboringItems(briefObject,
                    searchSettings.maxNeighborResults, principals);
            model.addAttribute("neighborList", neighbors);
        }

        if (briefObject.getResourceType().equals(searchSettings.resourceTypeAggregate)) {
            model.addAttribute("viewerNeeded", accessCopiesService.hasViewableFiles(briefObject, principals));
        }

        List<String> objectStatus = briefObject.getStatus();
        boolean isMarkedForDeletion = false;

        if (objectStatus != null) {
            isMarkedForDeletion = objectStatus.contains(MARKED_FOR_DELETION);
        }
        model.addAttribute("markedForDeletion", isMarkedForDeletion);

        model.addAttribute("pageSubtitle", briefObject.getTitle());
        return "fullRecord";
    }

    @ResponseStatus(value = HttpStatus.FORBIDDEN)
    @ExceptionHandler(InvalidRecordRequestException.class)
    public String handleInvalidRecordRequest(HttpServletRequest request) {
        request.setAttribute("pageSubtitle", "Invalid record");
        return "error/invalidRecord";
    }

    public void setXslViewResolver(XSLViewResolver xslViewResolver) {
        this.xslViewResolver = xslViewResolver;
    }
}
