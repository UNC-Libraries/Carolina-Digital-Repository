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
import static org.slf4j.LoggerFactory.getLogger;

import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import edu.unc.lib.dcr.migration.content.ACLTransformationReport;
import edu.unc.lib.dcr.migration.content.ContentObjectTransformerManager;
import edu.unc.lib.dcr.migration.content.ContentTransformationOptions;
import edu.unc.lib.dcr.migration.content.ContentTransformationReport;
import edu.unc.lib.dcr.migration.content.ContentTransformationService;
import edu.unc.lib.dcr.migration.deposit.PreconstructedDepositSubmissionService;
import edu.unc.lib.dcr.migration.paths.PathIndex;
import edu.unc.lib.dl.event.PremisLoggerFactory;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.services.deposit.DepositDirectoryManager;
import edu.unc.lib.dl.persist.services.deposit.DepositModelManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Command for transforming content objects
 *
 * @author bbpennel
 */
@Command(name = "transform_content", aliases = {"tc"},
    description = "Transforms a tree of content objects starting from a single uuid")
public class TransformContentCommand implements Callable<Integer> {

    private static final Logger output = getLogger(OUTPUT_LOGGER);

    @ParentCommand
    private MigrationCLI parentCommand;

    @Parameters(index = "0",
            description = "UUID of the content object from which to start the transformation")
    private String startingId;

    @Mixin
    private ContentTransformationOptions options;

    private String applicationContextPath = "spring/service-context.xml";

    @Override
    public Integer call() throws Exception {
        long start = System.currentTimeMillis();

        output.info("Transforming content tree starting from {}", startingId);
        output.info("===========================================");

        try (
                ConfigurableApplicationContext context = new ClassPathXmlApplicationContext(applicationContextPath);
                DepositModelManager depositModelManager = new DepositModelManager(parentCommand.tdbDir);
            ) {

            RepositoryPIDMinter pidMinter = (RepositoryPIDMinter) context.getBean("repositoryPIDMinter");
            PID depositPid = pidMinter.mintDepositRecordPid();
            options.setDepositPid(depositPid);

            output.info("Populating deposit: " + depositPid.getId());

            DepositDirectoryManager depositDirectoryManager = new DepositDirectoryManager(
                    depositPid, parentCommand.depositBaseDir, options.isHashNesting());

            PathIndex pathIndex = (PathIndex) context.getBean("pathIndex");
            pathIndex.setDatabaseUrl(parentCommand.databaseUrl);

            PremisLoggerFactory premisLoggerFactory = (PremisLoggerFactory) context.getBean("premisLoggerFactory");
            RepositoryObjectFactory repoObjFactory = (RepositoryObjectFactory)
                    context.getBean("repositoryObjectFactory");
            RepositoryObjectLoader repoObjLoader = (RepositoryObjectLoader)
                    context.getBean("repositoryObjectLoader");

            ContentObjectTransformerManager transformerManager = new ContentObjectTransformerManager();
            transformerManager.setModelManager(depositModelManager);
            transformerManager.setPathIndex(pathIndex);
            transformerManager.setPidMinter(pidMinter);
            transformerManager.setDirectoryManager(depositDirectoryManager);
            transformerManager.setPremisLoggerFactory(premisLoggerFactory);
            transformerManager.setRepositoryObjectFactory(repoObjFactory);
            transformerManager.setOptions(options);

            ContentTransformationService transformService = new ContentTransformationService(
                    depositPid, startingId);
            transformService.setTransformerManager(transformerManager);
            transformService.setModelManager(depositModelManager);
            transformService.setRepositoryObjectLoader(repoObjLoader);

            int result = transformService.perform();

            output.info("Finished transformation in {}ms", System.currentTimeMillis() - start);

            output.info("===========================================");
            output.info(ContentTransformationReport.report());
            output.info(ACLTransformationReport.report());
            output.info("===========================================");

            if (options.isDryRun()) {
                output.info("Dry run, deposit model not saved");
                depositModelManager.removeModel(depositPid);
                depositDirectoryManager.cleanupDepositDirectory();
                return result;
            }

            if (result != 0) {
                output.info("");
                output.info("###############################################");
                output.info("# ENCOUNTERED ERRORS DURING TRANSFORMATION    #");
                output.info("###############################################");
                output.info("");
                output.info("Check logs for details. Deposit shall not be submitted or processed further.");
                return result;

            }

            if (options.getDepositInto() != null) {
                PID destinationPid = PIDs.get(options.getDepositInto());

                try (PreconstructedDepositSubmissionService depositService =
                        new PreconstructedDepositSubmissionService(parentCommand.redisHost, parentCommand.redisPort)) {

                    output.info("Submitting {} for deposit to {}", depositPid.getId(), destinationPid.getId());

                    result = depositService.submitDeposit(parentCommand.username, parentCommand.groups,
                            depositPid, destinationPid);

                    output.info("Deposit submitted");
                }
            }

            return result;
        }
    }

    public void setApplicationContextPath(String applicationContextPath) {
        this.applicationContextPath = applicationContextPath;
    }
}
