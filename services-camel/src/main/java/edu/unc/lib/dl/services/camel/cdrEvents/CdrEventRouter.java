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

package edu.unc.lib.dl.services.camel.cdrEvents;

import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrUpdateAction;
import static edu.unc.lib.dl.util.JMSMessageUtil.CDRActions.ADD;
import static edu.unc.lib.dl.util.JMSMessageUtil.CDRActions.EDIT_ACCESS_CONTROL;
import static edu.unc.lib.dl.util.JMSMessageUtil.CDRActions.EDIT_TYPE;
import static edu.unc.lib.dl.util.JMSMessageUtil.CDRActions.MARK_FOR_DELETION;
import static edu.unc.lib.dl.util.JMSMessageUtil.CDRActions.MOVE;
import static edu.unc.lib.dl.util.JMSMessageUtil.CDRActions.REMOVE;
import static edu.unc.lib.dl.util.JMSMessageUtil.CDRActions.RESTORE_FROM_DELETION;
import static edu.unc.lib.dl.util.JMSMessageUtil.CDRActions.SET_AS_PRIMARY_OBJECT;
import static edu.unc.lib.dl.util.JMSMessageUtil.CDRActions.UPDATE_DESCRIPTION;
import static java.util.Arrays.asList;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.List;

import org.apache.camel.BeanInject;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;

import edu.unc.lib.dl.services.camel.solr.CdrEventToSolrUpdateProcessor;

/**
 * Router which listens to and processes CDR Event messages
 *
 * @author lfarrell
 *
 */
public class CdrEventRouter extends RouteBuilder {
    private static final Logger log = getLogger(CdrEventRouter.class);

    @BeanInject(value = "cdrEventProcessor")
    private CdrEventProcessor cdrEventProcessor;

    @BeanInject(value = "cdrEventToSolrUpdateProcessor")
    private CdrEventToSolrUpdateProcessor cdrEventToSolrUpdateProcessor;

    private static final List<String> solrAllowedActions = asList(ADD.toString(), EDIT_ACCESS_CONTROL.toString(),
            EDIT_TYPE.toString(), MARK_FOR_DELETION.toString(), MOVE.toString(), REMOVE.toString(),
            RESTORE_FROM_DELETION.toString(), SET_AS_PRIMARY_OBJECT.toString(), UPDATE_DESCRIPTION.toString());

    private static final String solrAllowed = String.join(",", solrAllowedActions);

    @Override
    public void configure() throws Exception {
        onException(Exception.class)
                .redeliveryDelay("{{error.retryDelay}}")
                .maximumRedeliveries("{{error.maxRedeliveries}}")
                .backOffMultiplier("{{error.backOffMultiplier}}")
                .retryAttemptedLogLevel(LoggingLevel.WARN);

        from("{{cdr.stream.camel}}")
            .routeId("CdrServiceCdrEvents")
            .startupOrder(2)
            .log(LoggingLevel.DEBUG, log, "CDR Event Message received ${headers[" + CdrUpdateAction + "]}")
            .bean(cdrEventProcessor)
            .filter(simple("${headers[" + CdrUpdateAction + "]} in '" + solrAllowed + "'"))
            .to("direct:solr-update");

        from("direct:solr-update")
            .routeId("CdrServiceCdrEventToSolrUpdateProcessor")
            .startupOrder(1)
            .log(LoggingLevel.DEBUG, log, "Updating solr index for ${headers[org.fcrepo.jms.identifier]}")
            .bean(cdrEventToSolrUpdateProcessor);
    }
}
