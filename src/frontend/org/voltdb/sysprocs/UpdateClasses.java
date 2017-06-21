/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.sysprocs;

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.voltcore.logging.VoltLogger;
import org.voltdb.ClientResponseImpl;
import org.voltdb.VoltDB;
import org.voltdb.VoltProcedure.VoltAbortException;
import org.voltdb.client.ClientResponse;
import org.voltdb.compiler.CatalogChangeResult;
import org.voltdb.compiler.CatalogChangeResult.PrepareDiffFailureException;
import org.voltdb.compiler.deploymentfile.DrRoleType;

/**
 * Non-transactional procedure to implement public @UpdateClasses system procedure.
 *
 */
public class UpdateClasses extends UpdateApplicationBase {

    VoltLogger log = new VoltLogger("HOST");

    public CompletableFuture<ClientResponse> run(byte[] jarfileBytes, String classesToDeleteSelector) throws Exception {
        DrRoleType drRole = DrRoleType.fromValue(VoltDB.instance().getCatalogContext().getCluster().getDrrole());

        boolean useDDLSchema = VoltDB.instance().getCatalogContext().cluster.getUseddlschema();

        if (!allowPausedModeWork(false, isAdminConnection())) {
            return makeQuickResponse(
                    ClientResponse.SERVER_UNAVAILABLE,
                    "Server is paused and is available in read-only mode - please try again later.");
        }
        // We have an @UAC.  Is it okay to run it?
        // If we weren't provided operationBytes, it's a deployment-only change and okay to take
        // master and adhoc DDL method chosen
        if (!useDDLSchema) {
            return makeQuickResponse(
                    ClientResponse.GRACEFUL_FAILURE,
                    "Cluster is configured to use @UpdateApplicationCatalog " +
                    "to change application schema.  Use of @UpdateClasses is forbidden.");
        }

        CatalogChangeResult ccr = null;
        try {
            ccr = prepareApplicationCatalogDiff("@UpdateClasses",
                                                jarfileBytes,
                                                classesToDeleteSelector,
                                                new String[0],
                                                null,
                                                false, /* isPromotion */
                                                drRole,
                                                useDDLSchema,
                                                false,
                                                getHostname(),
                                                getUsername());
        }
        catch (PrepareDiffFailureException pe) {
            hostLog.info("A request to update the loaded classes has been rejected. More info returned to client.");
            return makeQuickResponse(pe.statusCode, pe.getMessage());
        }

        // Log something useful about catalog upgrades when they occur.
        if (ccr.upgradedFromVersion != null) {
            hostLog.info(String.format("In order to update the application catalog it was "
                    + "automatically upgraded from version %s.",
                    ccr.upgradedFromVersion));
        }

        // case for @CatalogChangeResult
        if (ccr.encodedDiffCommands.trim().length() == 0) {
            return makeQuickResponse(ClientResponseImpl.SUCCESS, "Catalog update with no changes was skipped.");
        }


        // THE OLD METHOD
//        return callProcedure("@UpdateCore",
//                                             ccr.encodedDiffCommands,
//                                             ccr.catalogHash,
//                                             ccr.catalogBytes,
//                                             ccr.expectedCatalogVersion,
//                                             ccr.deploymentString,
//                                             ccr.tablesThatMustBeEmpty,
//                                             ccr.reasonsForEmptyTables,
//                                             ccr.requiresSnapshotIsolation ? 1 : 0,
//                                             ccr.worksWithElastic ? 1 : 0,
//                                             ccr.deploymentHash,
//                                             ccr.requireCatalogDiffCmdsApplyToEE ? 1 : 0,
//                                             ccr.hasSchemaChange ?  1 : 0,
//                                             ccr.requiresNewExportGeneration ? 1 : 0);

        // Write the new catalog to a temporary jar file
        CompletableFuture<Map<Integer,ClientResponse>> cf =
                                                      callNTProcedureOnAllHosts(
                                                      "@WriteCatalog",
                                                      ccr.encodedDiffCommands,
                                                      ccr.catalogHash,
                                                      ccr.catalogBytes,
                                                      ccr.expectedCatalogVersion,
                                                      ccr.deploymentString,
                                                      ccr.tablesThatMustBeEmpty,
                                                      ccr.reasonsForEmptyTables,
                                                      ccr.requiresSnapshotIsolation ? 1 : 0,
                                                      ccr.worksWithElastic ? 1 : 0,
                                                      ccr.deploymentHash,
                                                      ccr.requireCatalogDiffCmdsApplyToEE ? 1 : 0,
                                                      ccr.hasSchemaChange ?  1 : 0,
                                                      ccr.requiresNewExportGeneration ? 1 : 0);

        Map<Integer, ClientResponse>  map = null;
        try {
            map = cf.get();
        } catch (InterruptedException | ExecutionException e) {
            hostLog.warn("A request to update the loaded classes has failed. More info returned to client.");
            // Revert the changes in ZooKeeper
            throw new VoltAbortException(e);
        }

        if (map != null) {
            for (Entry<Integer, ClientResponse> entry : map.entrySet()) {
                if (entry.getValue().getStatus() != ClientResponseImpl.SUCCESS) {
                    hostLog.warn("A response from one host for writing the catalog jar has failed.");
                    throw new VoltAbortException("A response from host " + entry.getKey() +
                                                 " for writing the catalog jar has failed.");
                }
            }
        }

//        return makeQuickResponse(ClientResponseImpl.SUCCESS, "Catalog update finished.");

      return callProcedure("@UpdateCore",
                          ccr.encodedDiffCommands,
                          ccr.catalogHash,
                          ccr.catalogBytes,
                          ccr.expectedCatalogVersion,
                          ccr.deploymentString,
                          ccr.tablesThatMustBeEmpty,
                          ccr.reasonsForEmptyTables,
                          ccr.requiresSnapshotIsolation ? 1 : 0,
                          ccr.worksWithElastic ? 1 : 0,
                          ccr.deploymentHash,
                          ccr.requireCatalogDiffCmdsApplyToEE ? 1 : 0,
                          ccr.hasSchemaChange ?  1 : 0,
                          ccr.requiresNewExportGeneration ? 1 : 0);






        // Change the way we write the new catalog jar. A NP proc is first called to
        // write a temporary jar on each host. Then a MP proc is called to rename the
        // jar file. This way the CI is not blocked for a long time when writing the
        // new jar file. Many of the code snippets are taken from @UpdateCore

        /*
         * Validate that no elastic join is in progress, blocking this catalog update.
         * If this update works with elastic then do the update anyways
         */
//        ZooKeeper zk = VoltDB.instance().getHostMessenger().getZK();
//        if ((ccr.worksWithElastic ? 1 : 0) == 0 &&
//            !zk.getChildren(VoltZK.catalogUpdateBlockers, false).isEmpty()) {
//            throw new VoltAbortException("Can't do a catalog update while an elastic join or rejoin is active");
//        }
//
//        // write uac blocker zk node
//        VoltZK.createCatalogUpdateBlocker(zk, VoltZK.uacActiveBlocker);
//        // check rejoin blocker node
//        if (zk.exists(VoltZK.rejoinActiveBlocker, false) != null) {
//            VoltZK.removeCatalogUpdateBlocker(zk, VoltZK.uacActiveBlocker, log);
//            throw new VoltAbortException("Can't do a catalog update while an elastic join or rejoin is active");
//        }
//
//        try {
//            // Pull the current catalog and deployment version and hash info.  Validate that we're either:
//            // (a) starting a new, valid catalog or deployment update
//            // (b) restarting a valid catalog or deployment update
//            // otherwise, we can bomb out early.  This should guarantee that we only
//            // ever write valid catalog and deployment state to ZK.
//            CatalogAndIds catalogStuff = CatalogUtil.getCatalogFromZK(zk);
//            // New update?
//            // the expected version is the current version of catalog jar  s
//            if (catalogStuff.version == ccr.expectedCatalogVersion) {
//                if (log.isInfoEnabled()) {
//                    log.info("New catalog update from: " + catalogStuff.toString());
//                    log.info("To: catalog hash: " + Encoder.hexEncode(ccr.catalogHash).substring(0, 10) +
//                            ", deployment hash: " + Encoder.hexEncode(ccr.deploymentHash).substring(0, 10));
//                }
//            }
//            // restart?
//            else {
//                if (catalogStuff.version == (ccr.expectedCatalogVersion + 1) &&
//                        Arrays.equals(catalogStuff.getCatalogHash(), ccr.catalogHash) &&
//                        Arrays.equals(catalogStuff.getDeploymentHash(), ccr.deploymentHash)) {
//                    if (log.isInfoEnabled()) {
//                        log.info("Restarting catalog update: " + catalogStuff.toString());
//                    }
//                }
//                else {
//                    String errmsg = "Invalid catalog update. ZooKeeper catalog version: " + catalogStuff.version +
//                            ", expected version: " + ccr.expectedCatalogVersion + ". Catalog or deployment change was planned " +
//                            "against one version of the cluster configuration but that version was " +
//                            "no longer live when attempting to apply the change.  This is likely " +
//                            "the result of multiple concurrent attempts to change the cluster " +
//                            "configuration.  Please make such changes synchronously from a single " +
//                            "connection to the cluster.";
//                    log.warn(errmsg);
//                    throw new VoltAbortException(errmsg);
//                }
//            }
//
//            byte[] deploymentBytes = ccr.deploymentString.getBytes("UTF-8");
//            // update the global version. only one site per node will accomplish this.
//            // others will see there is no work to do and gracefully continue.
//            // then update data at the local site.
//            CatalogUtil.updateCatalogToZK(
//                    zk,
//                    ccr.expectedCatalogVersion + 1,
//                    getID(),
//                    Long.MAX_VALUE, // currently this value is not treated well
//                    ccr.catalogBytes,
//                    ccr.catalogHash,
//                    deploymentBytes);
//
//            // Do we need synchronized catalog verification here ?
//            // TODO
//
//
//            // Write the new catalog to a temporary jar file
//            CompletableFuture<Map<Integer,ClientResponse>> cf =
//                                                          callNTProcedureOnAllHosts(
//                                                          "@WriteCatalog",
//                                                          ccr.encodedDiffCommands,
//                                                          ccr.catalogHash,
//                                                          ccr.catalogBytes,
//                                                          ccr.expectedCatalogVersion,
//                                                          ccr.deploymentString,
//                                                          ccr.tablesThatMustBeEmpty,
//                                                          ccr.reasonsForEmptyTables,
//                                                          ccr.requiresSnapshotIsolation ? 1 : 0,
//                                                          ccr.worksWithElastic ? 1 : 0,
//                                                          ccr.deploymentHash,
//                                                          ccr.requireCatalogDiffCmdsApplyToEE ? 1 : 0,
//                                                          ccr.hasSchemaChange ?  1 : 0,
//                                                          ccr.requiresNewExportGeneration ? 1 : 0);
//
//            Map<Integer, ClientResponse>  map = null;
//            try {
//                map = cf.get();
//            } catch (InterruptedException | ExecutionException e) {
//                hostLog.warn("A request to update the loaded classes has failed. More info returned to client.");
//                // Revert the changes in ZooKeeper
//                CatalogUtil.updateCatalogToZK(
//                                zk,
//                                catalogStuff.version,
//                                catalogStuff.txnId,
//                                catalogStuff.uniqueId,
//                                catalogStuff.catalogBytes,
//                                catalogStuff.getCatalogHash(),
//                                catalogStuff.deploymentBytes);
//                throw new VoltAbortException(e);
//            }
//
//            if (map != null) {
//                for (Entry<Integer, ClientResponse> entry : map.entrySet()) {
//                    if (entry.getValue().getStatus() != ClientResponseImpl.SUCCESS) {
//                        hostLog.warn("A response from one host for writing the catalog jar has failed.");
//                        CatalogUtil.updateCatalogToZK(
//                                        zk,
//                                        catalogStuff.version,
//                                        catalogStuff.txnId,
//                                        catalogStuff.uniqueId,
//                                        catalogStuff.catalogBytes,
//                                        catalogStuff.getCatalogHash(),
//                                        catalogStuff.deploymentBytes);
//                        throw new VoltAbortException("A response from host " + entry.getKey() +
//                                                     " for writing the catalog jar has failed.");
//                    }
//                }
//            }
//
//            // Rename the catalog jar to replace the old one, this should be done in a
//            // synchronous way.
//            callProcedure("@UpdateCore",
//                           ccr.encodedDiffCommands,
//                           ccr.catalogHash,
//                           ccr.catalogBytes,
//                           ccr.expectedCatalogVersion,
//                           ccr.deploymentString,
//                           ccr.tablesThatMustBeEmpty,
//                           ccr.reasonsForEmptyTables,
//                           ccr.requiresSnapshotIsolation ? 1 : 0,
//                           ccr.worksWithElastic ? 1 : 0,
//                           ccr.deploymentHash,
//                           ccr.requireCatalogDiffCmdsApplyToEE ? 1 : 0,
//                           ccr.hasSchemaChange ?  1 : 0,
//                           ccr.requiresNewExportGeneration ? 1 : 0);
//
//        } finally {
//            // remove the uac blocker when exits or there is an exception
//            VoltZK.removeCatalogUpdateBlocker(zk, VoltZK.uacActiveBlocker, log);
//        }
//
//        // This is when the UpdateApplicationCatalog really ends in the blocking path
//        log.info(String.format("Globally updating the current application catalog and deployment " +
//                               "(new hashes %s, %s).",
//                               Encoder.hexEncode(ccr.catalogHash).substring(0, 10),
//                               Encoder.hexEncode(ccr.deploymentHash).substring(0, 10)));

//        return makeQuickResponse(ClientResponseImpl.SUCCESS, "Catalog update finished.");
    }
}
