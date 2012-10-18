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
package edu.unc.lib.dl.data.ingest.solr.indexing;

import org.jdom.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.data.ingest.solr.IndexingException;
import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.ManagementClient;
import edu.unc.lib.dl.fedora.PID;

public class DocumentIndexingPackageFactory {
	private static final Logger log = LoggerFactory.getLogger(DocumentIndexingPackageFactory.class);
	
	private ManagementClient managementClient = null;
	
	public DocumentIndexingPackage createDocumentIndexingPackage(PID pid) {
		try {
			Document foxml = managementClient.getObjectXML(pid);
			if (foxml == null)
				throw new IndexingException("Failed to retrieve FOXML for " + pid.getPid());
			return new DocumentIndexingPackage(pid, foxml);
		} catch (FedoraException e) {
			throw new IndexingException("Failed to retrieve FOXML for " + pid.getPid(), e);
		}
	}

	public void setManagementClient(ManagementClient managementClient) {
		this.managementClient = managementClient;
	}
}
