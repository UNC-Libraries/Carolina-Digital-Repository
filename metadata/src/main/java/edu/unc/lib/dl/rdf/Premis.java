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
package edu.unc.lib.dl.rdf;

import static org.apache.jena.rdf.model.ResourceFactory.createProperty;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

/**
 * Vocabulary definitions from rdf-schemas/premis.rdf
 * @author Auto-generated by schemagen on 21 Apr 2016 12:16
 */
public class Premis {
    private Premis() {

    }

/** The namespace of the vocabulary as a string */
    public static final String NS = "http://www.loc.gov/premis/rdf/v1#";

/** The namespace of the vocabulary as a string
 *  @see #NS */
    public static String getURI() {
        return NS; }

/** The namespace of the vocabulary as a resource */
    public static final Resource NAMESPACE = createResource(NS);

/** The ontology's owl:versionInfo as a string */
    public static final String VERSION_INFO = "version 2.2.1";

/** The Agent entity aggregates information about attributes or characteristics
 *  of agents (persons, organizations, or software) associated with rights management
 *  and preservation events in the life of a data object. Agent information serves
 *  to identify an agent unambiguously from all other Agent entities.
 */
    public static final Resource Agent = createResource("http://www.loc.gov/premis/rdf/v1#Agent");

    public static final Property hasAgentName = createProperty("http://www.loc.gov/premis/rdf/v1#hasAgentName");

    public static final Property hasAgentType = createProperty("http://www.loc.gov/premis/rdf/v1#hasAgentType");

    public static final Resource ApplicableDates = createResource("http://www.loc.gov/premis/rdf/v1#ApplicableDates");

    public static final Resource Bitstream = createResource("http://www.loc.gov/premis/rdf/v1#Bitstream");

    public static final Resource ContentLocation = createResource("http://www.loc.gov/premis/rdf/v1#ContentLocation");

    public static final Resource CopyrightInformation = createResource(
            "http://www.loc.gov/premis/rdf/v1#CopyrightInformation");

    public static final Resource CreatingApplication = createResource(
"http://www.loc.gov/premis/rdf/v1#CreatingApplication");

    public static final Resource Dependency = createResource("http://www.loc.gov/premis/rdf/v1#Dependency");

    public static final Resource Environment = createResource("http://www.loc.gov/premis/rdf/v1#Environment");

    /** The Event entity aggregates information about an action that involves one
     *  or more Object entities. Metadata about an Event would normally be recorded
     *  and stored separately from the digital object. Whether or not a preservation
     *  repository records an Event depends upon the importance of the event. Actions
     *  that modify objects should always be recorded. Other actions such as copying
     *  an object for backup purposes may be recorded in system logs or an audit trail
     *  but not necessarily in an Event entity. Mandatory semantic units are: eventIdentifier,
     *  eventType, and eventDateTime.
     */
    public static final Property hasEvent = createProperty("http://www.loc.gov/premis/rdf/v1#hasEvent");

    public static final Property hasEventDateTime = createProperty("http://www.loc.gov/premis/rdf/v1#hasEventDateTime");

    public static final Property hasEventDetail = createProperty("http://www.loc.gov/premis/rdf/v1#hasEventDetail");

    public static final Property hasEventOutcomeDetail = createProperty(
            "http://www.loc.gov/premis/rdf/v1#hasEventOutcomeDetail");

    public static final Property hasEventOutcomeDetailNote = createProperty(
            "http://www.loc.gov/premis/rdf/v1#hasEventOutcomeDetailNote");

    public static final Property hasEventOutcomeInformation = createProperty(
            "http://www.loc.gov/premis/rdf/v1#hasEventOutcomeInformation");

    public static final Property hasEventRelatedAgent = createProperty(
            "http://www.loc.gov/premis/rdf/v1#hasEventRelatedAgent");

    public static final Property hasEventRelatedAgentExecutor = createProperty(
            "http://id.loc.gov/vocabulary/preservation/eventRelatedAgentRole/exe");

    public static final Property hasEventRelatedAgentAuthorizor = createProperty(
            "http://id.loc.gov/vocabulary/preservation/eventRelatedAgentRole/aut");

    public static final Property hasEventRelatedAgentImplementor = createProperty(
            "http://id.loc.gov/vocabulary/preservation/eventRelatedAgentRole/imp");

    public static final Property hasEventRelatedObject = createProperty(
"http://www.loc.gov/premis/rdf/v1#hasEventRelatedObject");

    public static final Property hasEventType = createProperty("http://www.loc.gov/premis/rdf/v1#hasEventType");

    public static final Property hasFixity = createProperty("http://www.loc.gov/premis/rdf/v1#hasFixity");

    public static final Property hasMessageDigest = createProperty("http://www.loc.gov/premis/rdf/v1#hasMessageDigest");

    public static final Property hasOriginalName = createProperty("http://www.loc.gov/premis/rdf/v1#hasOriginalName");

    public static final Property hasSize = createProperty("http://www.loc.gov/premis/rdf/v1#hasSize");

    public static final Resource File = createResource("http://www.loc.gov/premis/rdf/v1#File");

    public static final Resource Fixity = createResource("http://www.loc.gov/premis/rdf/v1#Fixity");

    public static final Resource Format = createResource("http://www.loc.gov/premis/rdf/v1#Format");

    public static final Resource FormatDesignation = createResource(
            "http://www.loc.gov/premis/rdf/v1#FormatDesignation");

