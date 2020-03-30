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

package edu.unc.lib.dl.services.camel.solr;

import static edu.unc.lib.dl.services.camel.enhancements.EnhancementRequestProcessor.FCREPO_RESOURCE_TYPE;
import static org.fcrepo.camel.FcrepoHeaders.FCREPO_URI;

import java.util.concurrent.TimeUnit;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.data.ingest.solr.SolrUpdateRequest;
import edu.unc.lib.dl.data.ingest.solr.indexing.DocumentIndexingPackage;
import edu.unc.lib.dl.data.ingest.solr.indexing.DocumentIndexingPackageFactory;
import edu.unc.lib.dl.data.ingest.solr.indexing.DocumentIndexingPipeline;
import edu.unc.lib.dl.data.ingest.solr.indexing.SolrUpdateDriver;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.rdf.Fcrepo4Repository;
import edu.unc.lib.dl.util.IndexingActionType;

/**
 * Index newly ingested objects in Solr
 *
 * @author lfarrell
 *
 */
public class SolrIngestProcessor implements Processor {
    private static final Logger log = LoggerFactory.getLogger(SolrIngestProcessor.class);

    private final int maxRetries;
    private final long retryDelay;
    private DocumentIndexingPackageFactory factory;
    private DocumentIndexingPipeline pipeline;
    private SolrUpdateDriver solrUpdateDriver;

    public SolrIngestProcessor(DocumentIndexingPackageFactory factory,
            DocumentIndexingPipeline pipeline, SolrUpdateDriver solrUpdateDriver, int maxRetries, long retryDelay) {
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
        this.factory = factory;
        this.pipeline = pipeline;
        this.solrUpdateDriver = solrUpdateDriver;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        final Message in = exchange.getIn();
        String fcrepoUri = (String) in.getHeader(FCREPO_URI);

        PID targetPid = PIDs.get(fcrepoUri);
        String resourceTypes = (String) in.getHeader(FCREPO_RESOURCE_TYPE);

        // for binaries, need to index the file object which contains it
        if (resourceTypes != null && resourceTypes.contains(Fcrepo4Repository.Binary.getURI())) {
            targetPid = PIDs.get(targetPid.getId());
        }

        int retryAttempt = 0;

        while (true) {
            try {
                SolrUpdateRequest updateRequest = new SolrUpdateRequest(
                        targetPid.getRepositoryPath(), IndexingActionType.ADD);

                DocumentIndexingPackage dip = factory.createDip(targetPid);
                updateRequest.setDocumentIndexingPackage(dip);

                pipeline.process(dip);
                solrUpdateDriver.addDocument(dip.getDocument());

                return;
            } catch (Exception e) {
                if (retryAttempt == maxRetries) {
                    throw e;
                }

                retryAttempt++;
                log.info("Unable to update solr for {}. Retrying, attempt {}",
                        fcrepoUri, retryAttempt);
                TimeUnit.MILLISECONDS.sleep(retryDelay);
            }
        }
    }
}