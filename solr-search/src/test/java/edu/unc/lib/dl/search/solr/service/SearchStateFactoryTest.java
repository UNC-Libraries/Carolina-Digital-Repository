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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.Before;
import org.junit.Test;

import edu.unc.lib.dl.search.solr.model.CutoffFacet;
import edu.unc.lib.dl.search.solr.model.FacetFieldFactory;
import edu.unc.lib.dl.search.solr.model.SearchFacet;
import edu.unc.lib.dl.search.solr.model.SearchState;
import edu.unc.lib.dl.search.solr.util.SearchFieldKeys;
import edu.unc.lib.dl.search.solr.util.SearchSettings;

/**
 * @author bbpennel
 */
public class SearchStateFactoryTest {

    private SearchStateFactory searchStateFactory;
    private SearchActionService sas;

    @Before
    public void setup() throws Exception {
        Properties properties = new Properties();
        properties.load(new FileInputStream("src/test/resources/search.properties"));

        SearchSettings searchSettings = new SearchSettings();
        searchSettings.setProperties(properties);

        FacetFieldFactory fff = new FacetFieldFactory();
        fff.setSearchSettings(searchSettings);

        sas = new SearchActionService();
        sas.setSearchSettings(searchSettings);

        searchStateFactory = new SearchStateFactory();
        searchStateFactory.setSearchSettings(searchSettings);
        searchStateFactory.setFacetFieldFactory(fff);
    }

    @Test
    public void nullTier() throws Exception {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        parameters.put("action", new String[]{"setFacet%3apath%2c%221%2cuuid%3ac34ae354-8626-48c6-9963-d907aa65a713%22"});
        SearchState searchState = searchStateFactory.createSearchState(parameters);

        assertFalse(searchState.getFacets().containsKey("ANCESTOR_PATH"));
    }

    @Test
    public void cutoffTier() throws Exception {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        parameters.put("path", new String[]{"2,uuid:52726582-2cea-455a-8220-c360dbe5082b!3"});
        parameters.put("a.resetNav", new String[]{"search"});
        parameters.put("anywhere", new String[]{"query"});
        parameters.put("rows", new String[]{"20"});
        SearchState searchState = searchStateFactory.createSearchState(parameters);

        sas.executeActions(searchState, parameters);

        assertTrue(searchState.getFacets().containsKey("ANCESTOR_PATH"));
        List<SearchFacet> facetValues = searchState.getFacets().get("ANCESTOR_PATH");
        assertEquals(1, facetValues.size());
        assertEquals(3, ((CutoffFacet) facetValues.get(0)).getCutoff().intValue());
    }

    @Test
    public void extractFacets() throws Exception {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        parameters.put("path", new String[]{"2,uuid:52726582-2cea-455a-8220-c360dbe5082b!3"});
        parameters.put("anywhere", new String[]{"query"});
        parameters.put("start", new String[]{"55"});

        SearchState searchState = searchStateFactory.createSearchState(parameters);

        assertTrue(searchState.getFacets().containsKey("ANCESTOR_PATH"));
        List<SearchFacet> facetValues = searchState.getFacets().get("ANCESTOR_PATH");
        assertEquals(1, facetValues.size());
        assertEquals(3, ((CutoffFacet) facetValues.get(0)).getCutoff().intValue());
        assertTrue(searchState.getSearchFields().containsKey(SearchFieldKeys.DEFAULT_INDEX.name()));
        assertEquals(55, searchState.getStartRow().intValue());
    }

    @Test
    public void extractMultiplePathFacets() throws Exception {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        parameters.put("path", new String[]
                {"2,52726582-2cea-455a-8220-c360dbe5082b!3||4,b0c94c4f-58c4-4451-a402-f98e531688ff"});
        parameters.put("anywhere", new String[]{"query"});
        parameters.put("start", new String[]{"55"});

        SearchState searchState = searchStateFactory.createSearchState(parameters);

        assertTrue(searchState.getFacets().containsKey("ANCESTOR_PATH"));
        List<SearchFacet> facetValues = searchState.getFacets().get("ANCESTOR_PATH");
        assertEquals(2, facetValues.size());
        assertEquals("2,52726582-2cea-455a-8220-c360dbe5082b", facetValues.get(0).getSearchValue());
        assertEquals(3, ((CutoffFacet) facetValues.get(0)).getCutoff().intValue());
        assertEquals("4,b0c94c4f-58c4-4451-a402-f98e531688ff", facetValues.get(1).getSearchValue());
        assertNull(((CutoffFacet) facetValues.get(1)).getCutoff());
        assertTrue(searchState.getSearchFields().containsKey(SearchFieldKeys.DEFAULT_INDEX.name()));
        assertEquals(55, searchState.getStartRow().intValue());
    }

    @Test
    public void extractMultipleRoleGroup() throws Exception {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        parameters.put("role", new String[]{"unitOwner|unc%3aonyen%3aadmin||canManage|managerGroup"});
        parameters.put("anywhere", new String[]{""});
        parameters.put("start", new String[]{"0"});

        SearchState searchState = searchStateFactory.createSearchState(parameters);

        assertTrue(searchState.getFacets().containsKey("ROLE_GROUP"));

        List<SearchFacet> facetValues = searchState.getFacets().get("ROLE_GROUP");
        assertEquals(2, facetValues.size());
        assertEquals("unitOwner|unc:onyen:admin", facetValues.get(0).getSearchValue());
        assertEquals("canManage|managerGroup", facetValues.get(1).getSearchValue());
        assertEquals(0, searchState.getStartRow().intValue());
    }

    @Test
    public void extractCollectionFacets() throws Exception {
        Properties properties = new Properties();
        properties.load(new FileInputStream("src/test/resources/search.properties"));

        SearchSettings searchSettings = new SearchSettings();
        searchSettings.setProperties(properties);

        SearchStateFactory searchStateFactory = new SearchStateFactory();
        searchStateFactory.setSearchSettings(searchSettings);
        Map<String, String[]> parameters = new LinkedHashMap<String, String[]>();
        parameters.put("collection", new String[]{"52726582-2cea-455a-8220-c360dbe5082b"});
        parameters.put("anywhere", new String[]{"query"});
        FacetFieldFactory fff = new FacetFieldFactory();
        fff.setSearchSettings(searchSettings);
        searchStateFactory.setFacetFieldFactory(fff);

        SearchState searchState = searchStateFactory.createSearchState(parameters);

        assertTrue(searchState.getFacets().containsKey("PARENT_COLLECTION"));
        List<SearchFacet> facetValues = searchState.getFacets().get("PARENT_COLLECTION");
        assertEquals("52726582-2cea-455a-8220-c360dbe5082b", facetValues.get(0).getSearchValue());
        assertTrue(searchState.getSearchFields().containsKey(SearchFieldKeys.DEFAULT_INDEX.name()));
    }
}
