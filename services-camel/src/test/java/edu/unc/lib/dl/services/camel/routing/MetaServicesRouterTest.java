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
package edu.unc.lib.dl.services.camel.routing;

import static edu.unc.lib.dl.rdf.Fcrepo4Repository.Binary;
import static edu.unc.lib.dl.services.camel.JmsHeaderConstants.EVENT_TYPE;
import static edu.unc.lib.dl.services.camel.JmsHeaderConstants.IDENTIFIER;
import static edu.unc.lib.dl.services.camel.JmsHeaderConstants.RESOURCE_TYPE;
import static edu.unc.lib.dl.services.camel.util.EventTypes.EVENT_CREATE;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.EndpointInject;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.PropertyInject;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.CamelSpringTestSupport;
import org.junit.Test;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 *
 * @author bbpennel
 * @author lfarrell
 *
 */
public class MetaServicesRouterTest extends CamelSpringTestSupport {

    private static final String CONTAINER_ID = "/content/43/e2/27/ac/43e227ac-983a-4a18-94c9-c9cff8d28441";

    private static final String META_ROUTE = "CdrMetaServicesRouter";

    @PropertyInject(value = "fcrepo.baseUri")
    private static String baseUri;

    @EndpointInject(uri = "mock:fcrepo")
    private MockEndpoint resultEndpoint;

    @Produce(uri = "direct:start")
    private ProducerTemplate template;

    @Override
    protected AbstractApplicationContext createApplicationContext() {
        return new ClassPathXmlApplicationContext("/service-context.xml", "/metaservices-context.xml");
    }

    @Test
    public void testRouteStartContainer() throws Exception {
        getMockEndpoint("mock:direct-vm:index.start").expectedMessageCount(1);

        createContext(META_ROUTE);

        template.sendBodyAndHeaders("", createEvent(CONTAINER_ID, Binary.getURI()));

        assertMockEndpointsSatisfied();
    }

    private void createContext(String routeName) throws Exception {
        context.getRouteDefinition(routeName).adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                replaceFromWith("direct:start");
                mockEndpointsAndSkip("*");
            }
        });

        context.start();
    }

    private static Map<String, Object> createEvent(final String identifier, final String... type) {

        final Map<String, Object> headers = new HashMap<>();
        headers.put(EVENT_TYPE, EVENT_CREATE);
        headers.put(IDENTIFIER, identifier);
        headers.put(RESOURCE_TYPE, String.join(",", type));

        return headers;
    }
}
