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
package edu.unc.lib.deposit.normalize;

import static edu.unc.lib.dl.model.DatastreamType.ORIGINAL_FILE;

import java.io.File;
import java.net.URI;
import java.util.Map;

import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.deposit.work.AbstractDepositJob;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.PackagingType;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositField;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;

/**
 * Normalizes a simple deposit object into an N3 deposit structure.
 *
 * Expects to receive a single file in the data directory, as referenced in deposit status.
 *
 * @author count0
 * @date Jun 20, 2014
 */
public class Simple2N3BagJob extends AbstractDepositJob {

    private static final Logger log = LoggerFactory.getLogger(Simple2N3BagJob.class);

    public Simple2N3BagJob() {
        super();
    }

    public Simple2N3BagJob(String uuid, String depositUUID) {
        super(uuid, depositUUID);
    }

    @Override
    public void runJob() {

        // deposit RDF bag
        PID depositPID = getDepositPID();
        Model depModel = getReadOnlyModel();
        Model model = ModelFactory.createDefaultModel().add(depModel);
        Bag depositBag = model.createBag(depositPID.getURI().toString());

        // Generate a uuid for the main object
        PID mainPID = pidMinter.mintContentPid();

        // Identify the important file from the deposit
        Map<String, String> depositStatus = getDepositStatus();
        URI sourceUri = URI.create(depositStatus.get(DepositField.sourceUri.name()));
        String slug = depositStatus.get(DepositField.depositSlug.name());
        String mimetype = depositStatus.get(DepositField.fileMimetype.name());

        // Create the main resource as a simple resource
        Resource mainResource = model.createResource(mainPID.getURI());

        populateFileObject(mainResource, slug, sourceUri, mimetype);

        // Store main resource as child of the deposit
        depositBag.add(mainResource);

        commit(() -> depModel.add(model));

        if (!this.getDepositDirectory().exists()) {
            log.info("Creating deposit dir {}", this.getDepositDirectory().getAbsolutePath());
            this.getDepositDirectory().mkdir();
        }

        // Add normalization event to deposit record
        PremisLogger premisDepositLogger = getPremisLogger(depositPID);
        premisDepositLogger.buildEvent(Premis.Normalization)
                .addEventDetail("Normalized deposit package from {0} to {1}",
                        PackagingType.SIMPLE_OBJECT.getUri(), PackagingType.BAG_WITH_N3.getUri())
                .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.depositService))
                .write();
    }

    private void populateFileObject(Resource mainResource, String alabel, URI sourceUri,
            String mimetype) {
        File contentFile = new File(sourceUri);
        if (!contentFile.exists()) {
            failJob("Failed to find upload file for simple deposit: " + sourceUri,
                    contentFile.getAbsolutePath());
        }

        if (alabel == null) {
            alabel = contentFile.getName();
        }
        mainResource.addLiteral(CdrDeposit.label, alabel);
        mainResource.addProperty(RDF.type, Cdr.FileObject);

        Resource originalResc = DepositModelHelpers.addDatastream(mainResource, ORIGINAL_FILE);
        originalResc.addLiteral(CdrDeposit.size, Long.toString(contentFile.length()));
        if (mimetype != null) {
            originalResc.addLiteral(CdrDeposit.mimetype, mimetype);
        }
        // Reference the content file as the data file
        originalResc.addLiteral(CdrDeposit.stagingLocation, sourceUri.toString());
    }

}
