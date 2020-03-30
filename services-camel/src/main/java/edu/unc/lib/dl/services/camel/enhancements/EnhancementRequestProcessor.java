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
package edu.unc.lib.dl.services.camel.enhancements;

import static edu.unc.lib.dl.rdf.Fcrepo4Repository.Binary;
import static edu.unc.lib.dl.services.RunEnhancementsMessageHelpers.makeEnhancementOperationBody;
import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrBinaryMimeType;
import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrEnhancementSet;
import static org.fcrepo.camel.FcrepoHeaders.FCREPO_URI;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.jdom2.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.ContentContainerObject;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.services.MessageSender;
import edu.unc.lib.dl.services.camel.cdrEvents.CdrEventMessageHelpers;
import edu.unc.lib.dl.services.camel.util.MessageUtil;

/**
 * Sets headers related to identifying binary objects to run enhancement operations on
 *
 * @author lfarrell
 */
public class EnhancementRequestProcessor implements Processor {
    private static final Logger log = LoggerFactory.getLogger(EnhancementRequestProcessor.class);

    private static final String DEFAULT_ENHANCEMENTS = "thumbnails,imageAccessCopy,extractFulltext";
    public static final String FCREPO_RESOURCE_TYPE = "org.fcrepo.jms.resourceType";

    private RepositoryObjectLoader repoObjLoader;
    private MessageSender messageSender;

    @Override
    public void process(final Exchange exchange) throws Exception {
        final Message in = exchange.getIn();

        Document msgBody = MessageUtil.getDocumentBody(in);

        PID pid = CdrEventMessageHelpers.extractPid(msgBody);
        String pidValue = pid.getRepositoryPath();
        RepositoryObject repoObj = repoObjLoader.getRepositoryObject(pid);

        log.info("Adding enhancement headers for {}", pidValue);
        // For FileObjects, skip directly to its original file
        if (repoObj instanceof FileObject) {
            FileObject fileObj = (FileObject) repoObj;
            BinaryObject binObj = fileObj.getOriginalFile();
            if (binObj == null) {
                log.warn("Cannot perform enhancements on file object {}, it has no original binary",
                        fileObj.getPid().getQualifiedId());
                return;
            }
            repoObj = binObj;
            String binPid = binObj.getPid().getRepositoryPath();
            log.debug("Redirecting from file object {} to its binary {}",
                    pidValue, binPid);

            in.setHeader(FCREPO_URI, binPid);
        } else {
            in.setHeader(FCREPO_URI, pidValue);
        }

        if (repoObj instanceof BinaryObject) {
            BinaryObject binObj = (BinaryObject) repoObj;
            in.setHeader(CdrBinaryMimeType, binObj.getMimetype());
            in.setHeader(FCREPO_RESOURCE_TYPE, Binary.getURI());
            in.setHeader(CdrEnhancementSet, DEFAULT_ENHANCEMENTS);
        } else {
            log.debug("Processing container {}, generating requests for its children", pidValue);
            ContentContainerObject container = (ContentContainerObject) repoObj;

            String author = CdrEventMessageHelpers.extractAuthor(msgBody);
            boolean force = CdrEventMessageHelpers.extractForceFlag(msgBody);

            for (ContentObject childObj: container.getMembers()) {
                Document childRunBody = makeEnhancementOperationBody(author, childObj.getPid(), null, force);
                messageSender.sendMessage(childRunBody);
            }
        }
    }

    public void setMessageSender(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    public void setRepositoryObjectLoader(RepositoryObjectLoader repositoryObjectLoader) {
        this.repoObjLoader = repositoryObjectLoader;
    }
}