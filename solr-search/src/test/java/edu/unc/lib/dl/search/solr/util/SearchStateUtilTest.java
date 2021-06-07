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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.Test;

import edu.unc.lib.dl.search.solr.model.SearchState;

/**
 * @author bbpennel
 */
public class SearchStateUtilTest {

    @BeforeClass
    public static void setupClass() throws Exception {
        Properties searchProps = new Properties();
        searchProps.load(SearchStateUtilTest.class.getResourceAsStream("/search.properties"));
        SearchSettings searchSettings = new SearchSettings();
        searchSettings.setProperties(searchProps);

        SearchStateUtil searchStateUtil = new SearchStateUtil();
        searchStateUtil.setSearchSettings(searchSettings);
    }

    @Test
    public void generateSearchParametersEmptyState() throws Exception {
        SearchState searchState = new SearchState();

        Map<String, String> params = SearchStateUtil.generateSearchParameters(searchState);
        assertTrue(params.isEmpty());
    }

    @Test
    public void generateSearchParametersKeywordState() throws Exception {
        SearchState searchState = new SearchState();
        searchState.getSearchFields().put(SearchFieldKeys.KEYWORD.name(), "test words");

        Map<String, String> params = SearchStateUtil.generateSearchParameters(searchState);
        assertEquals("test words", params.get("keyword"));
    }

    @Test
    public void generateSearchParametersSubjectFacet() throws Exception {
        SearchState searchState = new SearchState();
        searchState.addFacet(SearchFieldKeys.SUBJECT, "subject value");

        Map<String, String> params = SearchStateUtil.generateSearchParameters(searchState);
        assertEquals("subject value", params.get("subject"));
    }

    @Test
    public void generateSearchParametersMultipleSubjectFacet() throws Exception {
        SearchState searchState = new SearchState();
        searchState.addFacet(SearchFieldKeys.SUBJECT, "subject1");
        searchState.addFacet(SearchFieldKeys.SUBJECT, "subject two");
        searchState.addFacet(SearchFieldKeys.SUBJECT, "last one");

        Map<String, String> params = SearchStateUtil.generateSearchParameters(searchState);
        assertEquals("subject1||subject two||last one", params.get("subject"));
    }

    @Test
    public void generateSearchParametersMultipleRoleGroup() throws Exception {
        SearchState searchState = new SearchState();
        searchState.addFacet(SearchFieldKeys.ROLE_GROUP, "canViewOriginals|everyone");
        searchState.addFacet(SearchFieldKeys.ROLE_GROUP, "canViewOriginals|authenticated");

        Map<String, String> params = SearchStateUtil.generateSearchParameters(searchState);
        assertEquals("canViewOriginals|everyone||canViewOriginals|authenticated", params.get("role"));
    }

    @Test
    public void generateSearchParametersMultipleFacets() throws Exception {
        SearchState searchState = new SearchState();
        searchState.addFacet(SearchFieldKeys.SUBJECT, "subject1");
        searchState.addFacet(SearchFieldKeys.SUBJECT, "subject two");
        searchState.addFacet(SearchFieldKeys.ROLE_GROUP, "canViewOriginals|everyone");

        Map<String, String> params = SearchStateUtil.generateSearchParameters(searchState);
        assertEquals("canViewOriginals|everyone", params.get("role"));
        assertEquals("subject1||subject two", params.get("subject"));
    }

    @Test
    public void generateSearchParametersAncestorPath() throws Exception {
        SearchState searchState = new SearchState();
        searchState.addFacet(SearchFieldKeys.ANCESTOR_PATH, "3,c31a3e50-8ee7-4381-a16d-0be47c9aeee4");

        Map<String, String> params = SearchStateUtil.generateSearchParameters(searchState);
        assertTrue(params.isEmpty());
    }

    @Test
    public void generateSearchParameterString() {
        SearchState searchState = new SearchState();
        searchState.getSearchFields().put(SearchFieldKeys.KEYWORD.name(), "test words");
        searchState.addFacet(SearchFieldKeys.SUBJECT, "subject1");
        searchState.addFacet(SearchFieldKeys.SUBJECT, "subject two");
        searchState.addFacet(SearchFieldKeys.ROLE_GROUP, "canViewOriginals|everyone");

        String paramString = SearchStateUtil.generateSearchParameterString(searchState);
        Map<String, String> params = Arrays.stream(paramString.split("&"))
                .collect(Collectors.toMap(k -> k.split("=")[0], v -> v.split("=")[1]));
        assertEquals(3, params.size());
        assertEquals("test words", params.get("keyword"));
        assertEquals("canViewOriginals|everyone", params.get("role"));
        assertEquals("subject1||subject two", params.get("subject"));
    }

    @Test
    public void generateSearchParameterStringEmptyState() {
        SearchState searchState = new SearchState();

        String paramString = SearchStateUtil.generateSearchParameterString(searchState);
        assertEquals(0, paramString.length());
    }
}
