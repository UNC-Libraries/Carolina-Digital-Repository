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
package edu.unc.lib.dl.ui.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.unc.lib.boxc.auth.api.UserRole;
import edu.unc.lib.boxc.auth.api.models.AccessGroupSet;
import edu.unc.lib.boxc.auth.api.services.GlobalPermissionEvaluator;
import edu.unc.lib.boxc.common.util.DateTimeUtil;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadata;
import edu.unc.lib.dl.search.solr.model.CutoffFacet;
import edu.unc.lib.dl.search.solr.model.HierarchicalBrowseResultResponse;
import edu.unc.lib.dl.search.solr.model.HierarchicalFacetNode;
import edu.unc.lib.dl.search.solr.model.SearchResultResponse;
import edu.unc.lib.dl.search.solr.util.SearchSettings;
import edu.unc.lib.dl.search.solr.util.SolrSettings;

/**
 *
 * @author bbpennel
 *
 */
public class SerializationUtil {
    private static final Logger log = LoggerFactory.getLogger(SerializationUtil.class);

    private static ObjectMapper jsonMapper = new ObjectMapper();
    static {
        jsonMapper.setSerializationInclusion(Include.NON_NULL);
    }

    private static SearchSettings searchSettings;
    private static SolrSettings solrSettings;
    private static GlobalPermissionEvaluator globalPermissionEvaluator;

    private SerializationUtil() {
    }

    public static String structureToJSON(HierarchicalBrowseResultResponse response, AccessGroupSet groups) {
        Map<String, Object> result = new HashMap<>();
        if (response.getRootNode() != null) {
            result.put("root", structureStep(response.getRootNode(), groups));
        }
        return objectToJSON(result);
    }

    private static Map<String, Object> structureStep(HierarchicalBrowseResultResponse.ResultNode node,
            AccessGroupSet groups) {
        Map<String, Object> entryMap = new HashMap<>();
        Map<String, Object> metadataMap = metadataToMap(node.getMetadata(), groups);
        entryMap.put("entry", metadataMap);
        if (node.getMetadata().getAncestorNames() != null
                && (node.getMetadata().getAncestorPath() == null || node.getMetadata().getAncestorPath().size() == 0)) {
            entryMap.put("isTopLevel", "true");
        }

        if (node.getChildren().size() > 0) {
            List<Object> childrenList = new ArrayList<>(node.getChildren().size());
            entryMap.put("children", childrenList);
            for (int i = 0; i < node.getChildren().size(); i++) {
                childrenList.add(structureStep(node.getChildren().get(i), groups));
            }
        }
        return entryMap;
    }

    public static String resultsToJSON(SearchResultResponse resultResponse, AccessGroupSet groups) {
        StringBuilder result = new StringBuilder();
        result.append('[');
        boolean firstEntry = true;
        for (BriefObjectMetadata metadata : resultResponse.getResultList()) {
            if (firstEntry) {
                firstEntry = false;
            } else {
                result.append(',');
            }
            result.append(metadataToJSON(metadata, groups));
        }
        result.append(']');
        return result.toString();
    }

    public static List<Map<String, Object>> resultsToList(SearchResultResponse resultResponse, AccessGroupSet groups) {
        List<Map<String, Object>> result = new ArrayList<>(resultResponse.getResultList().size());

        for (BriefObjectMetadata metadata : resultResponse.getResultList()) {
            result.add(metadataToMap(metadata, groups));
        }

        return result;
    }

