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

import static edu.unc.lib.dcr.migration.MigrationConstants.OUTPUT_LOGGER;
import static edu.unc.lib.dl.fedora.PIDConstants.DEPOSITS_QUALIFIER;
import static org.slf4j.LoggerFactory.getLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.persist.services.deposit.DepositDirectoryManager;
import edu.unc.lib.dl.persist.services.event.PremisLoggerFactoryImpl;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * @author bbpennel
 */
@Command(name = "verify_premis",
    description = "Verify premis log files in a deposit")
public class VerifyPremisLogsCommand implements Callable<Integer> {
    private static final Logger output = getLogger(OUTPUT_LOGGER);

    @ParentCommand
    private MigrationCLI parentCommand;

    @Parameters(index = "0",
            description = "Deposit ID")
    private String depositId;

    @Option(names = {"--no-hash-nesting"}, negatable = true,
            description = "Nest transformed logs in hashed subdirectories. Default: true")
    private boolean hashNesting = true;

    @Override
    public Integer call() throws Exception {
        long start = System.currentTimeMillis();

        output.info("Verify PREMIS files in deposit {}", depositId);
        output.info("===========================================");

        PID depositPid = PIDs.get(DEPOSITS_QUALIFIER, depositId);

        DepositDirectoryManager depositDirectoryManager = new DepositDirectoryManager(
                depositPid, parentCommand.depositBaseDir, hashNesting);
        PremisLoggerFactory premisLoggerFactory = new PremisLoggerFactoryImpl();

        AtomicBoolean errors = new AtomicBoolean();
        try (Stream<Path> fileStream = Files.walk(depositDirectoryManager.getEventsDir())) {
            fileStream.filter(Files::isRegularFile).forEach(path -> {
                String id = StringUtils.substringBeforeLast(path.getFileName().toString(), ".");
                PID pid = PIDs.get(id);
                try {
                    PremisLogger premisLogger = premisLoggerFactory.createPremisLogger(pid, path.toFile());
                    premisLogger.getEventsModel();
                    output.info("Success: {}", path);
                } catch (Exception e) {
                    output.info("Failure: {}", path, e);
                    errors.set(true);
                }
            });
        }

        if (errors.get()) {
            output.info("#### ENCOUNTERED ERRORS ####");
        } else {
            output.info("No errors during scan of PREMIS files");
        }

        output.info("===========================================");
        output.info("Finished check in {}ms", System.currentTimeMillis() - start);
        output.info("===========================================");

        return errors.get() ? 1 : 0;
    }
}
