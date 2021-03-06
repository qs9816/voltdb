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

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;

import org.hsqldb_voltpatches.HSQLInterface;
import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.Pair;
import org.voltdb.CatalogContext;
import org.voltdb.ClientResponseImpl;
import org.voltdb.OperationMode;
import org.voltdb.VoltDB;
import org.voltdb.VoltNTSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.catalog.Catalog;
import org.voltdb.catalog.CatalogDiffEngine;
import org.voltdb.client.ClientResponse;
import org.voltdb.common.Constants;
import org.voltdb.compiler.CatalogChangeResult;
import org.voltdb.compiler.CatalogChangeResult.PrepareDiffFailureException;
import org.voltdb.compiler.ClassMatcher;
import org.voltdb.compiler.ClassMatcher.ClassNameMatchStatus;
import org.voltdb.compiler.VoltCompiler;
import org.voltdb.compiler.VoltCompiler.VoltCompilerException;
import org.voltdb.compiler.deploymentfile.DeploymentType;
import org.voltdb.compiler.deploymentfile.DrRoleType;
import org.voltdb.utils.CatalogUtil;
import org.voltdb.utils.Encoder;
import org.voltdb.utils.InMemoryJarfile;

/**
 * Base class for non-transactional sysprocs UpdateApplicationCatalog, UpdateClasses and Promote.
 * *ALSO* the base class for AdHocNTBase, which is the base class for AdHoc, AdHocSPForTest
 * and SwapTables.
 *
 * Has the common code for figuring out what changes need to be passed onto the transactional
 * UpdateCore procedure.
 *
 */
public abstract class UpdateApplicationBase extends VoltNTSystemProcedure {
    protected static final VoltLogger compilerLog = new VoltLogger("COMPILER");
    protected static final VoltLogger hostLog = new VoltLogger("HOST");

