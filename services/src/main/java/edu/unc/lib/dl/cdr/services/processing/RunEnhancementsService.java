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
package edu.unc.lib.dl.cdr.services.processing;

import static edu.unc.lib.dl.model.DatastreamType.ORIGINAL_FILE;
import static edu.unc.lib.dl.services.RunEnhancementsMessageHelpers.makeEnhancementOperationBody;

import java.util.List;

import org.jdom2.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.metrics.TimerFactory;
import edu.unc.lib.dl.model.DatastreamPids;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadata;
import edu.unc.lib.dl.search.solr.model.Datastream;
import edu.unc.lib.dl.search.solr.model.SearchRequest;
import edu.unc.lib.dl.search.solr.model.SearchResultResponse;
import edu.unc.lib.dl.search.solr.model.SearchState;
import edu.unc.lib.dl.search.solr.model.SimpleIdRequest;
import edu.unc.lib.dl.search.solr.util.SearchFieldKeys;
import edu.unc.lib.dl.services.MessageSender;
import edu.unc.lib.dl.ui.service.SolrQueryLayerService;
import edu.unc.lib.dl.util.ResourceType;
import io.dropwizard.metrics5.Timer;

/**
 * Queries solr and creates JMS message(s) to run enhancements on returned File objects
 *
 * @author lfarrell
 */
public class RunEnhancementsService {
    private static final Logger LOG = LoggerFactory.getLogger(RunEnhancementsService.class);
    private static final Timer timer = TimerFactory.createTimerForClass(RunEnhancementsService.class);

    private AccessControlService aclService;

    private MessageSender messageSender;

    private RepositoryObjectLoader repositoryObjectLoader;

    private SolrQueryLayerService queryLayer;

    /**
     * Service to take a list of pids searches for file objects which are in the list of pids
     * or children of those objects and run enhancements on.
     *
     * @param agent security principals of the agent making request.
     * @param objectPids List of pids to run enhancements on
     * @param force whether enhancements should run if derivatives are already present
     */
    public void run(AgentPrincipals agent, List<String> objectPids, boolean force) {
        try (Timer.Context context = timer.time()) {
            for (String objectPid : objectPids) {
                PID pid = PIDs.get(objectPid);

                aclService.assertHasAccess("User does not have permission to run enhancements",
                        pid, agent.getPrincipals(), Permission.runEnhancements);

                LOG.debug("sending solr update message for {} of type runEnhancements", pid);

                if (!(repositoryObjectLoader.getRepositoryObject(pid) instanceof FileObject)) {
                    SearchState searchState = new SearchState();
                    searchState.getFacets().put(SearchFieldKeys.RESOURCE_TYPE.name(), ResourceType.File.name());

                    SearchRequest searchRequest = new SearchRequest();
                    searchRequest.setAccessGroups(agent.getPrincipals());
                    searchRequest.setSearchState(searchState);
                    searchRequest.setRootPid(pid);
                    searchRequest.setApplyCutoffs(false);
                    SearchResultResponse resultResponse = queryLayer.performSearch(searchRequest);

                    for (BriefObjectMetadata metadata : resultResponse.getResultList()) {
                        createMessage(metadata, agent.getUsername(), force);
                    }
                } else {
                    SimpleIdRequest searchRequest = new SimpleIdRequest(objectPid, agent.getPrincipals());
                    BriefObjectMetadata metadata = queryLayer.getObjectById(searchRequest);
                    createMessage(metadata, agent.getUsername(), force);
                }
            }
        }
    }

    public void setAclService(AccessControlService aclService) {
        this.aclService = aclService;
    }

    private void createMessage(BriefObjectMetadata metadata, String username, Boolean force) {
        PID pid = metadata.getPid();
        Datastream originalDs = metadata.getDatastreamObject(ORIGINAL_FILE.getId());
        if (originalDs == null) {
            return;
        }

        PID filePid = DatastreamPids.getOriginalFilePid(pid);
        Document msg = makeEnhancementOperationBody(username,
                filePid, originalDs.getMimetype(), force);
        messageSender.sendMessage(msg);
    }

    public void setRepositoryObjectLoader(RepositoryObjectLoader repositoryObjectLoader) {
        this.repositoryObjectLoader = repositoryObjectLoader;
    }

    public void setQueryLayer(SolrQueryLayerService queryLayer) {
        this.queryLayer = queryLayer;
    }

    public void setMessageSender(MessageSender messageSender) {
        this.messageSender = messageSender;
    }
}
