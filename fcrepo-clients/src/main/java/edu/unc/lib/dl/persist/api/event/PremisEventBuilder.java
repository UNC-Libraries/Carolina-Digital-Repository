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
package edu.unc.lib.dl.persist.api.event;

import org.apache.jena.rdf.model.Resource;

import edu.unc.lib.dl.fedora.PID;

/**
 * Builder for creating a PREMIS events.
 *
 * @author bbpennel
 */
public interface PremisEventBuilder {

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
    PremisEventBuilder addEventDetail(String message, Object... args);

    /**
     * Add an event outcome property
     *
     * @param success if true, the outcome will be Success, otherwise Fail
     * @return this event builder
     */
    PremisEventBuilder addOutcome(boolean success);

    /**
     * Add a related software agent to this event
     *
     * @param agentPid PID for the agent
     * @return this event builder
     */
    PremisEventBuilder addSoftwareAgent(PID agentPid);

    /**
     * Add a related authorizing agent to this event
     *
     * @param agentPid PID for the agent
     * @return this event builder
     */
    PremisEventBuilder addAuthorizingAgent(PID agentPid);

    /**
     * Add a related implementor agent to this event
     *
     * @param agentPid PID for the agent
     * @return this event builder
     */
    PremisEventBuilder addImplementorAgent(PID agentPid);

    /**
     * Finalize this builder by retrieving the created event resource
     *
     * @return
     */
    Resource create();

    /**
     * Finalize this builder by pushing the built event back to the log
     *
     * @return
     */
    Resource write();

    /**
     * Finalize this builder by pushing the built event back to the log
     * and then closing the logger
     *
     * @return
     */
    Resource writeAndClose();

}