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

import static edu.unc.lib.dl.services.RunEnhancementsMessageHelpers.makeEnhancementOperationBody;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.ATOM_NS;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.CDR_MESSAGE_NS;

import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.jdom2.Document;
import org.jdom2.Element;

import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.services.MessageSender;

/**
 * Processor which handles actions stemming from an "Add" event of newly ingested
 * objects.
 *
 * @author bbpennel
 */
public class AddEventProcessor implements Processor {
    private MessageSender messageSender;

    @Override
    public void process(Exchange exchange) throws Exception {
        final Message in = exchange.getIn();
        Document document = (Document) in.getBody();

        String author = CdrEventMessageHelpers.extractAuthor(document);
        boolean force = CdrEventMessageHelpers.extractForceFlag(document);

        Element addEl = document.getRootElement()
                .getChild("content", ATOM_NS)
                .getChild("add", CDR_MESSAGE_NS);
        List<String> subjects = CdrEventMessageHelpers.populateList(
                "subjects", addEl);

        if (subjects != null) {
            // Submit run enhancement messages for each added object
            for (String subject: subjects) {
                PID subjPid = PIDs.get(subject);
                Document body = makeEnhancementOperationBody(author, subjPid, null, force);
                messageSender.sendMessage(body);
            }
        }
    }

    public void setMessageSender(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

}
