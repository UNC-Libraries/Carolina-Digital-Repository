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

import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.ATOM_NS;
import static edu.unc.lib.dl.xml.JDOMNamespaceUtil.CDR_MESSAGE_NS;

import java.util.ArrayList;
import java.util.List;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;

import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.xml.JDOMNamespaceUtil;

/**
 * Helper methods for interacting with cdr event messages
 *
 * @author bbpennel
 */
public class CdrEventMessageHelpers {

    private CdrEventMessageHelpers() {
    }

    /**
     * Get values of all fields with the provided name, as a list.
     *
     * @param field name of the field element to list
     * @param contentBody the element in which to seek fields
     * @return list of field values
     */
    public static List<String> populateList(String field, Element contentBody) {
        return populateList(JDOMNamespaceUtil.CDR_MESSAGE_NS, field, contentBody);
    }

    public static List<String> populateList(Namespace ns, String field, Element contentBody) {
        List<Element> children = contentBody.getChildren(field, ns);

        if (children == null || children.size() == 0) {
            return null;
        }

        List<String> list = new ArrayList<>();
        for (Object node : children) {
            Element element = (Element) node;
            for (Object pid : element.getChildren()) {
                Element pidElement = (Element) pid;
                list.add(pidElement.getTextTrim());
            }
        }

        return list;
    }

    public static String extractAuthor(Document msgDoc) {
        return msgDoc.getRootElement().getChild("author", ATOM_NS)
                .getChildText("name", ATOM_NS);
    }

    public static boolean extractForceFlag(Document msgDoc) {
        return Boolean.parseBoolean(msgDoc.getRootElement()
                .getChildText("force", CDR_MESSAGE_NS));
    }

    public static PID extractPid(Document msgDoc) {
        String pidValue = msgDoc.getRootElement().getChild("pid", ATOM_NS).getTextTrim();
        return PIDs.get(pidValue);
    }
}
