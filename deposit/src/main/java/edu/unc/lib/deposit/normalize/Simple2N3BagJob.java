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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriUtils;

import com.hp.hpl.jena.rdf.model.Bag;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Resource;
import edu.unc.lib.deposit.work.AbstractDepositJob;
import edu.unc.lib.dl.event.PremisLogger;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.DepositConstants;
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
		Model model = getWritableModel();
		Bag depositBag = model.createBag(depositPID.getURI().toString());

		// Generate a uuid for the main object
		PID primaryPID = PIDs.get("uuid:" + UUID.randomUUID());

		// Identify the important file from the deposit
		Map<String, String> depositStatus = getDepositStatus();
		String filename = depositStatus.get(DepositField.fileName.name());
		String slug = depositStatus.get(DepositField.depositSlug.name());
		String mimetype = depositStatus.get(DepositField.fileMimetype.name());

		// Create the primary resource as a simple resource
		Resource primaryResource = model.createResource(primaryPID.getURI());
		
		populateFileObject(model, primaryResource, slug, filename, mimetype);

		// Store primary resource as child of the deposit
		depositBag.add(primaryResource);

		if (!this.getDepositDirectory().exists()) {
			log.info("Creating deposit dir {}", this.getDepositDirectory().getAbsolutePath());
			this.getDepositDirectory().mkdir();
		}
		
		// Add normalization event to deposit record
		PremisLogger premisDepositLogger = getPremisLogger(depositPID);
		Resource premisDepositEvent = premisDepositLogger.buildEvent(Premis.Normalization)
				.addEventDetail("Normalized deposit package from {0} to {1}",
						PackagingType.SIMPLE_OBJECT.getUri(), PackagingType.BAG_WITH_N3.getUri())
				.addSoftwareAgent(SoftwareAgent.depositService.getFullname())
				.create();
		premisDepositLogger.writeEvent(premisDepositEvent);
	}

	private void populateFileObject(Model model, Resource primaryResource, String alabel, String filename,
			String mimetype) {
		File contentFile = new File(this.getDataDirectory(), filename);
		if (!contentFile.exists()) {
			failJob("Failed to find upload file for simple deposit: " + filename,
					contentFile.getAbsolutePath());
		}
		
		String checksum = null;
		String fullPath = contentFile.toString();
		
		try {
			checksum = DigestUtils.md5Hex(new FileInputStream(fullPath));
			
			PremisLogger premisDepositLogger = getPremisLogger(PIDs.get(primaryResource.toString()));
			Resource premisDepositEvent = premisDepositLogger.buildEvent(Premis.MessageDigestCalculation)
					.addEventDetail("Checksum for file is {0}", checksum)
					.addSoftwareAgent(SoftwareAgent.depositService.getFullname())
					.create();
			
			premisDepositLogger.writeEvent(premisDepositEvent);
		} catch (IOException e) {
			failJob(e, "Unable to compute checksum. File not found at {}", fullPath);
		}
		
		model.add(primaryResource, CdrDeposit.md5sum, checksum);

		if(alabel == null) alabel = contentFile.getName();
		model.add(primaryResource, CdrDeposit.label, alabel);
		model.add(primaryResource, CdrDeposit.size, Long.toString(contentFile.length()));
		if (mimetype != null) {
			model.add(primaryResource, CdrDeposit.mimetype, mimetype);
		}

		// Reference the content file as the data file
		try {
			model.add(primaryResource, CdrDeposit.stagingLocation,
					DepositConstants.DATA_DIR + "/" + UriUtils.encodePathSegment(contentFile.getName(), "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			failJob(e, "Failed to add staging location for {} due to encoding issues", contentFile.getName());
		}
	}

}
