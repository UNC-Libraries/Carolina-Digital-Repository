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
package edu.unc.lib.dl.search.solr.service;

import static edu.unc.lib.dl.search.solr.util.SearchFieldKeys.RESOURCE_TYPE;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.FacetField.Count;
import org.apache.solr.client.solrj.response.Group;
import org.apache.solr.client.solrj.response.GroupCommand;
import org.apache.solr.client.solrj.response.GroupResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.GroupParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.acl.util.UserRole;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadata;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadataBean;
import edu.unc.lib.dl.search.solr.model.CutoffFacet;
import edu.unc.lib.dl.search.solr.model.FacetFieldFactory;
import edu.unc.lib.dl.search.solr.model.GroupedMetadataBean;
import edu.unc.lib.dl.search.solr.model.IdListRequest;
import edu.unc.lib.dl.search.solr.model.SearchFacet;
import edu.unc.lib.dl.search.solr.model.SearchRequest;
import edu.unc.lib.dl.search.solr.model.SearchResultResponse;
import edu.unc.lib.dl.search.solr.model.SearchState;
import edu.unc.lib.dl.search.solr.model.SimpleIdRequest;
import edu.unc.lib.dl.search.solr.util.DateFormatUtil;
import edu.unc.lib.dl.search.solr.util.FacetFieldUtil;
import edu.unc.lib.dl.search.solr.util.SearchFieldKeys;
import edu.unc.lib.dl.search.solr.util.SolrSettings;

/**
 * Performs CDR specific Solr search tasks, using solrj for connecting to the solr instance.
 *
 * @author bbpennel
 */
public class SolrSearchService extends AbstractQueryService {
    private static final Logger LOG = LoggerFactory.getLogger(SolrSearchService.class);

    @Autowired
    protected FacetFieldFactory facetFieldFactory;
    protected FacetFieldUtil facetFieldUtil;

    protected static final String DEFAULT_RELEVANCY_BOOSTS =
            "titleIndex^50 subjectIndex^10 contributorIndex^30 text^1 keywordIndex^2";
    protected static final String DEFAULT_SEARCHABLE_FIELDS =
            "text,titleIndex,contributorIndex,subjectIndex,keywordIndex";

    public SolrSearchService() {
    }