    public static Map<String, Object> metadataToMap(BriefObjectMetadata metadata, AccessGroupSet groups) {
        Map<String, Object> result = new HashMap<>();
        String thumbnail_url = DatastreamUtil.getThumbnailUrl(metadata, null);

        if (!thumbnail_url.isEmpty()) {
            result.put("thumbnail_url", thumbnail_url);
        }

        if (metadata.getId() != null) {
            result.put("id", metadata.getId());
        }

        if (metadata.getLabel() != null) {
            result.put("label", metadata.getLabel());
        }

        if (metadata.getTitle() != null) {
            result.put("title", metadata.getTitle());
        }

        if (metadata.get_version_() != null) {
            result.put("_version_", metadata.get_version_());
        }

        if (metadata.getStatus() != null && metadata.getStatus().size() > 0) {
            result.put("status", metadata.getStatus());
        }

        if (metadata.getContentStatus() != null && metadata.getContentStatus().size() > 0) {
            result.put("contentStatus", metadata.getContentStatus());
        }

        if (metadata.getSubject() != null) {
            result.put("subject", metadata.getSubject());
        }

        if (metadata.getResourceType() != null) {
            result.put("type", metadata.getResourceType());
        }

        if (metadata.getContentModel() != null && metadata.getContentModel().size() > 0) {
            result.put("model", metadata.getContentModel());
        }

        if (metadata.getCreator() != null) {
            result.put("creator", metadata.getCreator());
        }

        if (metadata.getDatastream() != null) {
            result.put("datastream", metadata.getDatastream());
        }

        if (metadata.getIdentifier() != null) {
            result.put("identifier", metadata.getIdentifier());
        }

        if (metadata.getAncestorPathFacet() != null) {
            result.put("ancestorPath", cutoffFacetToMap(metadata.getAncestorPathFacet()));
        }

        if (metadata.getObjectPath() != null) {
            result.put("objectPath", metadata.getObjectPath().getEntries());
        }

        if (metadata.getAncestorNames() != null) {
            result.put("ancestorNames", metadata.getAncestorNames());
        }

        if (metadata.getIsPart() != null) {
            result.put("isPart", metadata.getIsPart());
        }

        if (metadata.getRollup() != null) {
            result.put("rollup", metadata.getRollup());
        }

        if (metadata.getCountMap() != null && metadata.getCountMap().size() > 0) {
            result.put("counts", metadata.getCountMap());
        }

        if (metadata.getDateAdded() != null) {
            String dateAdded = DateTimeUtil.formatDateToUTC(metadata.getDateAdded());
            result.put("added", dateAdded);
        }
        if (metadata.getDateUpdated() != null) {
            String dateUpdated = DateTimeUtil.formatDateToUTC(metadata.getDateUpdated());
            result.put("updated", dateUpdated);
        }

        if (metadata.getDateCreated() != null) {
            result.put("created", metadata.getDateCreated());
        }

        if (metadata.getTimestamp() != null) {
            result.put("timestamp", metadata.getTimestamp());
        }

        if (groups != null && metadata.getRoleGroup() != null) {
            result.put("permissions", getPermissionsByGroups(metadata, groups));
        }

        if (metadata.getDynamicFields() != null) {
            Iterator<Entry<String, Object>> fieldIt = metadata.getDynamicFields().entrySet().iterator();
            while (fieldIt.hasNext()) {
                Entry<String, Object> entry = fieldIt.next();
                // Translate the solr field back into the query parameter name
                String fieldKey = solrSettings.getFieldKey(entry.getKey());
                String paramName = searchSettings.getSearchFieldParam(fieldKey);
                if (paramName != null) {
                    result.put(paramName, entry.getValue());
                }
            }
        }

        return result;
    }

    private static Object cutoffFacetToMap(CutoffFacet facet) {
        List<Map<String, String>> result = new ArrayList<>(facet.getFacetNodes().size());
        for (HierarchicalFacetNode node : facet.getFacetNodes()) {
            Map<String, String> nodeEntry = new HashMap<>();
            nodeEntry.put("id", node.getSearchKey());
            nodeEntry.put("title", node.getDisplayValue());
            result.add(nodeEntry);
        }
        return result;
    }

    public static String metadataToJSON(BriefObjectMetadata metadata, AccessGroupSet groups) {
        try {
            return jsonMapper.writeValueAsString(metadataToMap(metadata, groups));
        } catch (JsonProcessingException e) {
            log.error("Unable to serialize object " + metadata.getId() + " to json", e);
        }
        return null;
    }

    public static String objectToJSON(Object object) {
        try {
            return jsonMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Unable to serialize object of type " + object.getClass().getName() + " to json", e);
        }
        return "";
    }

    public static void injectSettings(SearchSettings searchSettings,
            SolrSettings solrSettings, GlobalPermissionEvaluator globalPermissionEvaluator) {
        SerializationUtil.searchSettings = searchSettings;
        SerializationUtil.solrSettings = solrSettings;
        SerializationUtil.globalPermissionEvaluator = globalPermissionEvaluator;
    }

    /**
     * Returns the aggregated set of permissions granted to the agent with the provided principals for the given object.
     *
     * @param metadata object to which permissions are granted.
     * @param principals agent principals
     * @return set of permissions
     */
    private static Set<String> getPermissionsByGroups(BriefObjectMetadata metadata, AccessGroupSet principals) {
        Set<UserRole> globalRoles = globalPermissionEvaluator.getGlobalUserRoles(principals);

        Map<String, Collection<String>> groupRoleMap = metadata.getGroupRoleMap();

        Stream<UserRole> localRoleStream = principals.stream()
                .map(p -> groupRoleMap.get(p))
                .filter(r -> r != null)
                // Start streaming the list of roles for the principal
                .flatMap(princRoles -> princRoles.stream())
                .map(roleName -> UserRole.valueOf(roleName))
                .filter(role -> role != null);

        // Combine local roles with global, then collect as permissions
        return Stream.concat(localRoleStream, globalRoles.stream())
                .flatMap(role -> role.getPermissionNames().stream())
                .collect(Collectors.toSet());
    }
}