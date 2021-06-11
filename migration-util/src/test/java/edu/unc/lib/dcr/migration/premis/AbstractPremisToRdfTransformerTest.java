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
package edu.unc.lib.dcr.migration.premis;

import static edu.unc.lib.dcr.migration.premis.TestPremisEventHelpers.createPremisDoc;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.List;

import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.jdom2.Document;
import org.jgroups.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.services.event.FilePremisLogger;
import edu.unc.lib.dl.rdf.Premis;

/**
 * @author bbpennel
 *
 */
public abstract class AbstractPremisToRdfTransformerTest {
    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    protected File logFile;

    protected PID objPid;

    protected Document premisDoc;

    protected PremisLogger premisLogger;
    protected RepositoryPIDMinter pidMinter;

    @Before
    public void setupParent() throws Exception {
        pidMinter = new RepositoryPIDMinter();

        objPid = makePid();
        logFile = tmpFolder.newFile(objPid.getId() + ".nt");
        premisDoc = createPremisDoc(objPid);
        premisLogger = new FilePremisLogger(objPid, logFile, pidMinter);
    }

    protected PID makePid() {
        return PIDs.get(UUID.randomUUID().toString());
    }

    protected Resource getResourceByEventDate(List<Resource> rescs, String eventDate) {
        return rescs.stream().filter(r -> eventDate.equals(r.getProperty(DCTerms.date).getString()))
                .findFirst().get();
    }

    protected void assertEventType(Resource expectedType, Resource eventResc) {
        assertEquals("Event type did not match expected value",
                expectedType, eventResc.getPropertyResourceValue(RDF.type));
    }

    protected void assertEventDetail(String expected, Resource eventResc) {
        List<String> details = eventResc.listProperties(Premis.note).toList().stream()
                .map(Statement::getString).collect(toList());
        if (details.contains(expected)) {
            return;
        }
        fail("Event detail message did not match expected value " + expected
                + ", the following values were present: " + String.join(",", details));
    }

    protected void assertEventDateTime(String expected, Resource eventResc) {
        assertEquals("Event date time did not match expected value",
                expected, eventResc.getProperty(DCTerms.date).getString());
    }

    protected void assertEventOutcomeSuccess(Resource eventResc) {
        assertTrue("Expected event outcome to be Success",
                eventResc.hasProperty(Premis.outcome, Premis.Success));
    }

    protected void assertEventOutcomeFail(Resource eventResc) {
        assertTrue("Expected event outcome to be Fail",
                eventResc.hasProperty(Premis.outcome, Premis.Fail));
    }

    protected void assertNoEventOutcome(Resource eventResc) {
        assertFalse("Expected event to have no outcome status",
                eventResc.hasProperty(Premis.outcome));
    }
}
