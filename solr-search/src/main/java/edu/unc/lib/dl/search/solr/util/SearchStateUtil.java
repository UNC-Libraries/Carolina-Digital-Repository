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
package edu.unc.lib.dl.search.solr.util;

import java.net.URLDecoder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import edu.unc.lib.dl.search.solr.model.SearchFacet;
import edu.unc.lib.dl.search.solr.model.SearchState;
import edu.unc.lib.dl.search.solr.model.SearchState.RangePair;

/**
 * Utility class which transforms search states to other formats.
 * @author bbpennel
 */
public class SearchStateUtil {
    private static SearchSettings searchSettings;

    public SearchStateUtil() {

    }

    private static String urlEncodeParameter(String value) {
        char[] chars = value.toCharArray();
        StringBuilder sb = new StringBuilder();
        for (char character: chars) {
            if (character == '&') {
                sb.append("%26");
            } else if (character == '=') {
                sb.append("%3D");
            } else if (character == '#') {
                sb.append("%23");
            } else {
                sb.append(character);
            }
        }
        return sb.toString();
    }

    public static HashMap<String,String> generateSearchParameters(SearchState searchState) {
        HashMap<String,String> params = new HashMap<String,String>();
        if (searchState.getSearchFields() != null && searchState.getSearchFields().size() > 0) {
            for (Entry<String,String> field: searchState.getSearchFields().entrySet()) {
                String fieldName = searchSettings.searchFieldParam(field.getKey());
                params.put(fieldName, urlEncodeParameter(field.getValue()));
            }
        }

        if (searchState.getRangeFields() != null && searchState.getRangeFields().size() > 0) {
            for (Entry<String, RangePair> field: searchState.getRangeFields().entrySet()) {
                String fieldName = searchSettings.searchFieldParam(field.getKey());
                params.put(fieldName, urlEncodeParameter(field.getValue().toString()));
            }
        }
        String ancestorPath = SearchFieldKeys.ANCESTOR_PATH.toString();
        if (searchState.getFacets() != null && searchState.getFacets().size() > 0) {
            for (Entry<String,List<SearchFacet>> field: searchState.getFacets().entrySet()) {
                if (ancestorPath.equals(field.getKey())) {
                    continue;
                }
                String fieldName = searchSettings.searchFieldParam(field.getKey());
                String value = field.getValue().stream()
                        .map(v -> urlEncodeParameter(v.getLimitToValue()))
                        .collect(Collectors.joining("||"));
                params.put(fieldName, value);
            }
        }
        return params;
    }


    /**
     * Returns the search state as a URL query string.
     * @param searchState
     * @return
     */
    public static HashMap<String,String> generateStateParameters(SearchState searchState) {
        HashMap<String,String> params = generateSearchParameters(searchState);

        if (searchState.getRowsPerPage() != null && searchState.getRowsPerPage() >= 0) {
            params.put(searchSettings.searchStateParam("ROWS_PER_PAGE"), "" + searchState.getRowsPerPage());
        }

        if (searchState.getFacetsToRetrieve() != null && searchState.getFacetsToRetrieve().size() > 0
                && !searchState.getFacetsToRetrieve().containsAll(searchSettings.facetNames)) {
            params.put(searchSettings.searchStateParam("FACET_FIELDS_TO_RETRIEVE"),
                    joinFields(searchState.getFacetsToRetrieve(), ",", true));
        }

        if (searchState.getFacetLimits() != null && searchState.getFacetLimits().size() > 0) {
            params.put(searchSettings.searchStateParam("FACET_LIMIT_FIELDS"),
                    joinFields(searchState.getFacetLimits()));
        }

        if (searchState.getStartRow() != null && searchState.getStartRow() != 0) {
            params.put(searchSettings.searchStateParam("START_ROW"), "" + searchState.getStartRow());
        }

        //Add base facet limit if it isn't the default
        if (searchState.getBaseFacetLimit() != null
                && searchState.getBaseFacetLimit() != searchSettings.facetsPerGroup) {
            params.put(searchSettings.searchStateParam("BASE_FACET_LIMIT"), "" + searchState.getBaseFacetLimit());
        }

        if (searchState.getSortType() != null && searchState.getSortType().length() != 0) {
            if (!"default".equals(searchState.getSortType()) || !searchState.getSortNormalOrder()) {
                StringBuilder sortValue = new StringBuilder();
                sortValue.append(searchState.getSortType());

                if (!searchState.getSortNormalOrder()) {
                    sortValue.append(',').append(searchSettings.sortReverse);
                }

                params.put(searchSettings.searchStateParam("SORT_TYPE"), sortValue.toString());
            }
        }

        //Append search term operator if its not the default
        if (searchState.getSearchTermOperator() != null &&
                !searchState.getSearchTermOperator().equals(searchSettings.defaultOperator)) {
            params.put(searchSettings.searchStateParam("SEARCH_TERM_OPERATOR"), searchState.getSearchTermOperator());
        }

        if (searchState.getResourceTypes() != null
                && !searchState.getResourceTypes().containsAll(searchSettings.defaultResourceTypes)) {
            params.put(searchSettings.searchStateParam("RESOURCE_TYPES"),
                    joinFields(searchState.getResourceTypes(), ",", false));
        }

        return params;
    }