    public static final Resource FormatRegistry = createResource("http://www.loc.gov/premis/rdf/v1#FormatRegistry");

    public static final Resource Hardware = createResource("http://www.loc.gov/premis/rdf/v1#Hardware");

/** This class is used in PREMIS OWL to describe identifiers if the identifiers
 *  are not http URIs.
 */
    public static final Resource Identifier = createResource("http://www.loc.gov/premis/rdf/v1#Identifier");

    public static final Resource Inhibitors = createResource("http://www.loc.gov/premis/rdf/v1#Inhibitors");

    /** Intellectual entities are described via Descriptive metadata models. These
     *  are very domain-specific and are out of scope for PREMIS. Examples: Dublin
     *
     *  Core, Mets, MARC
     */
    public static final Resource IntellectualEntity = createResource(
            "http://www.loc.gov/premis/rdf/v1#IntellectualEntity");

    public static final Resource LicenseInformation = createResource(
            "http://www.loc.gov/premis/rdf/v1#LicenseInformation");

    /** The object class aggregates information about a digital object held by a preservation
     *  repository and describes those characteristics relevant to preservation management.
     *  The only mandatory property is objectIdentifier. The object class has three
     *  subclasses: Representation, File, and Bitstream.
     */
    public static final Resource Object = createResource("http://www.loc.gov/premis/rdf/v1#Object");

    public static final Resource ObjectCharacteristics = createResource(
            "http://www.loc.gov/premis/rdf/v1#ObjectCharacteristics");

    public static final Resource PremisEntity = createResource("http://www.loc.gov/premis/rdf/v1#PremisEntity");

    public static final Resource PreservationLevel = createResource(
            "http://www.loc.gov/premis/rdf/v1#PreservationLevel");

    public static final Resource RelatedObjectIdentification = createResource(
            "http://www.loc.gov/premis/rdf/v1#RelatedObjectIdentification");

    public static final Resource Representation = createResource("http://www.loc.gov/premis/rdf/v1#Representation");

    public static final Resource RightsDocumentation = createResource(
            "http://www.loc.gov/premis/rdf/v1#RightsDocumentation");

    public static final Resource RightsGranted = createResource("http://www.loc.gov/premis/rdf/v1#RightsGranted");

    /** Extensions: In OWL one can define its own subclasses to the the RightsStatement
     *  class to denote OtherRightsInformation of the PREMIS data dictionary.
     */
    public static final Resource RightsStatement = createResource("http://www.loc.gov/premis/rdf/v1#RightsStatement");

    public static final Resource Signature = createResource("http://www.loc.gov/premis/rdf/v1#Signature");

    public static final Resource SignificantProperties = createResource(
            "http://www.loc.gov/premis/rdf/v1#SignificantProperties");

    public static final Resource StatuteInformation = createResource(
            "http://www.loc.gov/premis/rdf/v1#StatuteInformation");

    public static final Resource Storage = createResource("http://www.loc.gov/premis/rdf/v1#Storage");

    public static final Resource TermOfGrant = createResource("http://www.loc.gov/premis/rdf/v1#TermOfGrant");

    public static final Resource TermOfRestriction = createResource(
            "http://www.loc.gov/premis/rdf/v1#TermOfRestriction");

    /**
     * Premis event types
     */
    public static final Resource Accession = createResource("http://id.loc.gov/vocabulary/preservation/eventType/acc");

    public static final Resource Capture = createResource("http://id.loc.gov/vocabulary/preservation/eventType/cap");

    public static final Resource Compression = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/com");

    public static final Resource Creation = createResource("http://id.loc.gov/vocabulary/preservation/eventType/cre");

    public static final Resource Deaccession = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/dea");

    public static final Resource Decompression = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/dec");

    public static final Resource Decryption = createResource("http://id.loc.gov/vocabulary/preservation/eventType/der");

    public static final Resource Deletion = createResource("http://id.loc.gov/vocabulary/preservation/eventType/del");

    public static final Resource DigitalSignatureValidation = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/dig");

    public static final Resource Dissemination = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/dis");

    public static final Resource Execution = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/exe");

    public static final Resource FilenameChange = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/fil");

    public static final Resource FixityCheck = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/fix");

    public static final Resource Ingestion = createResource("http://id.loc.gov/vocabulary/preservation/eventType/ing");

    public static final Resource MessageDigestCalculation = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/mes");

    public static final Resource MetadataModification = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/mem");

    public static final Resource Migration = createResource("http://id.loc.gov/vocabulary/preservation/eventType/mig");

    public static final Resource Normalization = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/nor");

    public static final Resource PolicyAssignment = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/poa");

    public static final Resource Replication = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/rep");

    public static final Resource Validation = createResource("http://id.loc.gov/vocabulary/preservation/eventType/val");

    public static final Resource VirusCheck = createResource("http://id.loc.gov/vocabulary/preservation/eventType/vir");

    public static final Resource InformationPackageCreation = createResource(
            "http://id.loc.gov/vocabulary/preservation/eventType/ipc");

    public static final Resource Event = createResource("http://www.loc.gov/premis/rdf/v1#Event");

    public static final Property note = createProperty("http://www.loc.gov/premis/rdf/v3/note");

    public static final Property outcome = createProperty("http://www.loc.gov/premis/rdf/v3/outcome");

    public static final Resource Fail = createResource("http://id.loc.gov/vocabulary/preservation/eventOutcome/fai");

    public static final Resource Success = createResource("http://id.loc.gov/vocabulary/preservation/eventOutcome/suc");
}
