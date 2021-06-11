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

import static edu.unc.lib.dcr.migration.premis.Premis2Constants.CREATION_TYPE;
import static edu.unc.lib.dcr.migration.premis.Premis2Constants.DELETION_TYPE;
import static edu.unc.lib.dcr.migration.premis.Premis2Constants.FIXITY_CHECK_TYPE;
import static edu.unc.lib.dcr.migration.premis.Premis2Constants.INGESTION_TYPE;
import static edu.unc.lib.dcr.migration.premis.Premis2Constants.MIGRATION_TYPE;
import static edu.unc.lib.dcr.migration.premis.Premis2Constants.NORMALIZATION_TYPE;
import static edu.unc.lib.dcr.migration.premis.Premis2Constants.REPLICATION_TYPE;
import static edu.unc.lib.dcr.migration.premis.Premis2Constants.VALIDATION_TYPE;
import static edu.unc.lib.dcr.migration.premis.Premis2Constants.VIRUS_CHECK_TYPE;
import static edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent.clamav;
import static edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent.curatorsWorkbench;
import static edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent.depositService;
import static edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent.embargoUpdateService;
import static edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent.servicesAPI;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.PREMIS_V2_NS;
import static org.slf4j.LoggerFactory.getLogger;

import java.nio.file.Path;
import java.util.List;

import org.apache.jena.rdf.model.Resource;
import org.jdom2.Document;
import org.jdom2.Element;
import org.slf4j.Logger;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisEventBuilder;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;

/**
 * Transforms a PREMIS event log for a content object
 *
 * @author bbpennel
 */
public class ContentPremisToRdfTransformer extends AbstractPremisToRdfTransformer {

    private static final long serialVersionUID = 1L;

    private static final Logger log = getLogger(ContentPremisToRdfTransformer.class);

    public ContentPremisToRdfTransformer(PID pid, PremisLogger premisLogger, Document doc) {
        super(pid, premisLogger, doc);
    }

    public ContentPremisToRdfTransformer(PID pid, PremisLogger premisLogger, Path docPath) {
        super(pid, premisLogger, docPath);
    }

    @Override
    public void compute() {
        List<Element> eventEls = getDocument().getRootElement().getChildren("event", PREMIS_V2_NS);

        for (Element eventEl: eventEls) {
            List<String> eventTypes = getEventTypes(eventEl);
            if (eventTypes.isEmpty()) {
                log.error("No type present on event for object {}", pid);
                continue;
            }

            if (eventTypes.contains(VIRUS_CHECK_TYPE)) {
                addVirusCheckEvent(eventEl);
            } else if (eventTypes.contains(VALIDATION_TYPE)) {
                transformValidationEvent(eventEl);
            } else if (eventTypes.contains(INGESTION_TYPE)) {
                addIngestionEvent(eventEl);
            } else if (eventTypes.contains(NORMALIZATION_TYPE)) {
                transformNormalizationEvent(eventEl);
            } else if (eventTypes.contains(MIGRATION_TYPE)) {
                transformMigrationEvent(eventEl);
            } else if (eventTypes.contains(CREATION_TYPE)) {
                transformCreationEvent(eventEl);
            } else if (eventTypes.contains(DELETION_TYPE)) {
                transformDeletionEvent(eventEl);
            } else if (eventTypes.contains(REPLICATION_TYPE)) {
                transformReplicationEvent(eventEl);
            } else if (eventTypes.contains(FIXITY_CHECK_TYPE)) {
                transformFixityEvent(eventEl);
            } else {
                log.error("Unknown event types for {}: {}", pid, eventTypes);
            }
        }
    }

    private void addVirusCheckEvent(Element eventEl) {
        // Current virus scan event
        addEvent(eventEl, Premis.VirusCheck, getEventDetail(eventEl), clamav);
    }

