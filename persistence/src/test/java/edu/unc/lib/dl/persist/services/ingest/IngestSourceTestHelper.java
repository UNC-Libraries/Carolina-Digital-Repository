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
package edu.unc.lib.dl.persist.services.ingest;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.unc.lib.dl.fedora.ContentPathFactory;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.ingest.IngestSourceCandidate;
import edu.unc.lib.dl.persist.api.ingest.IngestSourceManager;
import edu.unc.lib.dl.persist.services.ingest.IngestSourceManagerImpl.IngestSourceMapping;
import edu.unc.lib.dl.util.PackagingType;

/**
 * @author bbpennel
 *
 */
public class IngestSourceTestHelper {

    private IngestSourceTestHelper() {
    }

    public static Path addBagToSource(Path ingestSourcePath) throws Exception {
        String bagName = "bag_with_files";
        return addBagToSource(ingestSourcePath, bagName, bagName);
    }

    public static Path addBagToSource(Path ingestSourcePath, String bagName, String destName) throws Exception {
        File original = new File("src/test/resources/ingestSources/" + bagName);
        Path bagPath = ingestSourcePath.resolve(destName);
        FileUtils.copyDirectory(original, bagPath.toFile());
        return bagPath;
    }

    public static IngestSourceCandidate findCandidateByPath(String patternMatched,
            List<IngestSourceCandidate> candidates) {
        return candidates.stream().filter(c -> c.getPatternMatched().equals(patternMatched)).findFirst().get();
    }

    public static void assertBagDetails(IngestSourceCandidate candidate, String sourceId, String patternMatched) {
        assertEquals(sourceId, candidate.getSourceId());
        assertEquals(PackagingType.BAGIT, candidate.getPackagingType());
        assertEquals("0.96", candidate.getVersion());
        assertEquals(15, candidate.getFileSize().longValue());
        assertEquals(3, candidate.getFileCount().intValue());
        assertEquals(patternMatched, candidate.getPatternMatched());
    }

    public static void assertDirectoryDetails(IngestSourceCandidate candidate, String sourceId, String patternMatched) {
        assertEquals(sourceId, candidate.getSourceId());
        assertEquals(PackagingType.DIRECTORY, candidate.getPackagingType());
        assertEquals(patternMatched, candidate.getPatternMatched());
    }

    public static Map<String, Object> createFilesystemConfig(String id, String name, Path basePath,
            List<String> patterns) {
        return createFilesystemConfig(id, name, basePath, patterns, false);
    }

    public static Map<String, Object> createFilesystemConfig(String id, String name, Path basePath,
            List<String> patterns, boolean readOnly) {
        Map<String, Object> config = new HashMap<>();
        config.put("id", id);
        config.put("name", name);
        config.put("patterns", patterns);
        config.put("base", basePath.toUri().toString());
        config.put("type", "filesystem");
        config.put("readOnly", readOnly);
        return config;
    }

    public static void addMapping(String id, PID containerPid, List<IngestSourceMapping> mappings) {
        IngestSourceMapping mapping = mappings.stream().filter(m -> m.getId().equals(containerPid.getId())).findFirst()
                .orElse(null);
        if (mapping == null) {
            mapping = new IngestSourceMapping();
            mapping.setId(containerPid.getId());
            mapping.setSources(new ArrayList<>());
            mappings.add(mapping);
        }
        mapping.getSources().add(id);
    }

    @SafeVarargs
    public static final Path createConfigFile(Map<String, Object>... configs) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path configPath = Files.createTempFile("ingestSources", ".json");
        mapper.writeValue(configPath.toFile(), configs);
        return configPath;
    }

    public static Path serializeLocationMappings(List<IngestSourceMapping> mappings) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path jsonPath = Files.createTempFile("sourceMapping", ".json");
        objectMapper.writeValue(jsonPath.toFile(), mappings);
        return jsonPath;
    }

    public static IngestSourceManager createIngestSourceManagerWithBasicConfig(
            String baseDir, ContentPathFactory contentPathFactory)
            throws Exception {
        if (StringUtils.isBlank(baseDir)) {
            return null;
        }
        Path basePath = Paths.get(baseDir);
        Map<String, Object> conf = createFilesystemConfig("ingest1", "ingest source", basePath, asList("*"));
        Path configPath = createConfigFile(conf);

        List<IngestSourceMapping> mappingList = new ArrayList<>();
        IngestSourceMapping mapping = new IngestSourceMapping();
        mapping.setId("collections");
        mapping.setSources(asList("ingest1"));
        mappingList.add(mapping);

        Path mappingPath = serializeLocationMappings(mappingList);

        IngestSourceManagerImpl manager = new IngestSourceManagerImpl();
        manager.setConfigPath(configPath.toString());
        manager.setMappingPath(mappingPath.toString());
        manager.setContentPathFactory(contentPathFactory);
        manager.init();

        return manager;
    }
}
