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

import static edu.unc.lib.dcr.migration.MigrationConstants.OUTPUT_LOGGER;
import static edu.unc.lib.dl.fcrepo4.RepositoryPathConstants.HASHED_PATH_DEPTH;
import static edu.unc.lib.dl.fcrepo4.RepositoryPathConstants.HASHED_PATH_SIZE;
import static edu.unc.lib.dl.fcrepo4.RepositoryPaths.idToPath;
import static java.nio.file.Files.createDirectories;
import static java.util.stream.Collectors.toList;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.Stream;

import org.slf4j.Logger;

import edu.unc.lib.dl.exceptions.RepositoryException;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;

/**
 * Service for transforming batches of PREMIS XML to RDF.
 *
 * @author bbpennel
 */
public abstract class PremisTransformationService {

    private static final Logger output = getLogger(OUTPUT_LOGGER);

    protected RepositoryPIDMinter pidMinter;
    protected PremisLoggerFactory premisLoggerFactory;
    protected Path premisListPath;
    protected Path outputPath;
    protected boolean hashNesting = true;

    protected PremisTransformationService(Path premisListPath, Path outputPath) {
        pidMinter = new RepositoryPIDMinter();
        this.premisListPath = premisListPath;
        this.outputPath = outputPath;
    }

    /**
     * Perform the transformation.
     *
     * @return result status. 0 if the transformation was successful, otherwise 1.
     */
    public int perform() {
        int result = 0;
        List<ForkJoinTask<Void>> futures;
        try (Stream<String> stream = Files.lines(premisListPath)) {
            futures = stream.map(Paths::get).map(premisPath -> {
                PID pid = makePid(premisPath);
                AbstractPremisToRdfTransformer transformer = makeTransformer(pid, premisPath);
                return transformer.fork();
            }).collect(toList());
        } catch (IOException e) {
            output.error("Failed to read list file or create log directories", e);
            return 1;
        }

        // Wait for all results and output any failures
        for (ForkJoinTask<Void> future: futures) {
            try {
                future.join();
            } catch (RepositoryException e) {
                output.error(e.getMessage());
                result = 1;
            }
        }

        return result;
    }

    protected abstract PID makePid(Path premisPath);

    protected abstract AbstractPremisToRdfTransformer makeTransformer(PID pid, Path docPath);

    protected File getTransformedPremisFile(PID pid) throws IOException {
        Path premisPath = getTransformedPremisPath(outputPath, pid, hashNesting);
        File premisFile = premisPath.toFile();
        createDirectories(premisFile.getParentFile().toPath());

        return premisFile;
    }

    /**
     * Get the path to where a transformed premis log file should be stored.
     *
     * @param outputPath
     * @param pid
     * @return
     */
    public static Path getTransformedPremisPath(Path outputPath, PID pid, boolean hashNesting) {
        Path premisDir;
        if (hashNesting) {
            String hashing = idToPath(pid.getId(), HASHED_PATH_DEPTH, HASHED_PATH_SIZE);
            premisDir = outputPath.resolve(hashing);
        } else {
            premisDir = outputPath;
        }

        return premisDir.resolve(pid.getId() + ".nt");
    }

    public void setPidMinter(RepositoryPIDMinter pidMinter) {
        this.pidMinter = pidMinter;
    }

    public void setPremisLoggerFactory(PremisLoggerFactory premisLoggerFactory) {
        this.premisLoggerFactory = premisLoggerFactory;
    }

    public void setHashNesting(boolean hashNesting) {
        this.hashNesting = hashNesting;
    }
}