    /**
     * Only validation event expected for content objects at the moment is old virus scans
     *
     * @param eventEl
     */
    private void transformValidationEvent(Element eventEl) {
        String eventDetail = getEventDetail(eventEl);
        if (eventDetail == null) {
            log.error("No event details for validation event on {}", pid);
            return;
        }

        if (eventDetail.contains("MODS") || eventDetail.contains("Updated through UI")) {
            log.info("Ignoring MODS validation event on object {}", pid);
            return;
        }
        if (eventDetail.contains("METS")) {
            log.info("Ignoring METS validation event on object {}", pid);
            return;
        }

        if (eventDetail.contains("virus scan")) {
            // Old virus scan event
            addEvent(eventEl, Premis.VirusCheck, "File passed pre-ingest scan for viruses.", clamav);
            return;
        }

        log.error("Unknown content validation event for {}, with detail: {}", pid, eventDetail);
    }

    private void transformNormalizationEvent(Element eventEl) {
        String eventDetail = getEventDetail(eventEl);
        if (eventDetail == null) {
            log.error("No event details for normalization event on {}", pid);
            return;
        }

        if (eventDetail.contains("transformed into Dublin Core")) {
            log.info("Ignoring MODS to DC transformation on {}", pid);
            return;
        }
        if (eventDetail.contains("assigned persistently unique Fedora PID")) {
            log.info("Ignoring PID assignment event on {}", pid);
            return;
        }

        // PID assignment normalization jobs become info package creation
        if (eventDetail.contains("Assigned PID to object defined in a METS div")) {
            addEvent(eventEl, Premis.InformationPackageCreation, eventDetail, depositService);
            return;
        }

        log.error("Unknown content normalization event for {}, with detail: {}", pid, eventDetail);
    }

    private void addIngestionEvent(Element eventEl) {
        String eventDetail = getEventDetail(eventEl);
        if (eventDetail == null) {
            log.error("No event details for ingestion event on {}", pid);
            return;
        }
        if (eventDetail.contains("Updated through UI")) {
            log.debug("Ignoring ingestion event with detail: {}", eventDetail);
            return;
        }
        if (!(eventDetail.contains("ingested as PID") || eventDetail.matches("added .* child object.*"))) {
            log.error("Unknown ingestion event for {}, with detail: {}", pid, eventDetail);
            return;
        }

        // ingested as PID
        addEvent(eventEl, Premis.Ingestion, eventDetail, depositService);
    }

    private void transformMigrationEvent(Element eventEl) {
        String eventDetail = getEventDetail(eventEl);
        if (eventDetail == null) {
            log.error("No event details for migration event on {}", pid);
            return;
        }

        String agent = getLinkingAgent(eventEl);
        boolean isSoftwareAgent = agent.contains("uuid:");
        if (isSoftwareAgent) {
            agent = servicesAPI.getFullname();
        }

        // Move operations and type changes
        if (eventDetail.contains("Changed resource type") || eventDetail.contains("object moved from")) {
            if (isSoftwareAgent) {
                addEvent(eventEl, Premis.MetadataModification, eventDetail, servicesAPI);
            } else {
                addEvent(eventEl, Premis.MetadataModification, eventDetail, agent);
            }
            return;
        }
        // Rename representation 1
        if (eventDetail.contains("Object renamed to")) {
            addEvent(eventEl, Premis.FilenameChange, eventDetail, servicesAPI);
            return;
        }
        // Rename representation 2
        String eventOutcome = getEventOutcomeDetailNote(eventEl);
        if (eventOutcome != null && eventOutcome.contains("Object renamed successfully")) {
            eventDetail = eventDetail.replaceFirst("Object", "Object renamed to");
            addEvent(eventEl, Premis.FilenameChange, eventDetail, servicesAPI);
            return;
        }

        if (eventDetail.contains("Embargo expiration")) {
            addEvent(eventEl, Premis.Dissemination, eventDetail, embargoUpdateService);
            return;
        }

        log.error("Unknown content migration event for {}, with detail: {}", pid, eventDetail);
    }