    /**
     * Retrieves the Solr tuple representing the object identified by id.
     *
     * @param id
     *           identifier (uuid) of the object to retrieve.
     * @param userAccessGroups
     * @return
     */
    public BriefObjectMetadataBean getObjectById(SimpleIdRequest idRequest) {
        LOG.debug("In getObjectbyID");

        QueryResponse queryResponse = null;
        SolrQuery solrQuery = new SolrQuery();
        StringBuilder query = new StringBuilder();
        query.append(solrSettings.getFieldName(SearchFieldKeys.ID.name())).append(':')
                .append(SolrSettings.sanitize(idRequest.getId()));
        try {
            // Add access restrictions to query
            addAccessRestrictions(solrQuery, idRequest.getAccessGroups());
            /*
             * if (idRequest.getAccessTypeFilter() != null) { addAccessRestrictions(query, idRequest.getAccessGroups(),
             * idRequest.getAccessTypeFilter()); }
             */
        } catch (AccessRestrictionException e) {
            // If the user doesn't have any access groups, they don't have access to anything, return null.
            LOG.error("Error while attempting to add access restrictions to object " + idRequest.getId(), e);
            return null;
        }

        addResultFields(idRequest.getResultFields(), solrQuery);

        solrQuery.setQuery(query.toString());
        solrQuery.setRows(1);

        LOG.debug("getObjectById query: " + solrQuery.toString());
        try {
            queryResponse = executeQuery(solrQuery);
        } catch (SolrServerException e) {
            LOG.error("Error retrieving Solr object request", e);
            return null;
        }

        List<BriefObjectMetadataBean> results = queryResponse.getBeans(BriefObjectMetadataBean.class);
        if (results != null && results.size() > 0) {
            return results.get(0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<BriefObjectMetadata> getObjectsById(IdListRequest listRequest) {
        QueryResponse queryResponse = null;
        SolrQuery solrQuery = new SolrQuery();
        StringBuilder query = new StringBuilder("*:* ");

        try {
            // Add access restrictions to query
            addAccessRestrictions(solrQuery, listRequest.getAccessGroups());
        } catch (AccessRestrictionException e) {
            // If the user doesn't have any access groups, they don't have access to anything, return null.
            LOG.error("Error while attempting to add access restrictions to object " + listRequest.getId(), e);
            return null;
        }

        query.append(" AND (");

        boolean first = true;
        for (String id : listRequest.getIds()) {
            if (first) {
                first = false;
            } else {
                query.append(" OR ");
            }
            query.append(solrSettings.getFieldName(SearchFieldKeys.ID.name())).append(':')
                    .append(SolrSettings.sanitize(id));
        }

        query.append(")");

        addResultFields(listRequest.getResultFields(), solrQuery);

        solrQuery.setQuery(query.toString());
        solrQuery.setRows(listRequest.getIds().size());

        LOG.debug("getObjectsById query: " + solrQuery.toString());
        try {
            queryResponse = executeQuery(solrQuery);
        } catch (SolrServerException e) {
            LOG.error("Error retrieving Solr object request", e);
            return null;
        }

        List<?> results = queryResponse.getBeans(BriefObjectMetadataBean.class);
        return (List<BriefObjectMetadata>) results;
    }

    /**
     * Retrieves search results as a SearchResultResponse. Will not return the solr query use for the request.
     *
     * @param searchRequest
     * @return
     */
    public SearchResultResponse getSearchResults(SearchRequest searchRequest) {
        return getSearchResults(searchRequest, false);
    }

    /**
     * Generates a solr query from the search state specified in searchRequest
     * and executes it, returning a result set of BriefObjectMetadataBeans and,
     * optionally, the SolrQuery generated for this request.
     *
     * @param searchRequest
     * @param returnQuery
     * @return the result set of BriefObjectMetadataBeans, or null if an error occurred
     */
    public SearchResultResponse getSearchResults(SearchRequest searchRequest, boolean returnQuery) {
        LOG.debug("In getSearchResults: " + searchRequest.getSearchState());

        SolrQuery solrQuery = this.generateSearch(searchRequest);

        if (searchRequest.isRetrieveFacets()) {
            facetFieldUtil.addFacetPrefixes(searchRequest.getSearchState(), solrQuery);
        }

        LOG.debug("getSearchResults query: " + solrQuery);
        try {
            SearchResultResponse resultResponse = executeSearch(solrQuery, searchRequest.getSearchState(),
                    searchRequest.isRetrieveFacets(), returnQuery);
            // Add in the correct rollup representatives when they are missing, if we are rolling up on the rollup id
            if (searchRequest.getSearchState().getRollup() != null && searchRequest.getSearchState().getRollup()
                    && searchRequest.getSearchState().getRollupField() == null) {
                for (BriefObjectMetadata item : resultResponse.getResultList()) {
                    if (item.getId() != null && item.getRollup() != null && !item.getId().equals(item.getRollup())) {
                        BriefObjectMetadataBean representative = this.getObjectById(new SimpleIdRequest(
                                item.getRollup(), searchRequest.getAccessGroups()));
                        if (representative != null) {
                            GroupedMetadataBean grouped = (GroupedMetadataBean) item;
                            grouped.getItems().add(representative);
                            grouped.setRepresentative(representative);
                        }
                    }
                }
            }

            return resultResponse;
        } catch (SolrServerException e) {
            LOG.error("Error retrieving Solr search result request", e);
        }
        return null;
    }

    /**
     * Adds access restrictions to the provided query string buffer. If there
     * are no access groups in the provided group set, then an
     * AccessRestrictionException is thrown as it is invalid for a user to have
     * no permissions. If the user is an admin, then do not restrict access
     *
     * @param query
     *            SolrQuery containing the query to add access groups to.
     * @param accessGroups
     *            set of access groups to append to the query
     * @return The original query restricted to results available to the
     *         provided access groups
     * @throws AccessRestrictionException
     *             thrown if no groups are provided.
     */
    public SolrQuery addAccessRestrictions(SolrQuery query, AccessGroupSet accessGroups)
            throws AccessRestrictionException {
        restrictionUtil.add(query, accessGroups);
        return query;
    }

    /**
     * Gets the ancestor path facet for the provided pid, given the access groups provided.
     *
     * @param pid
     * @param accessGroups
     * @return
     */
    public CutoffFacet getAncestorPath(String pid, AccessGroupSet accessGroups) {
        List<String> resultFields = new ArrayList<>();
        resultFields.add(SearchFieldKeys.ANCESTOR_PATH.name());

        SimpleIdRequest idRequest = new SimpleIdRequest(pid, resultFields, accessGroups);

        BriefObjectMetadataBean rootNode = null;
        try {
            rootNode = getObjectById(idRequest);
        } catch (Exception e) {
            LOG.error("Error while retrieving Solr entry for ", e);
        }
        if (rootNode == null) {
            return null;
        }
        return rootNode.getAncestorPathFacet();
    }

    public BriefObjectMetadata addSelectedContainer(PID containerPid, SearchState searchState,
            boolean applyCutoffs, AccessGroupSet principals) {
        BriefObjectMetadata selectedContainer = getObjectById(new SimpleIdRequest(containerPid, principals));
        if (selectedContainer == null) {
            return null;
        }
        CutoffFacet selectedPath = selectedContainer.getPath();
        if (applyCutoffs) {
            selectedPath.setCutoff(selectedPath.getHighestTier() + 1);
        }
        // Override any existing ancestor path facets if a selected container is present
        searchState.setFacet(SearchFieldKeys.ANCESTOR_PATH, selectedPath);
        return selectedContainer;
    }

    /**
     * Constructs a SolrQuery object from the search state specified within a
     * SearchRequest object. The request may optionally request to retrieve
     * facet results in addition to search results.
     *
     * @param searchRequest
     * @param isRetrieveFacetsRequest
     * @return
     */
    protected SolrQuery generateSearch(SearchRequest searchRequest) {
        SearchState searchState = searchRequest.getSearchState();
        SolrQuery solrQuery = new SolrQuery();
        StringBuilder termQuery = new StringBuilder();

        // Generate search term query string
        addSearchFields(searchState, termQuery);

        // Add role group limits based on the request's groups
        addPermissionLimits(searchState, searchRequest.getAccessGroups(), solrQuery);

        // Add range Fields to the query
        addRangeFields(searchState, solrQuery);

        // No query terms given, make it an everything query
        if (termQuery.length() == 0) {
            termQuery.append("*:* ");
        }

        // Add access restrictions to query
        try {
            addAccessRestrictions(solrQuery, searchRequest.getAccessGroups());
        } catch (AccessRestrictionException e) {
            // If the user doesn't have any access groups, they don't have access to anything, return null.
            LOG.debug("User had no access groups", e);
            return null;
        }

        // Add query
        solrQuery.setQuery(termQuery.toString());

        solrQuery.set("defType", "edismax");
        solrQuery.set("qf", DEFAULT_RELEVANCY_BOOSTS);
        solrQuery.set("uf", DEFAULT_SEARCHABLE_FIELDS);

        addResultFields(searchState.getResultFields(), solrQuery);

        if (searchState.getRollup() != null && searchState.getRollup()) {
            solrQuery.set(GroupParams.GROUP, true);
            if (searchState.getRollupField() == null) {
                solrQuery.set(GroupParams.GROUP_FIELD, solrSettings.getFieldName(SearchFieldKeys.ROLLUP_ID.name()));
            } else {
                solrQuery.set(GroupParams.GROUP_FIELD, solrSettings.getFieldName(searchState.getRollupField()));
            }

            solrQuery.set(GroupParams.GROUP_TOTAL_COUNT, true);
            if (searchState.getFacetsToRetrieve() != null && searchState.getFacetsToRetrieve().size() > 0) {
                solrQuery.set(GroupParams.GROUP_FACET, true);
            }
        }

        if (!searchState.getIncludeParts()) {
            solrQuery.addFilterQuery(
                    solrSettings.getFieldName(SearchFieldKeys.IS_PART.name()) + ":false");
        }

        // Add sort parameters
        addSort(solrQuery, searchState.getSortType(), searchState.getSortNormalOrder());

        // Set requested resource types
        String resourceTypeFilter = makeFilter(RESOURCE_TYPE, searchState.getResourceTypes());
        if (resourceTypeFilter != null) {
            solrQuery.addFilterQuery(resourceTypeFilter);
        }

        // Turn on faceting
        if (searchRequest.isRetrieveFacets()) {
            solrQuery.setFacet(true);
            solrQuery.setFacetMinCount(1);
            if (searchState.getBaseFacetLimit() != null) {
                solrQuery.setFacetLimit(searchState.getBaseFacetLimit());
            }

            if (searchState.getFacetsToRetrieve() != null) {
                // Add facet fields
                for (String facetName : searchState.getFacetsToRetrieve()) {
                    String facetField = solrSettings.getFieldName(facetName);
                    if (facetField != null) {
                        solrQuery.addFacetField(solrSettings.getFieldName(facetName));
                    }
                }
            }
        }

        // Override the base facet limit if overrides are given.
        if (searchState.getFacetLimits() != null) {
            for (Entry<String, Integer> facetLimit : searchState.getFacetLimits().entrySet()) {
                solrQuery.add("f." + solrSettings.getFieldName(facetLimit.getKey()) + ".facet.limit",
                        facetLimit.getValue().toString());
            }
        }

        // Add facet limits
        Map<String, List<SearchFacet>> facets = searchState.getFacets();
        if (facets != null) {
            Iterator<Entry<String, List<SearchFacet>>> facetIt = facets.entrySet().iterator();
            while (facetIt.hasNext()) {
                Entry<String, List<SearchFacet>> facetEntry = facetIt.next();
                LOG.debug("Adding facet {} as a  {}", facetEntry.getKey(),
                        facetEntry.getValue().getClass().getName());
                facetFieldUtil.addToSolrQuery(facetEntry.getValue(), solrQuery);
            }
        }

        // Scope hierarchical facet results to the highest tier selected within the facet tree
        if (searchRequest.isRetrieveFacets() && searchRequest.isApplyCutoffs()
                && searchState.getFacetsToRetrieve() != null) {
            Set<String> facetsQueried = searchState.getFacets().keySet();
            // Apply closing cutoff to all cutoff facets that are being retrieved but not being queried for
            for (String fieldKey : searchState.getFacetsToRetrieve()) {
                if (!facetsQueried.contains(fieldKey)) {
                    facetFieldUtil.addDefaultFacetPivot(fieldKey, solrQuery);
                }
            }

            // Add individual facet field sorts if they are present.
            if (searchState.getFacetSorts() != null) {
                for (Entry<String, String> facetSort : searchState.getFacetSorts().entrySet()) {
                    solrQuery.add("f." + solrSettings.getFieldName(facetSort.getKey()) + ".facet.sort",
                                    facetSort.getValue());
                }
            }
        }

        // Set Navigation options
        if (searchState.getStartRow() != null) {
            solrQuery.setStart(searchState.getStartRow());
        }
        if (searchState.getRowsPerPage() != null) {
            solrQuery.setRows(searchState.getRowsPerPage());
        }

        return solrQuery;
    }

    /**
     * Add search fields from a search state to the given termQuery
     *
     * @param searchState
     * @param termQuery
     */
    private void addSearchFields(SearchState searchState, StringBuilder termQuery) {
        // Generate search term query string
        String searchType = null;
        Map<String, String> searchFields = searchState.getSearchFields();
        if (searchFields != null) {
            Iterator<String> searchTypeIt = searchFields.keySet().iterator();
            while (searchTypeIt.hasNext()) {
                searchType = searchTypeIt.next();
                String fieldValue = searchState.getSearchFields().get(searchType);
                // Special "field exists" keyword
                if ("*".equals(fieldValue)) {
                    if (termQuery.length() > 0) {
                        termQuery.append(" AND ");
                    }
                    termQuery.append(solrSettings.getFieldName(searchType)).append(":*");
                    continue;
                }
                List<String> searchFragments = SolrSettings.getSearchTermFragments(fieldValue);
                if (searchFragments != null && searchFragments.size() > 0) {
                    if (termQuery.length() > 0) {
                        termQuery.append(" AND ");
                    }
                    LOG.debug("{} : {}", searchType, searchFragments);
                    // Search all fields when text field specified
                    if ("text".equalsIgnoreCase(searchType)) {
                        termQuery.append(solrSettings.getFieldName(searchType)).append(':');
                    }
                    termQuery.append('(');
                    boolean firstTerm = true;
                    for (String searchFragment : searchFragments) {
                        if (firstTerm) {
                            firstTerm = false;
                        } else {
                            termQuery.append(' ').append(searchState.getSearchTermOperator()).append(' ');
                        }
                        termQuery.append(searchFragment);
                    }
                    termQuery.append(')');
                }
            }
        }
    }

    /**
     * Limit the given query to only return results which have all of the
     * specified permissions for the given set of groups
     *
     * @param searchState
     * @param groups
     * @param termQuery
     */
    private void addPermissionLimits(SearchState searchState, AccessGroupSet groups, SolrQuery query) {

        Collection<Permission> permissions = searchState.getPermissionLimits();
        if (permissions != null && permissions.size() > 0) {
            // Determine the set of roles that match all of the permissions needed
            Set<UserRole> roles = UserRole.getUserRoles(permissions);
            if (roles.size() == 0) {
                return;
            }

            boolean first = true;
            StringBuilder filter = new StringBuilder(solrSettings.getFieldName(SearchFieldKeys.ROLE_GROUP.name()));
            filter.append(':').append('(');
            for (String group : groups) {
                String saneGroup = SolrSettings.sanitize(group);

                for (UserRole role : roles) {
                    if (first) {
                        first = false;
                    } else {
                        filter.append(" OR ");
                    }

                    filter.append(role.getPredicate()).append('|').append(saneGroup);
                }
            }

            filter.append(')');
            query.addFilterQuery(filter.toString());
        }
    }

    private void addRangeFields(SearchState searchState, SolrQuery query) {
        Map<String, SearchState.RangePair> rangeFields = searchState.getRangeFields();
        if (rangeFields != null) {
            Iterator<Map.Entry<String, SearchState.RangePair>> rangeTermIt = rangeFields.entrySet().iterator();
            while (rangeTermIt.hasNext()) {
                Map.Entry<String, SearchState.RangePair> rangeTerm = rangeTermIt.next();
                if (rangeTerm == null) {
                    continue;
                }
                String key = rangeTerm.getKey();
                String left = getRangeValue(key, rangeTerm.getValue().getLeftHand());
                String right = getRangeValue(key, rangeTerm.getValue().getRightHand());
                if (left.equals("*") && right.equals("*")) {
                    continue;
                }

                query.addFilterQuery(String.format("%s:[%s TO %s]",
                        solrSettings.getFieldName(key), left, right));
            }
        }
    }

    private String getRangeValue(String key, String value) {
        if (StringUtils.isBlank(value)) {
            return "*";
        }
        if (searchSettings.dateSearchableFields.contains(key)) {
            try {
                return DateFormatUtil.getFormattedDate(value, true, true);
            } catch (NumberFormatException e) {
                return "*";
            }
        } else {
            return SolrSettings.sanitize(value);
        }
    }

    /**
     * Executes a SolrQuery based off of a search state and stores the results as BriefObjectMetadataBeans.
     *
     * @param query
     *           the solr query to be executed
     * @param searchState
     *           the search state used to generate this SolrQuery
     * @param isRetrieveFacetsRequest
     *           indicates if facet results hould be returned
     * @param returnQuery
     *           indicates whether to return the solr query object as part of the response.
     * @return
     * @throws SolrServerException
     */
    @SuppressWarnings("unchecked")
    protected SearchResultResponse executeSearch(SolrQuery query, SearchState searchState,
            boolean isRetrieveFacetsRequest, boolean returnQuery) throws SolrServerException {
        QueryResponse queryResponse = executeQuery(query);

        GroupResponse groupResponse = queryResponse.getGroupResponse();
        SearchResultResponse response = new SearchResultResponse();
        if (groupResponse != null) {
            List<BriefObjectMetadata> groupResults = new ArrayList<>();
            for (GroupCommand groupCmd : groupResponse.getValues()) {
                // response.setResultCount(groupCmd.getMatches());
                response.setResultCount(groupCmd.getNGroups());
                for (Group group : groupCmd.getValues()) {
                    List<BriefObjectMetadataBean> beans = solrClient.getBinder()
                            .getBeans(BriefObjectMetadataBean.class, group.getResult());

                    GroupedMetadataBean grouped = new GroupedMetadataBean(group.getGroupValue(),
                            beans, group.getResult().getNumFound());
                    groupResults.add(grouped);
                }
            }
            response.setResultList(groupResults);

        } else {
            List<?> results = queryResponse.getBeans(BriefObjectMetadataBean.class);
            response.setResultList((List<BriefObjectMetadata>) results);
            // Store the number of results
            response.setResultCount(queryResponse.getResults().getNumFound());
        }

        if (isRetrieveFacetsRequest) {
            // Store facet results
            response.setFacetFields(facetFieldFactory.createFacetFieldList(queryResponse.getFacetFields()));
            // Add empty entries for any empty facets, then sort the list
            if (response.getFacetFields() != null) {
                if (searchState.getFacetsToRetrieve() != null
                        && searchState.getFacetsToRetrieve().size() != response.getFacetFields().size()) {
                    facetFieldFactory.addMissingFacetFieldObjects(response.getFacetFields(),
                            searchState.getFacetsToRetrieve());
                }
            }
        } else {
            response.setFacetFields(null);
        }

        // Set search state that generated this result
        response.setSearchState(searchState);

        // Add the query to the result if it was requested
        if (returnQuery) {
            response.setGeneratedQuery(query);
        }
        return response;
    }

    public Map<String, Object> getFields(String pid, List<String> fields) throws SolrServerException {
        QueryResponse queryResponse = null;
        SolrQuery solrQuery = new SolrQuery();
        StringBuilder query = new StringBuilder();

        query.append("id:").append(SolrSettings.sanitize(pid));
        solrQuery.setQuery(query.toString());
        for (String field : fields) {
            solrQuery.addField(field);
        }

        queryResponse = executeQuery(solrQuery);
        if (queryResponse.getResults().getNumFound() > 0) {
            return queryResponse.getResults().get(0).getFieldValueMap();
        }
        return null;
    }

    /**
     * Returns a combined set of distinct field values for one or more fields, limited by the set of access groups
     * provided
     *
     * @param fields
     *           Solr field names to retrieve distinct values for
     * @param maxValuesPerField
     *           Max number of distinct values to retrieve for each field
     * @param accessGroups
     * @return
     * @throws AccessRestrictionException
     * @throws SolrServerException
     */
    public java.util.Collection<String> getDistinctFieldValues(String[] fields, int maxValuesPerField,
            AccessGroupSet accessGroups) throws AccessRestrictionException, SolrServerException {
        SolrQuery solrQuery = new SolrQuery();
        StringBuilder query = new StringBuilder("*:*");
        addAccessRestrictions(solrQuery, accessGroups);
        solrQuery.setQuery(query.toString());
        solrQuery.setFacet(true);
        for (String facetField : fields) {
            solrQuery.addFacetField(facetField);
        }
        solrQuery.setFacetLimit(maxValuesPerField);
        solrQuery.setFacetSort("index");

        QueryResponse queryResponse = executeQuery(solrQuery);
        // Determine initial capacity for the result list
        int numberValues = 0;
        for (FacetField facet : queryResponse.getFacetFields()) {
            numberValues += facet.getValueCount();
        }

        java.util.Collection<String> fieldValues = new java.util.HashSet<>(numberValues);
        for (FacetField facet : queryResponse.getFacetFields()) {
            for (Count count : facet.getValues()) {
                fieldValues.add(count.getName());
            }
        }

        return fieldValues;
    }

    public void setFacetFieldFactory(FacetFieldFactory facetFieldFactory) {
        this.facetFieldFactory = facetFieldFactory;
    }

    public void setFacetFieldUtil(FacetFieldUtil facetFieldUtil) {
        this.facetFieldUtil = facetFieldUtil;
    }
}
