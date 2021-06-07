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
package edu.unc.lib.dl.ui.service;

import static edu.unc.lib.dl.ui.util.SearchConstants.MAX_COLLECTIONS_TO_RETRIEVE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.FacetField.Count;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.GroupsThreadStore;
import edu.unc.lib.dl.acl.util.PrincipalClassifier;
import edu.unc.lib.dl.acl.util.UserRole;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadata;
import edu.unc.lib.dl.search.solr.model.CaseInsensitiveFacet;
import edu.unc.lib.dl.search.solr.model.FacetFieldObject;
import edu.unc.lib.dl.search.solr.model.SearchRequest;
import edu.unc.lib.dl.search.solr.model.SearchResultResponse;
import edu.unc.lib.dl.search.solr.model.SearchState;
import edu.unc.lib.dl.search.solr.service.ObjectPathFactory;
import edu.unc.lib.dl.search.solr.service.SearchStateFactory;
import edu.unc.lib.dl.search.solr.service.SolrSearchService;
import edu.unc.lib.dl.search.solr.util.SearchFieldKeys;
import edu.unc.lib.dl.search.solr.util.SolrSettings;
import edu.unc.lib.dl.util.ResourceType;

/**
 * Solr query construction layer. Constructs search states specific to common tasks before passing them on to lower
 * level classes to retrieve the results.
 *
 * @author bbpennel
 */
public class SolrQueryLayerService extends SolrSearchService {
    private static final Logger LOG = LoggerFactory.getLogger(SolrQueryLayerService.class);
    protected SearchStateFactory searchStateFactory;
    protected ObjectPathFactory pathFactory;

    /**
     * Returns a list of collections
     *
     * @param accessGroups
     * @return
     */
    public SearchResultResponse getCollectionList(AccessGroupSet accessGroups) {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.setAccessGroups(accessGroups);

        SearchState searchState = searchStateFactory.createSearchState();
        searchState.setResourceTypes(searchSettings.defaultCollectionResourceTypes);
        searchState.setRowsPerPage(MAX_COLLECTIONS_TO_RETRIEVE);
        searchState.setFacetsToRetrieve(null);
        List<String> resultFields = new ArrayList<>();
        resultFields.add(SearchFieldKeys.ANCESTOR_PATH.name());
        resultFields.add(SearchFieldKeys.TITLE.name());
        resultFields.add(SearchFieldKeys.ID.name());
        searchState.setResultFields(resultFields);

        searchRequest.setSearchState(searchState);
        return getSearchResults(searchRequest);
    }

    public SearchResultResponse getDepartmentList(AccessGroupSet accessGroups, String pid) {
        SearchState searchState;
        Boolean has_pid = (pid != null) ? true : false;

        searchState = searchStateFactory.createFacetSearchState(SearchFieldKeys.DEPARTMENT.name(), "index",
                Integer.MAX_VALUE);

        SearchRequest searchRequest = new SearchRequest(searchState, accessGroups, true);
        searchRequest.setRootPid(pid);
        BriefObjectMetadata selectedContainer = null;

        if (has_pid) {
            selectedContainer = addSelectedContainer(searchRequest.getRootPid(), searchState,
                    false, accessGroups);
        }

        SearchResultResponse results = getSearchResults(searchRequest);

        if (has_pid) {
            results.setSelectedContainer(selectedContainer);
        }

        if (results.getFacetFields() != null && results.getFacetFields().size() > 0) {
            FacetFieldObject deptField = results.getFacetFields().get(0);
            if (deptField != null) {
                CaseInsensitiveFacet.deduplicateCaseInsensitiveValues(deptField);
            }
        }
        return results;
    }

    /**
     * Determines if the user has adminRole permissions on any items
     *
     * @param accessGroups
     * @return
     */
    public boolean hasAdminViewPermission(AccessGroupSet accessGroups) {
        StringBuilder query = new StringBuilder();
        String joinedGroups = accessGroups.stream()
            .filter(p -> !PrincipalClassifier.isPatronPrincipal(p))
            .map(p -> p.replaceAll("\\:", "\\\\:"))
            .collect(Collectors.joining(" OR "));
        query.append("adminGroup:(").append(joinedGroups).append(')');

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery(query.toString());
        // Only take into account permissions provided from collections and units
        solrQuery.addFacetQuery(solrSettings.getFieldName(SearchFieldKeys.RESOURCE_TYPE.name())
                + ":(" + ResourceType.Collection.name() + " " + ResourceType.AdminUnit.name() + ")");

        solrQuery.setRows(0);

        try {
            QueryResponse queryResponse = this.executeQuery(solrQuery);
            return queryResponse.getResults().getNumFound() > 0;
        } catch (SolrServerException e) {
            LOG.error("Error retrieving Solr object request: " + e);
        }
        return false;
    }

    public boolean hasRole(AccessGroupSet accessGroups, UserRole userRole) {
        StringBuilder query = new StringBuilder();
        String joinedGroups = accessGroups.joinAccessGroups(" OR ", userRole.getPredicate() + "|", true);
        query.append("roleGroup:(").append(joinedGroups).append(')');

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery(query.toString());
        solrQuery.setRows(0);

        try {
            QueryResponse queryResponse = this.executeQuery(solrQuery);
            return queryResponse.getResults().getNumFound() > 0;
        } catch (SolrServerException e) {
            LOG.error("Error retrieving Solr object request: " + e);
        }
        return false;
    }

