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

import org.apache.camel.builder.RouteBuilder;

/**
 * Meta router which directs event messages to .
 *
 * @author bbpennel
 *
 */
public class MetaServicesRouter extends RouteBuilder {
    @Override
    public void configure() throws Exception {
        from("{{fcrepo.stream}}")
            .routeId("CdrMetaServicesRouter")
            .to("direct-vm:index.start");
    }
}
