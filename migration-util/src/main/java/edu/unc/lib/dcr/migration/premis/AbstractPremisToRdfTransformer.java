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

import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.PREMIS_V2_NS;
import static java.util.stream.Collectors.toList;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.concurrent.RecursiveAction;
import java.util.stream.Collectors;

import org.apache.jena.rdf.model.Resource;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.slf4j.Logger;

import edu.unc.lib.dl.event.PremisEventBuilder;
import edu.unc.lib.dl.event.PremisLogger;
import edu.unc.lib.dl.exceptions.RepositoryException;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.util.DateTimeUtil;
import edu.unc.lib.dl.util.URIUtil;
import edu.unc.lib.dl.xml.SecureXMLFactory;

/**
 * @author bbpennel
 *
 */
public abstract class AbstractPremisToRdfTransformer extends RecursiveAction implements PremisToRdfTransformer {
    private static final long serialVersionUID = 1L;

    private static final Logger log = getLogger(AbstractPremisToRdfTransformer.class);

    protected PID pid;
    protected PremisLogger premisLogger;
    protected Document doc;
    protected Path docPath;

    protected AbstractPremisToRdfTransformer(PID pid, PremisLogger premisLogger, Document doc) {
        this.pid = pid;
        this.premisLogger = premisLogger;
        this.doc = doc;
    }

    protected AbstractPremisToRdfTransformer(PID pid, PremisLogger premisLogger, Path docPath) {
        this.pid = pid;
        this.premisLogger = premisLogger;
        this.docPath = docPath;
    }

    protected Document getDocument() {
        if (doc != null) {
            return doc;
        }
        SAXBuilder sb = SecureXMLFactory.createSAXBuilder();
        try {
            try {
                doc = sb.build(docPath.toFile());
            } catch (JDOMException e) {
                if (Files.size(docPath) == 0) {
                    throw new NoExistingPremisEventLogException("PREMIS log file " + docPath
                            + " for " + pid + " is empty", e);
                } else {
                    throw new RepositoryException("Failed to parse PREMIS document for " + pid, e);
                }
            }
        } catch (IOException e) {
            throw new RepositoryException("Failed to load PREMIS document for " + pid, e);
        }
        return doc;
    }

    protected List<String> getEventTypes(Element eventEl) {
        return eventEl.getChildren("eventType", PREMIS_V2_NS).stream()
                .map(Element::getTextTrim)
                .collect(toList());
    }

    protected Date getEventDateTime(Element eventEl) {
        String eventDateTime = eventEl.getChildTextTrim("eventDateTime", PREMIS_V2_NS);
        if (eventDateTime == null) {
            log.warn("No datetime for event on object {}", pid);
            return null;
        }
        return DateTimeUtil.parseUTCToDateTime(eventDateTime).toDate();
    }

    protected String getEventDetail(Element eventEl) {
        return eventEl.getChildTextTrim("eventDetail", PREMIS_V2_NS);
    }

    protected String getEventOutcomeDetailNote(Element eventEl) {
        Element outcomeEl = eventEl.getChild("eventOutcomeInformation", PREMIS_V2_NS);
        if (outcomeEl == null) {
            return null;
        }
        Element outcomeDetailEl = outcomeEl.getChild("eventOutcomeDetail", PREMIS_V2_NS);
        if (outcomeDetailEl == null) {
            return null;
        }
        return outcomeDetailEl.getChildTextTrim("eventOutcomeDetailNote", PREMIS_V2_NS);
    }

    protected Boolean getEventOutcome(Element eventEl) {
        Element infoEl = eventEl.getChild("eventOutcomeInformation", PREMIS_V2_NS);
        if (infoEl == null) {
            return null;
        }
        Element outcomeEl = infoEl.getChild("eventOutcome", PREMIS_V2_NS);
        if (outcomeEl == null) {
            return null;
        }

        String outcomeText = outcomeEl.getTextTrim();
        if ("success".equalsIgnoreCase(outcomeText) ) {
            return true;
        } else if ("failed".equalsIgnoreCase(outcomeText) || "fail".equalsIgnoreCase(outcomeText)) {
            return false;
        } else {
            return null;
        }
    }

    protected PID getEventPid(Element eventEl) {
        String idVal = eventEl.getChild("eventIdentifier", PREMIS_V2_NS)
                .getChildTextTrim("eventIdentifierValue", PREMIS_V2_NS);
        idVal = idVal.replaceFirst("urn:uuid:", "");

        String eventUrl = URIUtil.join(pid.getRepositoryPath(), idVal);
        return PIDs.get(eventUrl);
    }

    protected String getLinkingAgent(Element eventEl) {
        return eventEl.getChild("linkingAgentIdentifier", PREMIS_V2_NS)
            .getChildTextTrim("linkingAgentIdentifierValue", PREMIS_V2_NS);
    }

    protected List<String> getLinkingAgents(Element eventEl) {
        return eventEl.getChildren("linkingAgentIdentifier", PREMIS_V2_NS).stream()
                .map(c -> c.getChildTextTrim("linkingAgentIdentifierValue", PREMIS_V2_NS))
                .collect(Collectors.toList());
    }

    protected PremisEventBuilder createEventBuilder(Resource eventTypeResc, Element eventEl) {
        Date dateTime = getEventDateTime(eventEl);
        PID eventPid = getEventPid(eventEl);
        Boolean outcome = getEventOutcome(eventEl);
        PremisEventBuilder builder = premisLogger.buildEvent(eventPid, eventTypeResc, dateTime);
        if (outcome != null) {
            builder.addOutcome(outcome);
        }

        return builder;
    }
}
