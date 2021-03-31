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
package edu.unc.lib.dl.services.camel.longleaf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.slf4j.LoggerFactory.getLogger;

import java.net.URI;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;

/**
 * @author bbpennel
 */
public abstract class AbstractLongleafRouteTest {
    private static final Logger log = getLogger(AbstractLongleafRouteTest.class);

    protected String outputPath;
    protected List<String> output;

    /**
     * Assert that all of the provided content uris are present in the longleaf output
     * @param timeout time in milliseconds allowed for the condition to become true,
     *      to accommodate asynchronous unpredictable batch cutoffs
     * @param contentUris list of expected content uris
     * @throws Exception
     */
    protected void assertSubmittedPaths(long timeout, String... contentUris) throws Exception {
        long start = System.currentTimeMillis();
        do {
            try {
                output = LongleafTestHelpers.readOutput(outputPath);
                assertSubmittedPaths(contentUris);
                return;
            } catch (AssertionError e) {
                if ((System.currentTimeMillis() - start) > timeout) {
                    throw e;
                }
                Thread.sleep(25);
                log.debug("DeregisterPaths not yet satisfied, retrying");
            }
        } while (true);
    }

    protected void assertSubmittedPaths(String... contentUris) {
        for (String contentUri : contentUris) {
            URI uri = URI.create(contentUri);
            String contentPath = uri.getScheme() == null ? contentUri : Paths.get(uri).toString();
            assertTrue("Expected content uri to be submitted: " + contentPath,
                    output.stream().anyMatch(line -> line.contains(contentPath)));
        }
    }

    protected void assertNoSubmittedPaths() throws Exception {
        output = LongleafTestHelpers.readOutput(outputPath);
        assertEquals("Expected no calls to longleaf, but received output:\n" + String.join("\n", output),
                0, output.size());
    }
}
