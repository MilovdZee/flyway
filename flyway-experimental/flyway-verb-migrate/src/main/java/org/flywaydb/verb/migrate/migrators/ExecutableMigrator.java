/*-
 * ========================LICENSE_START=================================
 * flyway-verb-migrate
 * ========================================================================
 * Copyright (C) 2010 - 2025 Red Gate Software Ltd
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.flywaydb.verb.migrate.migrators;

import static org.flywaydb.verb.VerbUtils.toMigrationText;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import lombok.CustomLog;
import org.flywaydb.core.api.LoadableMigrationInfo;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.CommandResultFactory;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.experimental.ExperimentalDatabase;
import org.flywaydb.core.internal.exception.FlywayMigrateException;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.util.StopWatch;
import org.flywaydb.experimental.callbacks.CallbackManager;
import org.flywaydb.verb.ErrorUtils;
import org.flywaydb.verb.executors.ExecutorFactory;
import org.flywaydb.verb.migrate.MigrationExecutionGroup;
import org.flywaydb.verb.executors.Executor;
import org.flywaydb.verb.readers.Reader;
import org.flywaydb.verb.readers.ReaderFactory;

@CustomLog
public class ExecutableMigrator extends Migrator{
    @Override
    public List<MigrationExecutionGroup> createGroups(final MigrationInfo[] allPendingMigrations,
        final Configuration configuration,
        final ExperimentalDatabase experimentalDatabase,
        final MigrateResult migrateResult,
        final ParsingContext parsingContext) {

        return Arrays.stream(allPendingMigrations)
            .map(x -> new MigrationExecutionGroup(List.of(x), true))
            .toList();
    }

    @Override
    public int doExecutionGroup(final Configuration configuration,
        final MigrationExecutionGroup executionGroup,
        final ExperimentalDatabase experimentalDatabase,
        final MigrateResult migrateResult,
        final ParsingContext parsingContext,
        final int installedRank, final CallbackManager callbackManager) {

        final boolean executeInTransaction = configuration.isExecuteInTransaction()
            && executionGroup.shouldExecuteInTransaction();
        if (executeInTransaction) {
            experimentalDatabase.startTransaction();
        }

        doIndividualMigration(
            executionGroup.migrations().get(0),
            experimentalDatabase,
            configuration,
            migrateResult,
            installedRank,
            parsingContext,
            callbackManager);

        return installedRank + 1;
    }

    private void doIndividualMigration(final MigrationInfo migrationInfo,
        final ExperimentalDatabase experimentalDatabase,
        final Configuration configuration,
        final MigrateResult migrateResult,
        final int installedRank,
        final ParsingContext parsingContext,
        final CallbackManager callbackManager) {
        final StopWatch watch = new StopWatch();
        watch.start();

        final boolean outOfOrder = migrationInfo.getState() == MigrationState.OUT_OF_ORDER && configuration.isOutOfOrder();
        final String migrationText = toMigrationText(migrationInfo, false, experimentalDatabase, outOfOrder);
        final Executor<String> executor = ExecutorFactory.getExecutor(experimentalDatabase, configuration);
        final Reader<String> reader = ReaderFactory.getReader(experimentalDatabase, configuration);
        
        try {
            if (configuration.isSkipExecutingMigrations()) {
                LOG.debug("Skipping execution of migration of " + migrationText);
            } else {
                LOG.debug("Starting migration of " + migrationText + " ...");
                if (!migrationInfo.getType().isUndo()) {
                    callbackManager.handleEvent(Event.BEFORE_EACH_MIGRATE, experimentalDatabase, configuration, parsingContext);
                }
                if (!migrationInfo.getType().isUndo()) {
                    LOG.info("Migrating " + migrationText);
                } else {
                    LOG.info("Undoing migration of " + migrationText);
                }

                if (migrationInfo instanceof final LoadableMigrationInfo loadableMigrationInfo) {
                    final String executionUnit = reader.read(configuration, experimentalDatabase, parsingContext,
                        loadableMigrationInfo.getLoadableResource(), null).findFirst().get();
                    executor.execute(experimentalDatabase, executionUnit, configuration);
                    executor.finishExecution(experimentalDatabase, configuration);
                }

                if (!migrationInfo.getType().isUndo()) {
                    callbackManager.handleEvent(Event.AFTER_EACH_MIGRATE, experimentalDatabase, configuration, parsingContext);
                }
            }
        } catch (final Exception e) {
            watch.stop();
            final int totalTimeMillis = (int) watch.getTotalTimeMillis();
            handleMigrationError(e,
                experimentalDatabase,
                migrationInfo,
                migrateResult,
                configuration.getTable(),
                configuration.isOutOfOrder(),
                installedRank,
                experimentalDatabase.getInstalledBy(configuration),
                totalTimeMillis);
        }

        watch.stop();

        migrateResult.migrationsExecuted += 1;
        final int totalTimeMillis = (int) watch.getTotalTimeMillis();
        migrateResult.putSuccessfulMigration(migrationInfo, totalTimeMillis);
        if (migrationInfo.isVersioned()) {
            migrateResult.targetSchemaVersion = migrationInfo.getVersion().getVersion();
        }
        migrateResult.migrations.add(CommandResultFactory.createMigrateOutput(migrationInfo, totalTimeMillis, null));
        updateSchemaHistoryTable(configuration.getTable(),
            migrationInfo,
            totalTimeMillis,
            installedRank,
            experimentalDatabase,
            experimentalDatabase.getInstalledBy(configuration),
            true);
    }

    private void handleMigrationError(final Exception e,
        final ExperimentalDatabase experimentalDatabase,
        final MigrationInfo migrationInfo,
        final MigrateResult migrateResult,
        final String schemaHistoryTableName,
        final boolean outOfOrder,
        final int installedRank,
        final String installedBy,
        final int totalTimeMillis) {
        final String migrationText = toMigrationText(migrationInfo, false, experimentalDatabase, outOfOrder);
        final String failedMsg;
        if (!migrationInfo.getType().isUndo()) {
            failedMsg = "Migration of " + migrationText + " failed!";
        } else {
            failedMsg = "Undo of migration of " + migrationText + " failed!";
        }

        migrateResult.putFailedMigration(migrationInfo, totalTimeMillis);
        migrateResult.setSuccess(false);

        LOG.error(failedMsg + " Please restore backups and roll back database and code!");
        updateSchemaHistoryTable(schemaHistoryTableName,
            migrationInfo,
            totalTimeMillis,
            installedRank,
            experimentalDatabase,
            installedBy,
            false);

        throw new FlywayMigrateException(migrationInfo,
            calculateErrorMessage(e, migrationInfo),
            true, migrateResult);
    }

    private String calculateErrorMessage(final Exception e, final MigrationInfo migrationInfo) {

        final String title = "Script " + Paths.get(migrationInfo.getScript()).getFileName() + " failed";

        LoadableResource loadableResource = null;
        if (migrationInfo instanceof final LoadableMigrationInfo loadableMigrationInfo) {
            loadableResource = loadableMigrationInfo.getLoadableResource();
        }

        return ErrorUtils.calculateErrorMessage(e, title,
            loadableResource,
            migrationInfo.getPhysicalLocation(),
            null,
            null,
            "Message    : " + e.getMessage() + "\n");
    }
}
