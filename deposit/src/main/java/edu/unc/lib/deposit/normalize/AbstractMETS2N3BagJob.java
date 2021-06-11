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

import static edu.unc.lib.dl.xml.SecureXMLFactory.createSAXBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.List;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.filter.ElementFilter;
import org.jdom2.output.XMLOutputter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.xml.sax.SAXException;

import edu.unc.lib.deposit.work.AbstractDepositJob;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.model.AgentPids;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.services.deposit.DepositModelHelpers;
import edu.unc.lib.dl.rdf.CdrDeposit;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.schematron.SchematronValidator;
import edu.unc.lib.dl.util.METSParseException;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositField;
import edu.unc.lib.dl.util.SoftwareAgentConstants.SoftwareAgent;
import edu.unc.lib.dl.xml.JDOMNamespaceUtil;
import edu.unc.lib.dl.xml.METSProfile;
import edu.unc.lib.dl.xml.NoResolutionResourceResolver;

/**
 *
 * @author bbpennel
 *
 */
public abstract class AbstractMETS2N3BagJob extends AbstractDepositJob {

    private static final Logger log = LoggerFactory.getLogger(AbstractMETS2N3BagJob.class);

    @Autowired
    protected SchematronValidator schematronValidator = null;
    @Autowired
    private Schema metsSipSchema = null;

    public SchematronValidator getSchematronValidator() {
        return schematronValidator;
    }

    public void setSchematronValidator(SchematronValidator schematronValidator) {
        this.schematronValidator = schematronValidator;
    }

    public AbstractMETS2N3BagJob(String uuid, String depositUUID) {
        super(uuid, depositUUID);
    }

    public AbstractMETS2N3BagJob() {
        super();
    }

    protected File getMETSFile() {
        File dataDir = new File(getDepositDirectory(), "data");
        File result = new File(dataDir, "mets.xml");
        if (!result.exists()) {
            result = new File(dataDir, "METS.xml");
        }
        if (!result.exists()) {
            result = new File(dataDir, "METS.XML");
        }
        if (!result.exists()) {
            result = new File(URI.create(getDepositStatus().get(DepositField.sourceUri.name())));
            if (!result.exists()) {
                failJob("Cannot find a METS file",
                        " A METS was not found in the expected locations: mets.xml, METS.xml, METS.XML.");
            }
        }
        return result;
    }

    public Schema getMetsSipSchema() {
        return metsSipSchema;
    }

    public void setMetsSipSchema(Schema metsSipSchema) {
        this.metsSipSchema = metsSipSchema;
    }

    protected void assignPIDs(Document mets) {
        int count = 0;
        Iterator<Element> divs = mets.getDescendants(new ElementFilter("div", JDOMNamespaceUtil.METS_NS));
        while (divs.hasNext()) {
            Element div = divs.next();
            String cids = div.getAttributeValue("CONTENTIDS");
            if (cids != null && cids.contains("info:fedora/")) {
                continue;
            }
            PID pid = pidMinter.mintContentPid();
            if (cids == null) {
                div.setAttribute("CONTENTIDS", pid.getURI());
            } else {
                StringBuilder sb = new StringBuilder(pid.getURI());
                for (String s : cids.split("\\s")) {
                    sb.append(" ").append(s);
                }
                div.setAttribute("CONTENTIDS", sb.toString());
            }

            PremisLogger premisLogger = getPremisLogger(pid);
            premisLogger.buildEvent(Premis.Accession)
                    .addEventDetail("Assigned PID, {0}, to object defined in a METS div", pid)
                    .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.depositService))
                    .write();

            count++;
        }
        log.info("{} PIDs assigned", count);

        PID depositPID = getDepositPID();
        PremisLogger premisDepositLogger = getPremisLogger(depositPID);
        premisDepositLogger.buildEvent(Premis.Accession)
                .addEventDetail("Assigned {0,choice,1#PID|2#PIDs}"
                        + " to {0,choice,1#one object|2#{0,number} objects} ", count)
                .write();
    }

    protected Document loadMETS() {
        Document mets = null;
        try {
            mets = createSAXBuilder().build(getMETSFile());
        } catch (Exception e) {
            failJob(e, "Unexpected error parsing METS file.");
        }
        log.info("METS dom document loaded");
        return mets;
    }

    protected void saveMETS(Document mets) {
        try (FileOutputStream fos = new FileOutputStream(getMETSFile())) {
            new XMLOutputter().output(mets, fos);
        } catch (Exception e) {
            failJob(e, "Unexpected error saving METS.");
        }
        log.info("METS saved with new PIDs");
    }

    protected void validateMETS() {

        METSParseException handler = new METSParseException("There was a problem parsing METS XML.");

        try {
            // XXE addressed via resource resolver since xerces validator doesn't support the usual jaxp parameters
            Validator metsValidator = getMetsSipSchema().newValidator();
            metsValidator.setResourceResolver(new NoResolutionResourceResolver());
            metsValidator.setErrorHandler(handler);

            metsValidator.validate(new StreamSource(getMETSFile()));
        } catch (SAXException e) {
            if (log.isDebugEnabled()) {
                log.debug(e.getMessage());
            }
            failJob(handler, "METS is not valid with respect to schemas.");
        } catch (IOException e) {
            failJob(e, "Cannot parse METS file.");
        }
        log.info("METS XML validated");
        PID depositPID = getDepositPID();
        PremisLogger premisDepositLogger = getPremisLogger(depositPID);
        premisDepositLogger.buildEvent(Premis.Validation)
                .addEventDetail("METS schema(s) validated")
                .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.depositService))
                .write();
    }

    /**
     * Stores a reference to the METS file for this deposit as the manifest
     * @param model
     */
    protected void addManifestURI(Model model) {
        File metsFile = getMETSFile();
        Resource depositResc = model.getResource(depositPID.getURI());

        log.debug("Adding manifest URI referencing {}", metsFile);
        Resource manifestResc = DepositModelHelpers.addManifest(depositResc, "mets.xml");
        manifestResc.addLiteral(CdrDeposit.stagingLocation, metsFile.toPath().toUri().toString());
        manifestResc.addLiteral(CdrDeposit.mimetype, "text/xml");
    }

    protected void validateProfile(METSProfile profile) {
        List<String> errors = schematronValidator
                .validateReportErrors(new StreamSource(getMETSFile()), profile.getName());
        if (errors != null && errors.size() > 0) {
            String msg = MessageFormat.format("METS is not valid with respect to profile: {0}", profile.getName());
            StringBuilder details = new StringBuilder();
            for (String error : errors) {
                details.append(error).append('\n');
            }
            failJob(msg, details.toString());
        }
        log.info("METS Schematron validated");
    }

}
