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
package edu.unc.lib.dcr.migration.deposit;

import static edu.unc.lib.dcr.migration.premis.TestPremisEventHelpers.getEventByType;
import static edu.unc.lib.dcr.migration.premis.TestPremisEventHelpers.listEventResources;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;

import edu.unc.lib.dcr.migration.AbstractTransformationIT;
import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.DepositRecord;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;

/**
 * @author bbpennel
 */
public abstract class AbstractDepositRecordTransformationIT extends AbstractTransformationIT {

    protected BinaryObject getManifestByName(List<BinaryObject> binList, String dsName) {
        return binList.stream()
                .filter(b -> b.getPid().getComponentPath().endsWith(dsName.toLowerCase()))
                .findFirst()
                .get();
    }

    protected void assertManifestDetails(Date expectedTimestamp, String expectedMimetype,
            String expectedContent, BinaryObject manifestBin) throws IOException {
        assertEquals(expectedTimestamp, manifestBin.getLastModified());
        assertEquals(expectedTimestamp, manifestBin.getCreatedDate());
        assertEquals(expectedMimetype, manifestBin.getMimetype());
        assertEquals(expectedContent, IOUtils.toString(manifestBin.getBinaryStream(), UTF_8));
    }

    protected void assertPremisTransformed(DepositRecord depRec) throws IOException {
        PremisLogger premisLog = depRec.getPremisLog();
        Model eventsModel = premisLog.getEventsModel();
        List<Resource> eventRescs = listEventResources(depRec.getPid(), eventsModel);
        assertEquals(2, eventRescs.size());

        Resource virusEventResc = getEventByType(eventRescs, Premis.VirusCheck);
        assertNotNull("Virus event was not present in premis log",
                virusEventResc);
        Resource migrationEventResc = getEventByType(eventRescs, Premis.Ingestion);
        assertTrue("Missing migration event note",
                migrationEventResc.hasProperty(Premis.note, "Object migrated from Boxc 3 to Boxc 5"));
        Resource agentResc = migrationEventResc.getProperty(Premis.hasEventRelatedAgentExecutor).getResource();
        assertEquals(AgentPids.forSoftware(SoftwareAgent.migrationUtil).getRepositoryPath(),
                agentResc.getURI());
    }
}
