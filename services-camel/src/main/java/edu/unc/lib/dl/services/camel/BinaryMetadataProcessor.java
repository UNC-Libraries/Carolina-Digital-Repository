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
package edu.unc.lib.dl.services.camel;

import static edu.unc.lib.dl.rdf.Ebucore.hasMimeType;
import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrBinaryMimeType;
import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrBinaryPath;
import static org.fcrepo.camel.FcrepoHeaders.FCREPO_URI;

import java.net.URI;
import java.nio.file.Paths;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fedora.ObjectTypeMismatchException;
import edu.unc.lib.dl.fedora.PID;

/**
 * Stores information related to identifying binary objects from the repository
 *
 * @author lfarrell
 *
 */
public class BinaryMetadataProcessor implements Processor {
    private static final Logger log = LoggerFactory.getLogger(BinaryMetadataProcessor.class);

    private RepositoryObjectLoader repoObjLoader;

    @Override
    public void process(final Exchange exchange) throws Exception {
        final Message in = exchange.getIn();

        String fcrepoBinaryUri = (String) in.getHeader(FCREPO_URI);
        PID binPid = PIDs.get(fcrepoBinaryUri);

        BinaryObject binObj;
        try {
            binObj = repoObjLoader.getBinaryObject(binPid);
        } catch (ObjectTypeMismatchException e) {
            log.warn("Cannot extract binary metadata from {}, it is not a binary", binPid.getId());
            return;
        }

        if (binObj.getContentUri() != null) {
            Resource resc = binObj.getResource();
            String binaryMimeType = resc.getProperty(hasMimeType).getObject().toString();

            URI contentUri = binObj.getContentUri();
            if (!contentUri.getScheme().equals("file")) {
                log.warn("Only file content URIs are supported at this time");
                return;
            }

            in.setHeader(CdrBinaryPath, Paths.get(binObj.getContentUri()).toString());
            in.setHeader(CdrBinaryMimeType, binaryMimeType);
        } else {
            log.warn("Cannot process {}, internal binaries are not currently supported", binPid.getId());
        }
    }

    /**
     * @param repoObjLoader the repoObjLoader to set
     */
    public void setRepositoryObjectLoader(RepositoryObjectLoader repoObjLoader) {
        this.repoObjLoader = repoObjLoader;
    }
}
