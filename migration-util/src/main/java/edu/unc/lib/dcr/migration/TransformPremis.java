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
package edu.unc.lib.dcr.migration;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import edu.unc.lib.dcr.migration.premis.PremisTransformationService;
import edu.unc.lib.dcr.migration.premis.TransformContentPremisService;
import edu.unc.lib.dcr.migration.premis.TransformDepositPremisService;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.persist.services.event.PremisLoggerFactoryImpl;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command for premis transformations
 *
 * @author bbpennel
 */
@Command(name = "transform_premis", aliases = {"tp"},
    description = "Transform a list of PREMIS 2 XML documents into PREMIS 3 serialized as n-triples")
public class TransformPremis implements Callable<Integer> {
    @Option(names = {"-d", "--deposits"},
            description = "Transform PREMIS events for deposit records."
                    + " If not provided, default to assuming subjects are content objects")
    private boolean depositRecords;

    @Option(names = {"--no-hash-nesting"}, negatable = true,
            description = "Nest transformed logs in hashed subdirectories. Default: true")
    private boolean hashNesting = true;

    @Parameters(index = "0",
            description = "Path to file listing PREMIS documents to transform."
                    + " The file names must include the PID of the object they describe.")
    private Path premisListPath;

    @Parameters(index = "1", description = "Path where transformed logs will be stored")
    private Path outputPath;

    @Override
    public Integer call() throws Exception {
        PremisLoggerFactory premisLoggerFactory = new PremisLoggerFactoryImpl();
        RepositoryPIDMinter pidMinter = new RepositoryPIDMinter();

        PremisTransformationService transformationService;
        if (depositRecords) {
            transformationService = new TransformDepositPremisService(premisListPath, outputPath);
        } else {
            transformationService = new TransformContentPremisService(premisListPath, outputPath);
        }
        transformationService.setPidMinter(pidMinter);
        transformationService.setPremisLoggerFactory(premisLoggerFactory);
        transformationService.setHashNesting(hashNesting);

        return transformationService.perform();
    }
}