    public static String generateSearchParameterString(SearchState searchState) {
        return generateStateParameterString(generateSearchParameters(searchState));
    }

    public static String generateStateParameterString(SearchState searchState) {
        return generateStateParameterString(generateStateParameters(searchState));
    }

    public static String generateStateParameterString(HashMap<String,String> stateParameters) {
        return joinFields(stateParameters, '&', '=', false);
    }

    private static String joinFields(Collection<String> collection, String delimiter, boolean performFieldLookup) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String object: collection) {
            if (first) {
                first = false;
            } else {
                sb.append(delimiter);
            }
            if (performFieldLookup) {
                sb.append(searchSettings.searchFieldParam(object));
            } else {
                sb.append(object);
            }

        }
        return sb.toString();
    }

    private static String joinFields(Map<?,?> fields, char pairDelimiter, char keyValueDelimiter) {
        return joinFields(fields, pairDelimiter, keyValueDelimiter, true);
    }

    private static String joinFields(Map<?,?> fields, char pairDelimiter, char keyValueDelimiter,
            boolean performFieldLookup) {
        StringBuilder sb = new StringBuilder();
        boolean firstField = true;
        Iterator<?> fieldIt = fields.keySet().iterator();
        while (fieldIt.hasNext()) {
            String fieldName = (String)fieldIt.next();
            Object value = fields.get(fieldName);
            if (value != null && value.toString().trim().length() > 0) {
                if (firstField) {
                    firstField = false;
                } else {
                    sb.append(pairDelimiter);
                }
                if (performFieldLookup) {
                    sb.append(searchSettings.searchFieldParam(fieldName));
                } else {
                    sb.append(fieldName);
                }
                sb.append(keyValueDelimiter);

                if (value != null) {
                    sb.append(value);
                }
            }
        }
        return sb.toString();
    }

    private static String joinFields(Map<?,?> fields) {
        return joinFields(fields, '|', ':');
    }

    /**
     * Transforms a search state in URL format to a parameter map.
     * @param searchStateUrl
     * @return
     */
    public static HashMap<String, String[]> getParametersAsHashMap(String searchStateUrl) {
        HashMap<String,String[]> parameterHashMap = new HashMap<String,String[]>();
        String[] parameterList = searchStateUrl.split("&");
        for (String parameter : parameterList) {
            String[] parameterPair = parameter.split("=");
            if (parameterPair.length == 2) {
                try {
                    String[] valueArray = new String[1];
                    valueArray[0] = URLDecoder.decode(parameterPair[1], "UTF-8");
                    parameterHashMap.put(parameterPair[0], valueArray);
                } catch (Exception e) {

                }
            }
        }
        return parameterHashMap;
    }

    public SearchSettings getSearchSettings() {
        return searchSettings;
    }

    @Autowired
    public void setSearchSettings(SearchSettings searchSettings) {
        SearchStateUtil.searchSettings = searchSettings;
    }
}
