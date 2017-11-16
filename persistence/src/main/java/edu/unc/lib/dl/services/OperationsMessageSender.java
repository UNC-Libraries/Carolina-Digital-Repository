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
import static edu.unc.lib.dl.xml.NamespaceConstants.CDR_MESSAGE_AUTHOR_URI;

import java.util.Collection;
import java.util.UUID;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Session;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.XMLOutputter;
import org.joda.time.DateTimeUtils;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.util.IndexingActionType;
import edu.unc.lib.dl.util.JMSMessageUtil.CDRActions;
import edu.unc.lib.dl.util.ResourceType;

/**
 * Constructs and sends JMS messages describing CDR operations.
 *
 * @author Gregory Jansen
 * @author bbpennel
 *
 */
public class OperationsMessageSender {
    private static final Logger LOG = LoggerFactory.getLogger(OperationsMessageSender.class);

    private JmsTemplate jmsTemplate = null;

    /**
     * Sends a Add operation message, indicating that objects were added to destination containers.
     *
     * @param userid id of user who triggered the operation
     * @param destinations containers objects were added to
     * @param added objects added
     * @param reordered reordered (optional)
     * @param depositId id of deposit (optional)
     * @return id of operation message
     */
    public String sendAddOperation(String userid, Collection<PID> destinations, Collection<PID> added,
            Collection<PID> reordered, String depositId) {
        Element contentEl = createAtomEntry(userid, destinations.iterator().next(), CDRActions.ADD);

        Element add = new Element("add", CDR_MESSAGE_NS);
        contentEl.addContent(add);

        if (depositId != null) {
            add.addContent(new Element("depositId", CDR_MESSAGE_NS).setText(depositId));
        }

        for (PID destination : destinations) {
            add.addContent(new Element("parent", CDR_MESSAGE_NS).setText(destination.toString()));
        }

        Element subjects = new Element("subjects", CDR_MESSAGE_NS);
        add.addContent(subjects);
        for (PID sub : added) {
            subjects.addContent(new Element("pid", CDR_MESSAGE_NS).setText(sub.toString()));
        }

        if (reordered != null) {
            Element reorderedEl = new Element("reordered", CDR_MESSAGE_NS);
            add.addContent(reorderedEl);
            for (PID re : reordered) {
                reorderedEl.addContent(new Element("pid", CDR_MESSAGE_NS).setText(re.toString()));
            }
        }

        Document msg = contentEl.getDocument();
        sendMessage(msg);
        LOG.debug("sent add operation JMS message using JMS template: {}", this.getJmsTemplate());

        return getMessageId(msg);
    }

    /**
     * Sends a Remove operation message, indicating that objects are being removed from the repository
     *
     * @param userid id of user who triggered the operation
     * @param destinations containers objects were added to
     * @param removed objects removed
     * @param reordered reordered
     * @return id of operation message
     */
    public String sendRemoveOperation(String userid, PID destination, Collection<PID> removed,
            Collection<PID> reordered) {
        Element contentEl = createAtomEntry(userid, destination, CDRActions.REMOVE);

        Element remove = new Element("remove", CDR_MESSAGE_NS);
        contentEl.addContent(remove);

        remove.addContent(new Element("parent", CDR_MESSAGE_NS).setText(destination.toString()));

        Element subjects = new Element("subjects", CDR_MESSAGE_NS);
        remove.addContent(subjects);
        for (PID sub : removed) {
            subjects.addContent(new Element("pid", CDR_MESSAGE_NS).setText(sub.toString()));
        }

        Element reorderedEl = new Element("reordered", CDR_MESSAGE_NS);
        remove.addContent(reorderedEl);
        if (reordered != null) {
            for (PID re : reordered) {
                reorderedEl.addContent(new Element("pid", CDR_MESSAGE_NS).setText(re.toString()));
            }
        }

        Document msg = contentEl.getDocument();
        sendMessage(msg);

        return getMessageId(msg);
    }

    /**
     * Sends a Move operation message, indicating that objects are being moved
     * from source containers to destination containers.
     *
     * @param userid id of user who triggered the operation
     * @param sources containers objects moved from
     * @param destination containers objects moved to
     * @param moved objects moved
     * @param reordered reordered
     * @return id of operation message
     */
    public String sendMoveOperation(String userid, Collection<PID> sources, PID destination,
            Collection<PID> moved, Collection<PID> reordered) {
        Element contentEl = createAtomEntry(userid, destination, CDRActions.MOVE);

        Element move = new Element("move", CDR_MESSAGE_NS);
        contentEl.addContent(move);

        move.addContent(new Element("parent", CDR_MESSAGE_NS).setText(destination.toString()));

        Element oldParents = new Element("oldParents", CDR_MESSAGE_NS);
        move.addContent(oldParents);
        for (PID old : sources) {
            oldParents.addContent(new Element("pid", CDR_MESSAGE_NS).setText(old.toString()));
        }

        Element subjects = new Element("subjects", CDR_MESSAGE_NS);
        move.addContent(subjects);
        for (PID sub : moved) {
            subjects.addContent(new Element("pid", CDR_MESSAGE_NS).setText(sub.toString()));
        }

        Element reorderedEl = new Element("reordered", CDR_MESSAGE_NS);
        move.addContent(reorderedEl);
        if (reordered != null) {
            for (PID re : reordered) {
                reorderedEl.addContent(new Element("pid", CDR_MESSAGE_NS).setText(re.toString()));
            }
        }

        Document msg = contentEl.getDocument();
        sendMessage(msg);

        return getMessageId(msg);
    }