    /**
     *
     * @param operationBytes The bytes for the catalog operation, if any. May be null in all cases.
     * For UpdateApplicationCatalog, this will contain the compiled catalog jarfile bytes
     * For UpdateClasses, this will contain the class jarfile bytes
     * For AdHoc DDL work, this will be null
     * @param operationString The string for the catalog operation, if any. May be null in all cases.
     * For UpdateApplicationCatalog, this will contain the deployment string to apply
     * For UpdateClasses, this will contain the class deletion patterns
     * For AdHoc DDL work, this will be null
     */
    public static CatalogChangeResult prepareApplicationCatalogDiff(String invocationName,
                                                                    final byte[] operationBytes,
                                                                    final String operationString,
                                                                    final String[] adhocDDLStmts,
                                                                    final byte[] replayHashOverride,
                                                                    final boolean isPromotion,
                                                                    final DrRoleType drRole,
                                                                    final boolean useAdhocDDL,
                                                                    boolean adminConnection,
                                                                    String hostname,
                                                                    String user)
                                                                            throws PrepareDiffFailureException
    {

        // create the change result and set up all the boiler plate
        CatalogChangeResult retval = new CatalogChangeResult();
        retval.tablesThatMustBeEmpty = new String[0]; // ensure non-null
        retval.hasSchemaChange = true;
        if (replayHashOverride != null) {
            retval.isForReplay = true;
        }

        try {
            // catalog change specific boiler plate
            CatalogContext context = VoltDB.instance().getCatalogContext();
            // Start by assuming we're doing an @UpdateApplicationCatalog.  If-ladder below
            // will complete with newCatalogBytes actually containing the bytes of the
            // catalog to be applied, and deploymentString will contain an actual deployment string,
            // or null if it still needs to be filled in.
            InMemoryJarfile newCatalogJar = null;
            InMemoryJarfile oldJar = context.getCatalogJar().deepCopy();
            String deploymentString = operationString;
            if ("@UpdateApplicationCatalog".equals(invocationName)) {
                // Grab the current catalog bytes if @UAC had a null catalog from deployment-only update
                if ((operationBytes == null) || (operationBytes.length == 0)) {
                    newCatalogJar = oldJar;
                } else {
                    newCatalogJar = CatalogUtil.loadInMemoryJarFile(operationBytes);
                }
                // If the deploymentString is null, we'll fill it in with current deployment later
                // Otherwise, deploymentString has the right contents, don't need to touch it
            }
            else if ("@UpdateClasses".equals(invocationName)) {
                // provided operationString is really a String with class patterns to delete,
                // provided newCatalogJar is the jarfile with the new classes
                if (operationBytes != null) {
                    newCatalogJar = new InMemoryJarfile(operationBytes);
                }
                try {
                    InMemoryJarfile modifiedJar = modifyCatalogClasses(context.catalog, oldJar, operationString,
                            newCatalogJar, drRole == DrRoleType.XDCR, context.m_ptool.getHSQLInterface());
                    if (modifiedJar == null) {
                        newCatalogJar = oldJar;
                    } else {
                        newCatalogJar = modifiedJar;
                    }
                }
                catch (ClassNotFoundException e) {
                    throw new PrepareDiffFailureException(
                            ClientResponse.GRACEFUL_FAILURE,
                            "Unexpected error in @UpdateClasses modifying classes from catalog: " +
                            e.getMessage());
                }
                // Real deploymentString should be the current deployment, just set it to null
                // here and let it get filled in correctly later.
                deploymentString = null;

                // mark it as non-schema change
                retval.hasSchemaChange = false;
            }
            else if ("@AdHoc".equals(invocationName)) {
                // work.adhocDDLStmts should be applied to the current catalog
                try {
                    newCatalogJar = addDDLToCatalog(context.catalog, oldJar, adhocDDLStmts, drRole == DrRoleType.XDCR);
                }
                catch (VoltCompilerException vce) {
                    throw new PrepareDiffFailureException(ClientResponse.GRACEFUL_FAILURE, vce.getMessage());
                }
                catch (IOException ioe) {
                    throw new PrepareDiffFailureException(ClientResponse.UNEXPECTED_FAILURE, ioe.getMessage());
                }
                catch (Throwable t) {
                    String msg = "Unexpected condition occurred applying DDL statements: " + t.toString();
                    compilerLog.error(msg);
                    throw new PrepareDiffFailureException(ClientResponse.UNEXPECTED_FAILURE, msg);
                }
                assert(newCatalogJar != null);
                if (newCatalogJar == null) {
                    // Shouldn't ever get here
                    String msg = "Unexpected failure in applying DDL statements to original catalog";
                    compilerLog.error(msg);
                    throw new PrepareDiffFailureException(ClientResponse.UNEXPECTED_FAILURE, msg);
                }
                // Real deploymentString should be the current deployment, just set it to null
                // here and let it get filled in correctly later.
                deploymentString = null;
            }
            else {
                assert(false); // TODO: this if-chain doesn't feel like it even should exist
            }

            // get the diff between catalogs
            // try to get the new catalog from the params
            Pair<InMemoryJarfile, String> loadResults = null;
            try {
                loadResults = CatalogUtil.loadAndUpgradeCatalogFromJar(newCatalogJar, drRole == DrRoleType.XDCR);
            }
            catch (IOException ioe) {
                // Preserve a nicer message from the jarfile loading rather than
                // falling through to the ZOMG message in the big catch
                throw new PrepareDiffFailureException(ClientResponse.GRACEFUL_FAILURE, ioe.getMessage());
            }
            retval.catalogBytes = loadResults.getFirst().getFullJarBytes();
            if (!retval.isForReplay) {
                retval.catalogHash = loadResults.getFirst().getSha1Hash();
            } else {
                retval.catalogHash = replayHashOverride;
            }
            String newCatalogCommands =
                CatalogUtil.getSerializedCatalogStringFromJar(loadResults.getFirst());
            retval.upgradedFromVersion = loadResults.getSecond();
            if (newCatalogCommands == null) {
                throw new PrepareDiffFailureException(
                        ClientResponse.GRACEFUL_FAILURE,
                        "Unable to read from catalog bytes");
            }
            Catalog newCatalog = new Catalog();
            newCatalog.execute(newCatalogCommands);

            // Retrieve the original deployment string, if necessary
            if (deploymentString == null) {
                // Go get the deployment string from the current catalog context
                byte[] deploymentBytes = context.getDeploymentBytes();
                if (deploymentBytes != null) {
                    deploymentString = new String(deploymentBytes, Constants.UTF8ENCODING);
                }
                if (deploymentBytes == null || deploymentString == null) {
                    throw new PrepareDiffFailureException(
                            ClientResponse.GRACEFUL_FAILURE,
                            "No deployment file provided and unable to recover previous deployment settings.");
                }
            }

            // recompile deployment and add to catalog
            // this is necessary, even for @UpdateClasses that does not change deployment
            // because the catalog bytes does not contain any deployments but only schema related contents
            // the command log reply needs it to generate a correct catalog diff
            DeploymentType dt  = CatalogUtil.parseDeploymentFromString(deploymentString);
            if (dt == null) {
                throw new PrepareDiffFailureException(
                        ClientResponse.GRACEFUL_FAILURE,
                        "Unable to update deployment configuration: Error parsing deployment string");
            }
            if (isPromotion && drRole == DrRoleType.REPLICA) {
                assert dt.getDr().getRole() == DrRoleType.REPLICA;
                dt.getDr().setRole(DrRoleType.MASTER);
            }

            String result = CatalogUtil.compileDeployment(newCatalog, dt, false);
            if (result != null) {
                throw new PrepareDiffFailureException(
                        ClientResponse.GRACEFUL_FAILURE,
                        "Unable to update deployment configuration: " + result);
            }

            //In non legacy mode discard the path element.
            if (!VoltDB.instance().isRunningWithOldVerbs()) {
                dt.setPaths(null);
            }

            //Always get deployment after its adjusted.
            retval.deploymentString = CatalogUtil.getDeployment(dt, true);
            // make deployment hash from string
            retval.deploymentHash =
                    CatalogUtil.makeDeploymentHash(retval.deploymentString.getBytes(Constants.UTF8ENCODING));

            // store the version of the catalog the diffs were created against.
            // verified when / if the update procedure runs in order to verify
            // catalogs only move forward
            retval.expectedCatalogVersion = context.catalogVersion;

            // compute the diff in StringBuilder
            CatalogDiffEngine diff = new CatalogDiffEngine(context.catalog, newCatalog);
            if (!diff.supported()) {
                throw new PrepareDiffFailureException(
                        ClientResponse.GRACEFUL_FAILURE,
                        "The requested catalog change(s) are not supported:\n" + diff.errors());
            }

            String commands = diff.commands();
            compilerLog.info(diff.getDescriptionOfChanges("@UpdateClasses".equals(invocationName)));

            retval.requireCatalogDiffCmdsApplyToEE = diff.requiresCatalogDiffCmdsApplyToEE();
            // since diff commands can be stupidly big, compress them here
            retval.encodedDiffCommands = Encoder.compressAndBase64Encode(commands);
            retval.diffCommandsLength = commands.length();
            String emptyTablesAndReasons[][] = diff.tablesThatMustBeEmpty();
            assert(emptyTablesAndReasons.length == 2);
            assert(emptyTablesAndReasons[0].length == emptyTablesAndReasons[1].length);
            retval.tablesThatMustBeEmpty = emptyTablesAndReasons[0];
            retval.reasonsForEmptyTables = emptyTablesAndReasons[1];
            retval.requiresSnapshotIsolation = diff.requiresSnapshotIsolation();
            retval.requiresNewExportGeneration = diff.requiresNewExportGeneration();
            retval.worksWithElastic = diff.worksWithElastic();
        }
        catch (PrepareDiffFailureException e) {
            throw e;
        }
        catch (Exception e) {
            String msg = "Unexpected error in adhoc or catalog update: " + e.getClass() + ", " +
                e.getMessage();
            compilerLog.warn(msg, e);
            throw new PrepareDiffFailureException(ClientResponse.UNEXPECTED_FAILURE, msg);
        }

        return retval;
    }

