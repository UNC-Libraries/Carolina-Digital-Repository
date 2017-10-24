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

import static edu.unc.lib.dl.services.camel.headers.CdrFcrepoHeaders.CdrSolrUpdateAction;

import org.apache.camel.BeanInject;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

import edu.unc.lib.dl.services.camel.solr.processor.CdrEventToSolrUpdateProcessor;
import edu.unc.lib.dl.util.JMSMessageUtil.CDRActions;

/**
 * Router which listens to and processes CDR Event messages
 *
 * @author lfarrell
 *
 */
public class CdrEventRouter extends RouteBuilder {
    @BeanInject(value = "cdrEventProcessor")
    private CdrEventProcessor cdrEventProcessor;

    @BeanInject(value = "cdrEventToSolrUpdateProcessor")
    private CdrEventToSolrUpdateProcessor cdrEventToSolrUpdateProcessor;

    @Override
    public void configure() throws Exception {
        onException(Exception.class)
        .redeliveryDelay("{{error.retryDelay}}")
        .maximumRedeliveries("{{error.maxRedeliveries}}")
        .backOffMultiplier(2)
        .retryAttemptedLogLevel(LoggingLevel.WARN);

        from("{{cdr.stream.camel}}")
            .routeId("CdrServiceCdrEvents")
            .log(LoggingLevel.DEBUG, "CDR Event Message received")
            .bean(cdrEventProcessor)
            .filter(simple("${headers[" + CdrSolrUpdateAction + "]} contains '" + CDRActions.MOVE.getName() + "'"
                    + " || ${headers[" + CdrSolrUpdateAction + "]} contains '" + CDRActions.REMOVE.getName() + "'"
                    + " || ${headers[" + CdrSolrUpdateAction + "]} contains '" + CDRActions.ADD.getName() + "'"
                    + " || ${headers[" + CdrSolrUpdateAction + "]} contains '" + CDRActions.REORDER.getName() + "'"
                    + " || ${headers[" + CdrSolrUpdateAction + "]} contains '" + CDRActions.PUBLISH.getName() + "'"
                    + " || ${headers[" + CdrSolrUpdateAction + "]} contains '" + CDRActions.REINDEX.getName() + "'"
                    + " || ${headers[" + CdrSolrUpdateAction + "]} contains '" + CDRActions.INDEX.getName() + "'"
                    + " || ${headers[" + CdrSolrUpdateAction + "]} contains '" + CDRActions.EDIT_TYPE.getName() + "'"))
            .to("direct:solr-update");

        from("direct:solr-update")
            .routeId("CdrServiceCdrEventToSolrUpdateProcessor")
            .log(LoggingLevel.DEBUG, "Updating solr index for ${headers[org.fcrepo.jms.identifier]}")
            .bean(cdrEventToSolrUpdateProcessor);
    }
}
