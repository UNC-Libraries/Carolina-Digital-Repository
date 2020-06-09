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

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.slf4j.Logger;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Main class for the migration tool
 *
 * @author bbpennel
 *
 */
@Command(subcommands = {
        TransformPremis.class,
        PathIndexCommand.class,
        TransformContentCommand.class,
        TransformDepositRecordsCommand.class,
        ViewDepositModelCommand.class,
        SubmitDepositCommand.class,
        PullDetailsCommand.class
    })
public class MigrationCLI implements Callable<Integer> {
    private static final Logger output = getLogger(OUTPUT_LOGGER);

    @Option(names = {"-d", "--database-url"},
            defaultValue = "${sys:dcr.migration.index.url:-${sys:user.home}/bxc_pindex",
            description = "Path where the database for the index is stored. Defaults to home dir.")
    protected String databaseUrl;

    @Option(names = {"--tdb-dir"},
            defaultValue = "${sys:dcr.tdb.dir:-${sys:user.home}/bxc_tdb",
            description = "Path where the jena TDB deposit dataset is stored. Defaults to home dir.")
    protected String tdbDir;

    @Option(names = {"--deposit-dir"},
            defaultValue = "${sys:dcr.deposit.dir:-${sys:user.home}/bxc_deposits",
            description = "Path where deposit directories will be stored. Defaults to home dir.")
    protected Path depositBaseDir;

    @Option(names = {"-u", "--username"},
            defaultValue = "${sys:user.name}",
            description = "User performing this action. Defaults to current user.")
    protected String username;

    @Option(names = {"--groups"},
            defaultValue = "unc:app:lib:cdr:migrationGroup",
            description = "Groups used for permissions evaluations. Defaults to the migration group.")
    protected String groups;

    @Option(names = {"--redis-host"},
            defaultValue = "localhost",
            description = "Host name for redis. Default localhost.")
    protected String redisHost;

    @Option(names = {"--redis-port"},
            defaultValue = "6379",
            description = "Port for redis. Default 6379.")
    protected int redisPort;

    protected MigrationCLI() {
    }

    @Override
    public Integer call() throws Exception {
        output.info(BannerUtility.getBanner());
        return 0;
    }

    @Command(name = "chomp")
    public int chomp() {
        output.info(BannerUtility.getChompBanner());
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MigrationCLI()).execute(args);
        System.exit(exitCode);
    }

}
