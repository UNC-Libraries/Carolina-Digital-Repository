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
package edu.unc.lib.dcr.migration.premis;

import static edu.unc.lib.dcr.migration.MigrationConstants.extractUUIDFromPath;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import edu.unc.lib.dl.exceptions.RepositoryException;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;

/**
 * Transformation service for batches of content object premis event logs
 *
 * @author bbpennel
 */
public class TransformContentPremisService extends PremisTransformationService {

    /**
     * Constructor a content object batch transformation service
     *
     * @param premisListPath path to file containing list of premis log paths
     * @param outputPath path to directory where transformed logs should be stored
     */
    public TransformContentPremisService(Path premisListPath, Path outputPath) {
        super(premisListPath, outputPath);
    }

    @Override
    protected PID makePid(Path premisPath) {
        return PIDs.get(extractUUIDFromPath(premisPath));
    }

    @Override
    protected AbstractPremisToRdfTransformer makeTransformer(PID pid, Path docPath) {
        File transformedLogFile;
        try {
            transformedLogFile = getTransformedPremisFile(pid);
        } catch (IOException e) {
            throw new RepositoryException("Failed to create log directory for " + pid.getId(), e);
        }
        PremisLogger premisLogger = premisLoggerFactory.createPremisLogger(pid, transformedLogFile);
        return new ContentPremisToRdfTransformer(pid, premisLogger, docPath);
    }

}
