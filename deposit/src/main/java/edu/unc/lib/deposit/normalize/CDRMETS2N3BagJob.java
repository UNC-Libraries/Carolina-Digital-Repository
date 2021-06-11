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

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.jdom2.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.util.PackagingType;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositField;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;
import edu.unc.lib.dl.xml.METSProfile;

/**
 *
 * @author bbpennel
 *
 */
public class CDRMETS2N3BagJob extends AbstractMETS2N3BagJob {
    private static final Logger LOG = LoggerFactory.getLogger(CDRMETS2N3BagJob.class);
    public CDRMETS2N3BagJob() {
        super();
    }

    public CDRMETS2N3BagJob(String uuid, String depositUUID) {
        super(uuid, depositUUID);
    }

    @Override
    public void runJob() {
        validateMETS();

        validateProfile(METSProfile.CDR_SIMPLE);

        interruptJobIfStopped();

        Model depModel = getReadOnlyModel();
        Model model = ModelFactory.createDefaultModel().add(depModel);

        // Store a reference to the manifest file
        addManifestURI(model);

        Document mets = loadMETS();
        // assign any missing PIDs
        assignPIDs(mets);
        // manifest updated to have record of all PIDs
        saveMETS(mets);

        Map<String, String> status = getDepositStatus();
        URI sourceUri = URI.create(status.get(DepositField.sourceUri.name()));
        Path sourceDir = Paths.get(sourceUri).getParent();

        CDRMETSGraphExtractor extractor = new CDRMETSGraphExtractor(mets, this.getDepositPID());
        LOG.info("Extractor initialized");
        extractor.addArrangement(model);
        LOG.info("Extractor arrangement added");
        extractor.helper.addFileAssociations(model, sourceDir);
        LOG.info("Extractor file associations added");
        extractor.addAccessControls(model);
        LOG.info("Extractor access controls added");

        extractor.saveDescriptions(new FilePathFunction() {
        @Override
            public String getPath(String piduri) {
                PID pid = PIDs.get(piduri);
                return getModsPath(pid, true).toAbsolutePath().toString();
            }
        });
        LOG.info("MODS descriptions saved");

        PID depositPID = getDepositPID();
        PremisLogger premisDepositLogger = getPremisLogger(depositPID);
        premisDepositLogger.buildEvent(Premis.Accession)
                .addEventDetail("Normalized deposit package from {0} to {1}", PackagingType.METS_CDR.getUri(),
                        PackagingType.BAG_WITH_N3.getUri())
                .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.depositService))
                .write();

        commit(() -> depModel.add(model));
    }

}