    private void transformDeletionEvent(Element eventEl) {
        String eventDetail = getEventDetail(eventEl);
        if (eventDetail == null) {
            log.error("No event details for deletion event on {}", pid);
            return;
        }

        // Destroy event
        if (eventDetail.matches("Deleted .*contained object.*")) {
            addEvent(eventEl, Premis.Deletion, eventDetail, servicesAPI);
            return;
        }

        log.error("Unknown content deletion event for {}, with detail: {}", pid, eventDetail);
    }

    private void transformCreationEvent(Element eventEl) {
        String eventDetail = getEventDetail(eventEl);
        if (eventDetail == null) {
            log.error("No event details for migration event on {}", pid);
            return;
        }

        // Container creation via the API
        if (eventDetail.contains("Container created")) {
            String agent = getLinkingAgent(eventEl);
            addEvent(eventEl, Premis.Creation, eventDetail, agent);
            return;
        }
        // From the time before deposit records, SIP creation was recorded here
        if (eventDetail.contains("SIP created")) {
            PremisEventBuilder builder = createEventBuilder(Premis.InformationPackageCreation, eventEl)
                .addEventDetail(eventDetail);
            getLinkingAgents(eventEl).forEach(agent -> {
                if (agent.equals("CDR Workbench")) {
                    builder.addSoftwareAgent(AgentPids.forSoftware(curatorsWorkbench));
                } else {
                    builder.addAuthorizingAgent(AgentPids.forPerson(agent));
                }
            });
            builder.write();
            return;
        }

        log.error("Unknown content migration event for {}, with detail: {}", pid, eventDetail);
    }

    private void transformReplicationEvent(Element eventEl) {
        String eventOutcome = getEventOutcomeDetailNote(eventEl);
        if (eventOutcome == null || !eventOutcome.contains("object was not found on the resource")) {
            log.error("Unknown replication event for {}, with detail: {}", pid, eventOutcome);
            return;
        }

        addFixityEvent(eventEl, eventOutcome);
    }

    private void transformFixityEvent(Element eventEl) {
        String eventOutcome = getEventOutcomeDetailNote(eventEl);
        if (eventOutcome == null || !eventOutcome.contains("The checksum of the object on the resource")) {
            log.error("Unknown fixity event for {}, with detail: {}", pid, eventOutcome);
            return;
        }

        addFixityEvent(eventEl, eventOutcome);
    }

    private void addFixityEvent(Element eventEl, String eventOutcome) {
        PremisEventBuilder builder = createEventBuilder(Premis.FixityCheck, eventEl)
                .addEventDetail(eventOutcome);

        eventEl.getChildren("linkingAgentIdentifier", PREMIS_V2_NS).forEach(agentEl -> {
            String idType = agentEl.getChildTextTrim("linkingAgentIdentifierType", PREMIS_V2_NS);
            String idVal = agentEl.getChildTextTrim("linkingAgentIdentifierValue", PREMIS_V2_NS);
            builder.addSoftwareAgent(AgentPids.forSoftware(idType + " " + idVal));
        });
        builder.addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.fixityCheckingService));

        eventEl.getChildren("linkingObjectIdentifier", PREMIS_V2_NS).stream().forEach(objEl -> {
            String objectType = objEl.getChildTextTrim("linkingObjectIdentifierType", PREMIS_V2_NS);
            String objectPath = objEl.getChildTextTrim("linkingObjectIdentifierValue", PREMIS_V2_NS);
            builder.addEventDetail(objectType + " " + objectPath);
        });

        builder.write();
    }

    private void addEvent(Element eventEl, Resource eventType, String eventDetail, SoftwareAgent agent) {
        PremisEventBuilder builder = createEventBuilder(eventType, eventEl)
                .addEventDetail(eventDetail);
        builder.addSoftwareAgent(AgentPids.forSoftware(agent));
        builder.write();
    }

    private void addEvent(Element eventEl, Resource eventType, String eventDetail, String agentName) {
        PremisEventBuilder builder = createEventBuilder(eventType, eventEl)
            .addEventDetail(eventDetail);
        builder.addImplementorAgent(AgentPids.forPerson(agentName));
        builder.write();
    }
}
