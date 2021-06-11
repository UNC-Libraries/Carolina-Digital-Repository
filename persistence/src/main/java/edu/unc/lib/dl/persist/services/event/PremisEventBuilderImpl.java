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
package edu.unc.lib.dl.persist.services.event;

import java.text.MessageFormat;
import java.util.Date;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisEventBuilder;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.rdf.Prov;
import edu.unc.lib.dl.util.DateTimeUtil;

/**
 * Builder for creating a PREMIS RDF event.
 *
 * @author lfarrell
 *
 */
public class PremisEventBuilderImpl implements PremisEventBuilder {

    private PID eventPid;
    private Model model;
    private PremisLogger premisLogger;
    private Resource premisObjResc;

    public PremisEventBuilderImpl(PID eventSubject, PID eventPid, Resource eventType, Date date,
            PremisLogger premisLogger) {
        this.eventPid = eventPid;
        this.premisLogger = premisLogger;
        addEvent(eventSubject, eventType, date);
    }

    /**
     * Adds basic information required for all events
     *
     * @param eventType
     * @param date
     * @return
     */
    private PremisEventBuilder addEvent(PID eventSubject, Resource eventType, Date date) {
        Resource premisObjResc = getResource();

        Model logModel = getModel();
        Resource eventSubjectResc = logModel.getResource(eventSubject.getRepositoryPath());
        if (Premis.Ingestion.equals(eventType)
                || Premis.Creation.equals(eventType)) {
            premisObjResc.addProperty(Prov.generated, eventSubjectResc);
        } else {
            premisObjResc.addProperty(Prov.used, eventSubjectResc);
        }
        premisObjResc.addProperty(RDF.type, eventType);
        premisObjResc.addProperty(DCTerms.date,
                DateTimeUtil.formatDateToUTC(date), XSDDatatype.XSDdateTime);

        return this;
    }

    /**
     * Add an event detail property to this event
     *
     * @param message
     *            The detail message for this event.
     * @param args
     *            Optional parameters that should be formatted into the message,
     *            using String.format syntax.
     * @return this event builder
     */
    @Override
    public PremisEventBuilderImpl addEventDetail(String message, Object... args) {
        if (args != null && args.length > 0) {
            message = MessageFormat.format(message, args);
        }
        Resource premisObjResc = getResource();
        premisObjResc.addProperty(Premis.note, message);

        return this;
    }

    /**
     * Add an event outcome property
     *
     * @param success if true, the outcome will be Success, otherwise Fail
     * @return this event builder
     */
    @Override
    public PremisEventBuilder addOutcome(boolean success) {
        Resource premisObjResc = getResource();
        premisObjResc.addProperty(Premis.outcome, success ?
                Premis.Success : Premis.Fail);

        return this;
    }

    /**
     * Add a related software agent to this event
     *
     * @param agentPid PID for the agent
     * @return this event builder
     */
    @Override
    public PremisEventBuilderImpl addSoftwareAgent(PID agentPid) {
        Resource premisObjResc = getResource();
        Resource agentResc = model.createResource(agentPid.getRepositoryPath());
        premisObjResc.addProperty(Premis.hasEventRelatedAgentExecutor, agentResc);

        return this;
    }

    /**
     * Add a related authorizing agent to this event
     *
     * @param agentPid PID for the agent
     * @return this event builder
     */
    @Override
    public PremisEventBuilder addAuthorizingAgent(PID agentPid) {
        Resource premisObjResc = getResource();
        Resource agentResc = model.createResource(agentPid.getRepositoryPath());
        premisObjResc.addProperty(Premis.hasEventRelatedAgentAuthorizor, agentResc);

        return this;
    }

    /**
     * Add a related implementor agent to this event
     *
     * @param agentPid PID for the agent
     * @return this event builder
     */
    @Override
    public PremisEventBuilder addImplementorAgent(PID agentPid) {
        Resource premisObjResc = getResource();
        Resource agentResc = model.createResource(agentPid.getRepositoryPath());
        premisObjResc.addProperty(Premis.hasEventRelatedAgentImplementor, agentResc);

        return this;
    }

    /**
     * Finalize this builder by retrieving the created event resource
     *
     * @return
     */
    @Override
    public Resource create() {
        return getResource();
    }

    /**
     * Finalize this builder by pushing the built event back to the log
     *
     * @return
     */
    @Override
    public Resource write() {
        Resource resource = getResource();
        premisLogger.writeEvents(resource);
        return resource;
    }

    /**
     * Finalize this builder by pushing the built event back to the log
     * and then closing the logger
     *
     * @return
     */
    @Override
    public Resource writeAndClose() {
        Resource resc = write();
        premisLogger.close();
        return resc;
    }

    private Resource getResource() {
        if (premisObjResc != null) {
            return premisObjResc;
        }

        model = getModel();
        premisObjResc = model.createResource(eventPid.getRepositoryPath());

        return premisObjResc;
    }

    private Model getModel() {
        if (model != null) {
            return model;
        }

        return ModelFactory.createDefaultModel();
    }
}