    /**
     * Append the supplied adhoc DDL to the current catalog's DDL and recompile the
     * jarfile
     * @throws VoltCompilerException
     */
    protected static InMemoryJarfile addDDLToCatalog(Catalog oldCatalog, InMemoryJarfile jarfile, String[] adhocDDLStmts, boolean isXDCR)
    throws IOException, VoltCompilerException
    {
        StringBuilder sb = new StringBuilder();
        compilerLog.info("Applying the following DDL to cluster:");
        for (String stmt : adhocDDLStmts) {
            compilerLog.info("\t" + stmt);
            sb.append(stmt);
            sb.append(";\n");
        }
        String newDDL = sb.toString();
        compilerLog.trace("Adhoc-modified DDL:\n" + newDDL);

        VoltCompiler compiler = new VoltCompiler(isXDCR);
        compiler.compileInMemoryJarfileWithNewDDL(jarfile, newDDL, oldCatalog);
        return jarfile;
    }

    /**
     * @return NUll if no classes changed, otherwise return the update jar file.
     *
     * @throws ClassNotFoundException
     * @throws VoltCompilerException
     * @throws IOException
     */
    private static InMemoryJarfile modifyCatalogClasses(Catalog catalog, InMemoryJarfile jarfile, String deletePatterns,
            InMemoryJarfile newJarfile, boolean isXDCR, HSQLInterface hsql)
                    throws IOException, ClassNotFoundException, VoltCompilerException
    {
        // modify the old jar in place based on the @UpdateClasses inputs, and then
        // recompile it if necessary
        boolean deletedClasses = false;
        if (deletePatterns != null) {
            String[] patterns = deletePatterns.split(",");
            ClassMatcher matcher = new ClassMatcher();
            // Need to concatenate all the classnames together for ClassMatcher
            String currentClasses = "";
            for (String classname : jarfile.getLoader().getClassNames()) {
                currentClasses = currentClasses.concat(classname + "\n");
            }
            matcher.m_classList = currentClasses;
            for (String pattern : patterns) {
                ClassNameMatchStatus status = matcher.addPattern(pattern.trim());
                if (status == ClassNameMatchStatus.MATCH_FOUND) {
                    deletedClasses = true;
                }
            }

            for (String classname : matcher.getMatchedClassList()) {
                jarfile.removeClassFromJar(classname);
            }
        }
        boolean foundClasses = false;
        if (newJarfile != null) {
            for (Entry<String, byte[]> e : newJarfile.entrySet()) {
                String filename = e.getKey();
                // Ignore root level, non-class file names
                boolean isRootFile = Paths.get(filename).getNameCount() == 1;
                if (isRootFile && !filename.endsWith(".class")) {
                    continue;
                }
                foundClasses = true;
                jarfile.put(e.getKey(), e.getValue());
            }
        }
        if (!deletedClasses && !foundClasses) {
            return null;
        }

        compilerLog.info("Updating java classes available to stored procedures");
        VoltCompiler compiler = new VoltCompiler(isXDCR);
        try {
            compiler.compileInMemoryJarfileForUpdateClasses(jarfile, catalog, hsql);
        } catch (ClassNotFoundException | VoltCompilerException | IOException ex) {
            throw ex;
        }

        return jarfile;
    }

    /** Check if something should run based on admin/paused/internal status */
    static protected boolean allowPausedModeWork(boolean internalCall, boolean adminConnection) {
        return (VoltDB.instance().getMode() != OperationMode.PAUSED ||
                internalCall ||
                adminConnection);
    }

    /** Error generating shortcut method */
    static protected CompletableFuture<ClientResponse> makeQuickResponse(byte statusCode, String msg) {
        ClientResponseImpl cri = new ClientResponseImpl(statusCode, new VoltTable[0], msg);
        CompletableFuture<ClientResponse> f = new CompletableFuture<>();
        f.complete(cri);
        return f;
    }
}
