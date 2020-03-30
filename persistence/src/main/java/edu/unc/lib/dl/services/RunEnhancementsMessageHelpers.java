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
package edu.unc.lib.dl.services;

import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.ATOM_NS;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.CDR_MESSAGE_NS;

import org.jdom2.Document;
import org.jdom2.Element;

import edu.unc.lib.dl.fedora.PID;

/**
 * @author bbpennel
 */
public class RunEnhancementsMessageHelpers {

    private RunEnhancementsMessageHelpers() {
    }

    /**
     * Generate the body for a run enhancement request message
     *
     * @param userid
     * @param filePath
     * @param mimeType
     * @param force
     * @return
     */
    public static Document makeEnhancementOperationBody(String userid, PID pid, String mimeType, Boolean force) {
        Document msg = new Document();
        Element entry = new Element("entry", ATOM_NS);
        entry.addContent(new Element("author", ATOM_NS)
                .addContent(new Element("name", ATOM_NS).setText(userid)));
        entry.addContent(new Element("pid", ATOM_NS).setText(pid.getRepositoryPath()));
        entry.addContent(new Element("mimeType", ATOM_NS).setText(mimeType));

        if (force) {
            Element paramForce = new Element("force", CDR_MESSAGE_NS);
            paramForce.setText("true");
        }
        msg.addContent(entry);

        return msg;
    }
}
