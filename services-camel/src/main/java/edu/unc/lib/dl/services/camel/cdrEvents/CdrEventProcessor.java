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

import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrSolrUpdateAction;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.ATOM_NS;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.jdom2.Document;

import edu.unc.lib.dl.services.camel.util.MessageUtil;

/**
 * Processes CDR Events, extracting the body and headers
 *
 * @author lfarrell
 *
 */
public class CdrEventProcessor implements Processor {
    @Override
    public void process(Exchange exchange) throws Exception {
        final Message in = exchange.getIn();
        Document document = MessageUtil.getDocumentBody(in);
        if (document == null) {
            return;
        }

        String actionType = document.getRootElement().getChildTextTrim("title", ATOM_NS);
        if (actionType == null) {
            in.setHeader(CdrSolrUpdateAction, null);
            return;
        }
        in.setHeader(CdrSolrUpdateAction, actionType);

        // Pass the body document along for future processors
        in.setBody(document);
    }

}