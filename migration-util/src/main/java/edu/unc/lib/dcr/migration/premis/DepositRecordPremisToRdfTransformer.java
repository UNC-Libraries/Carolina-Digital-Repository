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

import static edu.unc.lib.dcr.migration.premis.Premis2Constants.VALIDATE_MODS_AGENT;
import static edu.unc.lib.dcr.migration.premis.Premis2Constants.VIRUS_CHECK_TYPE;
import static edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent.clamav;
import static edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent.depositService;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.PREMIS_V2_NS;
import static org.slf4j.LoggerFactory.getLogger;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jdom2.Document;
import org.jdom2.Element;
import org.slf4j.Logger;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.rdf.Premis;

/**
 * Populates a PREMIS 3 logger with events transformed from an XML PREMIS 2 log for one object.
 *
 * @author bbpennel
 *
 */
public class DepositRecordPremisToRdfTransformer extends AbstractPremisToRdfTransformer {
    private static final long serialVersionUID = 1L;

    private static final Logger log = getLogger(DepositRecordPremisToRdfTransformer.class);

    private static final Pattern NORMALIZE_FORMAT_PATTERN = Pattern.compile(
            "Normalized deposit package from ([^ ]+) to.*");

    private PID depositServicePid;

    public DepositRecordPremisToRdfTransformer(PID pid, PremisLogger premisLogger, Document doc) {
        super(pid, premisLogger, doc);
    }

    public DepositRecordPremisToRdfTransformer(PID pid, PremisLogger premisLogger, Path docPath) {
        super(pid, premisLogger, docPath);
    }

    @Override
    public void compute() {
        depositServicePid = AgentPids.forSoftware(depositService);
        List<Element> eventEls = getDocument().getRootElement().getChildren("event", PREMIS_V2_NS);

        for (Element eventEl: eventEls) {
            List<String> eventTypes = getEventTypes(eventEl);
            if (eventTypes.isEmpty()) {
                log.error("No type present on event for record {}", pid);
                continue;
            }

            if (eventTypes.contains(VIRUS_CHECK_TYPE)) {
                addVirusCheckEvent(eventEl);
            } else if (eventTypes.contains(Premis2Constants.INGESTION_TYPE)) {
                addIngestionEvent(eventEl);
            } else if (eventTypes.contains(Premis2Constants.NORMALIZATION_TYPE)) {
                transformNormalizationEvent(eventEl);
            } else if (eventTypes.contains(Premis2Constants.VALIDATION_TYPE)) {
                addValidationEvent(eventEl);
            } else {
                log.error("Unknown event types for {}: {}", pid, eventTypes);
            }
        }
    }

    private void addVirusCheckEvent(Element eventEl) {
        createEventBuilder(Premis.VirusCheck, eventEl)
            .addEventDetail(getEventDetail(eventEl))
            .addSoftwareAgent(AgentPids.forSoftware(clamav))
            .write();
    }

    private void addIngestionEvent(Element eventEl) {
        String eventDetail = getEventDetail(eventEl);
        if (eventDetail == null) {
            log.error("No event details for ingestion event on {}", pid);
            return;
        }

        if (!eventDetail.contains("ingested as PID")) {
            log.error("Unknown deposit validation event for {}, with detail: {}", pid, eventDetail);
            return;
        }

        // ingested as PID
        createEventBuilder(Premis.Ingestion, eventEl)
            .addEventDetail(getEventDetail(eventEl))
            .addSoftwareAgent(depositServicePid)
            .write();
    }

    private void addValidationEvent(Element eventEl) {
        String eventDetail = getEventDetail(eventEl);
        if (eventDetail == null) {
            log.error("No event details for validation event on {}", pid);
            return;
        }

        String agent = getLinkingAgent(eventEl);
        if (VALIDATE_MODS_AGENT.equals(agent)) {
            log.info("Ignoring MODS validation event on {}", pid);
            return;
        }

        if (!eventDetail.contains("METS")) {
            log.error("Unknown deposit validation event for {}, with detail: {}", pid, eventDetail);
            return;
        }

        createEventBuilder(Premis.Validation, eventEl)
            .addEventDetail("METS schema validated")
            .addSoftwareAgent(depositServicePid)
            .write();
    }

    private void transformNormalizationEvent(Element eventEl) {
        String eventDetail = getEventDetail(eventEl);
        if (eventDetail == null) {
            log.error("No event details for normalization event on {}", pid);
            return;
        }

        if (eventDetail.contains("Deposit recorded")) {
            return;
        }

        // PID assignment normalization jobs become info package creation
        if (eventDetail.contains("Assigned PID")) {
            createEventBuilder(Premis.InformationPackageCreation, eventEl)
                .addEventDetail(eventDetail)
                .addSoftwareAgent(depositServicePid)
                .write();
            return;
        }

        // Check if it is a format normalization event
        Matcher matcher = NORMALIZE_FORMAT_PATTERN.matcher(eventDetail);
        if (!matcher.matches()) {
            log.error("Unknown deposit normalization event for {}, with detail: {}", pid, eventDetail);
            return;
        }

        String format = matcher.group(1);
        createEventBuilder(Premis.Ingestion, eventEl)
            .addEventDetail("ingested as format: " + format)
            .addSoftwareAgent(depositServicePid)
            .write();
    }
}