    @Deprecated
    public String sendReorderOperation(String userid, String timestamp, PID destination,
            Collection<PID> reordered) {
        Element contentEl = createAtomEntry(userid, destination, CDRActions.REORDER);

        Element reorder = new Element("reorder", CDR_MESSAGE_NS);
        contentEl.addContent(reorder);

        reorder.addContent(new Element("parent", CDR_MESSAGE_NS).setText(destination.toString()));

        Element reorderedEl = new Element("reordered", CDR_MESSAGE_NS);
        reorder.addContent(reorderedEl);
        if (reordered != null) {
            for (PID re : reordered) {
                reorderedEl.addContent(new Element("pid", CDR_MESSAGE_NS).setText(re.toString()));
            }
        }

        Document msg = contentEl.getDocument();
        sendMessage(msg);

        return getMessageId(msg);
    }

    /**
     * Sends a Published operation message, indicating that the publication
     * status of objects has changed.
     *
     * @param userid id of user who triggered the operation
     * @param pids objects changed
     * @param publish Subjects are published if true, unpublished if false
     * @return id of operation message
     */
    public String sendPublishOperation(String userid, Collection<PID> pids, boolean publish) {
        Element contentEl = createAtomEntry(userid, pids.iterator().next(),
                CDRActions.PUBLISH);

        Element publishEl = new Element("publish", CDR_MESSAGE_NS);
        contentEl.addContent(publishEl);

        Element publishValueEl = new Element("value", CDR_MESSAGE_NS);
        publishEl.addContent(publishValueEl);
        if (publish) {
            publishValueEl.setText("yes");
        } else {
            publishValueEl.setText("no");
        }

        Element subjects = new Element("subjects", CDR_MESSAGE_NS);
        publishEl.addContent(subjects);
        for (PID sub : pids) {
            subjects.addContent(new Element("pid", CDR_MESSAGE_NS).setText(sub.toString()));
        }

        Document msg = contentEl.getDocument();
        sendMessage(msg);

        return getMessageId(msg);
    }

    /**
     * Sends Edit Type operation message.
     *
     * @param userid id of user who triggered the operation
     * @param pids objects changed
     * @param newType new type
     * @return id of operation message
     */
    public String sendEditTypeOperation(String userid, Collection<PID> pids, ResourceType newType) {
        Element contentEl = createAtomEntry(userid, pids.iterator().next(),
                CDRActions.EDIT_TYPE);

        Element newTypeEl = new Element("newType", CDR_MESSAGE_NS);
        contentEl.addContent(newTypeEl);

        Element newTypeValueEl = new Element("value", CDR_MESSAGE_NS);
        newTypeEl.addContent(newTypeValueEl);
        newTypeEl.setText(newType.name());

        Element subjects = new Element("subjects", CDR_MESSAGE_NS);
        newTypeEl.addContent(subjects);
        for (PID sub : pids) {
            subjects.addContent(new Element("pid", CDR_MESSAGE_NS).setText(sub.toString()));
        }

        Document msg = contentEl.getDocument();
        sendMessage(msg);

        return getMessageId(msg);
    }


    /**
     * Sends message indicating that objects are being requested to be reindexed.
     *
     * @param userid id of user who triggered the operation
     * @param pids objects to be reindexed
     * @param type type of indexing action to perform
     * @return id of operation message
     */
    public String sendIndexingOperation(String userid, Collection<PID> pids, IndexingActionType type) {
        Element contentEl = createAtomEntry(userid, pids.iterator().next(),
                CDRActions.INDEX);

        Element indexEl = new Element(type.getName(), CDR_MESSAGE_NS);
        contentEl.addContent(indexEl);

        Element subjects = new Element("subjects", CDR_MESSAGE_NS);
        indexEl.addContent(subjects);
        for (PID sub : pids) {
            subjects.addContent(new Element("pid", CDR_MESSAGE_NS).setText(sub.toString()));
        }

        Document msg = contentEl.getDocument();
        sendMessage(msg);

        return getMessageId(msg);
    }

    private void sendMessage(Document msg) {
        XMLOutputter out = new XMLOutputter();
        final String msgStr = out.outputString(msg);

        this.jmsTemplate.send(new MessageCreator() {

            @Override
            public Message createMessage(Session session) throws JMSException {
                return session.createTextMessage(msgStr);
            }

        });
    }

    private Element createAtomEntry(String userid, PID contextpid, CDRActions operation) {
        return createAtomEntry(userid, contextpid, operation.toString(), "urn:uuid:" + UUID.randomUUID().toString());
    }

    /**
     * @param msg
     * @param userid
     * @param pid
     * @return
     */
    private Element createAtomEntry(String userid, PID contextpid, String operation, String messageId) {
        Document msg = new Document();
        Element entry = new Element("entry", ATOM_NS);
        msg.addContent(entry);
        entry.addContent(new Element("id", ATOM_NS).setText(messageId));
        DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
        String timestamp = fmt.print(DateTimeUtils.currentTimeMillis());
        entry.addContent(new Element("updated", ATOM_NS).setText(timestamp));
        entry.addContent(new Element("author", ATOM_NS).addContent(new Element("name", ATOM_NS).setText(userid))
                .addContent(new Element("uri", ATOM_NS).setText(CDR_MESSAGE_AUTHOR_URI)));
        entry.addContent(new Element("title", ATOM_NS)
                .setText(operation).setAttribute("type", "text"));
        entry.addContent(new Element("summary", ATOM_NS).setText(contextpid.toString()).setAttribute("type", "text"));
        Element content = new Element("content", ATOM_NS).setAttribute("type", "text/xml");
        entry.addContent(content);
        return content;
    }

    private String getMessageId(Document msg) {
        return msg.getRootElement().getChildText("id", ATOM_NS);
    }

    public JmsTemplate getJmsTemplate() {
        return jmsTemplate;
    }

    public void setJmsTemplate(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

}