    public SearchResultResponse performSearch(SearchRequest searchRequest) {
        SearchState searchState = (SearchState) searchRequest.getSearchState().clone();
        SearchState originalState = searchRequest.getSearchState();
        searchRequest.setSearchState(searchState);
        searchState.setFacetsToRetrieve(null);

        // Adjust the number of results to retrieve
        if (searchState.getRowsPerPage() == null || searchState.getRowsPerPage() < 0) {
            searchState.setRowsPerPage(searchSettings.defaultPerPage);
        } else if (!searchState.getIgnoreMaxRows() && searchState.getRowsPerPage() > searchSettings.getMaxPerPage()) {
            searchState.setRowsPerPage(searchSettings.getMaxPerPage());
        }

        Boolean rollup = searchState.getRollup();

        BriefObjectMetadata selectedContainer = null;
        // Get the record for the currently selected container if one is selected.
        if (searchRequest.getRootPid() != null) {
            selectedContainer = addSelectedContainer(searchRequest.getRootPid(), searchState,
                    searchRequest.isApplyCutoffs(), searchRequest.getAccessGroups());
        } else if (rollup == null) {
            LOG.debug("No container and no rollup, defaulting rollup to true");
            searchState.setRollup(true);
        }

        // Retrieve search results
        SearchResultResponse resultResponse = getSearchResults(searchRequest);

        resultResponse.setSelectedContainer(selectedContainer);

        searchRequest.setSearchState(originalState);
        return resultResponse;
    }

    public void populateBreadcrumbs(SearchRequest searchRequest, SearchResultResponse resultResponse) {
        SearchState searchState = searchRequest.getSearchState();
        if (searchState.getFacets().containsKey(SearchFieldKeys.CONTENT_TYPE.name())) {
            if (resultResponse.getResultCount() == 0 || searchState.getResultFields() == null
                    || !searchState.getResultFields().contains(SearchFieldKeys.CONTENT_TYPE.name())) {
                SearchState contentTypeSearchState = new SearchState();
                contentTypeSearchState.setRowsPerPage(1);
                contentTypeSearchState.setFacet(SearchFieldKeys.CONTENT_TYPE,
                        searchState.getFacets().get(SearchFieldKeys.CONTENT_TYPE.name()));
                contentTypeSearchState.setResultFields(Arrays.asList(SearchFieldKeys.CONTENT_TYPE.name()));

                SearchRequest contentTypeRequest = new SearchRequest(contentTypeSearchState, GroupsThreadStore
                                .getAgentPrincipals().getPrincipals());
                SearchResultResponse contentTypeResponse = getSearchResults(contentTypeRequest);
                if (contentTypeResponse.getResultCount() > 0) {
                    resultResponse.extractCrumbDisplayValueFromRepresentative(
                            contentTypeResponse.getResultList().get(0));
                }
            } else {
                resultResponse.extractCrumbDisplayValueFromRepresentative(resultResponse.getResultList().get(0));
            }
        }
    }

    public void setSearchStateFactory(SearchStateFactory searchStateFactory) {
        this.searchStateFactory = searchStateFactory;
    }

    public SearchResultResponse getRelationSet(SearchRequest searchRequest, String relationName) {

        SolrQuery query = generateSearch(searchRequest);

        query.setQuery(query.getQuery() + " AND " + solrSettings.getFieldName(SearchFieldKeys.RELATIONS.name()) + ":"
                + SolrSettings.sanitize(relationName) + "|*");
        query.setRows(1000);

        try {
            return executeSearch(query, searchRequest.getSearchState(), false, false);
        } catch (SolrServerException e) {
            LOG.error("Error retrieving Solr object request: " + e);
        }

        return null;
    }

    /**
     * Get the number of objects present in the collection for various formats
     *
     * @return a map from format name to count
     */
    public Map<String, Long> getFormatCounts(AccessGroupSet accessGroups) {
        Map<String, Long> counts = new HashMap<>();

        try {
            SolrQuery query = new SolrQuery();

            query.setQuery("*:*");
            addAccessRestrictions(query, accessGroups);
            query.setRows(0);
            query.addFacetField("contentType");
            query.setFacetLimit(-1);

            QueryResponse response = this.executeQuery(query);
            FacetField facetField = response.getFacetField("contentType");

            for (Count count : facetField.getValues()) {

                if (count.getName().startsWith("^text")) {
                    counts.put("text", count.getCount());
                } else if (count.getName().startsWith("^image")) {
                    counts.put("image", count.getCount());
                } else if (count.getName().startsWith("^dataset")) {
                    counts.put("dataset", count.getCount());
                } else if (count.getName().startsWith("^audio")) {
                    counts.put("audio", count.getCount());
                } else if (count.getName().startsWith("^video")) {
                    counts.put("video", count.getCount());
                }

            }
        } catch (SolrServerException | AccessRestrictionException e) {
            LOG.error("Error retrieving format counts", e);
        }

        return counts;
}

    public static String getWriteRoleFilter(AccessGroupSet groups) {
        StringBuilder roleString = new StringBuilder();

        roleString.append('(');

        for (String group : groups) {
            String saneGroup = SolrSettings.sanitize(group);
            roleString.append(UserRole.canManage.getPredicate()).append('|').append(saneGroup).append(' ');
            roleString.append(UserRole.unitOwner.getPredicate()).append('|').append(saneGroup).append(' ');
        }

        roleString.append(')');

        return roleString.toString();
    }

    public void setPathFactory(ObjectPathFactory pathFactory) {
        this.pathFactory = pathFactory;
    }

}
