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

package org.voltdb;

import static org.voltdb.compiler.CatalogChangeResult.CATALOG_CHANGE_NOREPLAY;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.zookeeper_voltpatches.KeeperException;
import org.json_voltpatches.JSONException;
import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.HostMessenger;
import org.voltdb.catalog.Catalog;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Cluster;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Deployment;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.SnapshotSchedule;
import org.voltdb.catalog.Table;
import org.voltdb.compiler.PlannerTool;
import org.voltdb.compiler.deploymentfile.DeploymentType;
import org.voltdb.settings.ClusterSettings;
import org.voltdb.settings.DbSettings;
import org.voltdb.settings.NodeSettings;
import org.voltdb.utils.CatalogUtil;
import org.voltdb.utils.Encoder;
import org.voltdb.utils.InMemoryJarfile;
import org.voltdb.utils.VoltFile;

public class CatalogContext {
    private static final VoltLogger hostLog = new VoltLogger("HOST");

    public static final class ProcedurePartitionInfo {
        VoltType type;
        int index;
        public ProcedurePartitionInfo(VoltType type, int index) {
            this.type = type;
            this.index = index;
        }
    }


    // THE CATALOG!
    public final Catalog catalog;

    // PUBLIC IMMUTABLE CACHED INFORMATION
    public final Cluster cluster;
    public final Database database;
    public final CatalogMap<Procedure> procedures;
    public final CatalogMap<Table> tables;
    public final AuthSystem authSystem;
    public final int catalogVersion;
    private final byte[] catalogHash;
    private final long catalogCRC;
    private final byte[] deploymentBytes;
    public final byte[] deploymentHash;
    public final UUID deploymentHashForConfig;
    public final long m_transactionId;
    public long m_uniqueId;
    public final JdbcDatabaseMetaDataGenerator m_jdbc;
    // Default procs are loaded on the fly
    // The DPM knows which default procs COULD EXIST
    //  and also how to get SQL for them.
    public final DefaultProcedureManager m_defaultProcs;
    public final HostMessenger m_messenger;

    /*
     * Planner associated with this catalog version.
     * Not thread-safe, should only be accessed by AsyncCompilerAgent
     */
    public final PlannerTool m_ptool;

    // PRIVATE
    private final InMemoryJarfile m_jarfile;

    // Some people may be interested in the JAXB rather than the raw deployment bytes.
    private DeploymentType m_memoizedDeployment;

    // database settings. contains both cluster and path settings
    private final DbSettings m_dbSettings;

    //This is same as unique id except when the UAC is building new catalog ccr stands for catalog change replay time.
    public final long m_ccrTime;
    /**
     * Constructor especially used during @CatalogContext update when @param hasSchemaChange is false.
     * When @param hasSchemaChange is true, @param defaultProcManager and @param plannerTool will be created as new.
     * Otherwise, it will try to use the ones passed in to save CPU cycles for performance reason.
     * @param transactionId
     * @param uniqueId
     * @param catalog
     * @param settings
     * @param catalogBytes
     * @param catalogBytesHash
     * @param deploymentBytes
     * @param version
     * @param messenger
     * @param hasSchemaChange
     * @param defaultProcManager
     * @param plannerTool
     * @param ccrTime - Catalog Change Replay Time
     */
    public CatalogContext(
            long transactionId,
            long uniqueId,
            Catalog catalog,
            DbSettings settings,
            byte[] catalogBytes,
            byte[] catalogBytesHash,
            byte[] deploymentBytes,
            int version,
            HostMessenger messenger,
            boolean hasSchemaChange,
            DefaultProcedureManager defaultProcManager,
            PlannerTool plannerTool, long ccrTime)
    {
        m_transactionId = transactionId;
        m_uniqueId = uniqueId;
        //This is only set to something other than m_uniqueId when we are replaying a UAC.
        m_ccrTime = ((ccrTime == CATALOG_CHANGE_NOREPLAY) ? uniqueId : ccrTime);
        // check the heck out of the given params in this immutable class
        if (catalog == null) {
            throw new IllegalArgumentException("Can't create CatalogContext with null catalog.");
        }

        if (deploymentBytes == null) {
            throw new IllegalArgumentException("Can't create CatalogContext with null deployment bytes.");
        }

        if (catalogBytes != null) {
            try {
                m_jarfile = new InMemoryJarfile(catalogBytes);
                catalogCRC = m_jarfile.getCRC();
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }

            if (catalogBytesHash != null) {
                // This is expensive to compute so if it was passed in to us, use it.
                this.catalogHash = catalogBytesHash;
            }
            else {
                this.catalogHash = m_jarfile.getSha1Hash();
            }
        }
        else {
            throw new IllegalArgumentException("Can't create CatalogContext with null catalog bytes.");
        }

        if (settings == null) {
            throw new IllegalArgumentException("Cant't create CatalogContent with null cluster settings");
        }

        this.catalog = catalog;
        cluster = catalog.getClusters().get("cluster");
        database = cluster.getDatabases().get("database");
        procedures = database.getProcedures();
        tables = database.getTables();
        authSystem = new AuthSystem(database, cluster.getSecurityenabled());

        this.m_dbSettings = settings;

        this.deploymentBytes = deploymentBytes;
        this.deploymentHash = CatalogUtil.makeDeploymentHash(deploymentBytes);
        this.deploymentHashForConfig = CatalogUtil.makeDeploymentHashForConfig(deploymentBytes);
        m_memoizedDeployment = null;


        // If there is no schema change, default procedures will not be changed.
        // Also, the planner tool can be almost reused except updating the catalog hash string.
        // When there is schema change, we just reload every default procedure and create new planner tool
        // by applying the existing schema, which are costly in the UAC MP blocking path.
        if (hasSchemaChange) {
            m_defaultProcs = new DefaultProcedureManager(database);
            m_ptool = new PlannerTool(database, catalogHash);
        } else {
            m_defaultProcs = defaultProcManager;
            m_ptool = plannerTool.updateWhenNoSchemaChange(database, catalogBytesHash);;
        }

        m_jdbc = new JdbcDatabaseMetaDataGenerator(catalog, m_defaultProcs, m_jarfile);

        catalogVersion = version;
        m_messenger = messenger;

        if (procedures != null) {
            for (Procedure proc : procedures) {
                if (proc.getSinglepartition()) {
                    ProcedurePartitionInfo ppi = new ProcedurePartitionInfo(VoltType.get((byte)proc.getPartitioncolumn().getType()), proc.getPartitionparameter());
                    proc.setAttachment(ppi);
                }
            }
        }
    }

    /**
     * Constructor of @CatalogConext used when creating brand-new instances.
     * @param transactionId
     * @param uniqueId
     * @param catalog
     * @param settings
     * @param catalogBytes
     * @param catalogBytesHash
     * @param deploymentBytes
     * @param version
     * @param messenger
     */
    public CatalogContext(
            long transactionId,
            long uniqueId,
            Catalog catalog,
            DbSettings settings,
            byte[] catalogBytes,
            byte[] catalogBytesHash,
            byte[] deploymentBytes,
            int version,
            HostMessenger messenger)
    {
        this(transactionId, uniqueId, catalog, settings, catalogBytes, catalogBytesHash, deploymentBytes,
                version, messenger, true, null, null, uniqueId);
    }

    public Cluster getCluster() {
        return cluster;
    }

    public ClusterSettings getClusterSettings() {
        return m_dbSettings.getCluster();
    }

    public NodeSettings getNodeSettings() {
        return m_dbSettings.getNodeSetting();
    }

    public CatalogContext update(
            long txnId,
            long uniqueId,
            byte[] catalogBytes,
            byte[] catalogBytesHash,
            String diffCommands,
            boolean incrementVersion,
            byte[] deploymentBytes,
            HostMessenger messenger,
            boolean hasSchemaChange,
            long ccrTime)
    {
        Catalog newCatalog = catalog.deepCopy();
        newCatalog.execute(diffCommands);
        int incValue = incrementVersion ? 1 : 0;
        // If there's no new catalog bytes, preserve the old one rather than
        // bashing it
        byte[] bytes = catalogBytes;
        if (bytes == null) {
            try {
                bytes = this.getCatalogJarBytes();
            } catch (IOException e) {
                // Failure is not an option
                hostLog.fatal(e.getMessage());
            }
        }
        // Ditto for the deploymentBytes
        byte[] depbytes = deploymentBytes;
        if (depbytes == null) {
            depbytes = this.deploymentBytes;
        }
        CatalogContext retval =
            new CatalogContext(
                    txnId,
                    uniqueId,
                    newCatalog,
                    this.m_dbSettings,
                    bytes,
                    catalogBytesHash,
                    depbytes,
                    catalogVersion + incValue,
                    messenger,
                    hasSchemaChange,
                    m_defaultProcs,
                    m_ptool,
                    ccrTime);
        return retval;
    }

    /**
     * Get a file/entry (as bytes) given a key/path in the source jar.
     *
     * @param key In-jar path to file.
     * @return byte[] or null if the file doesn't exist.
     */
    public byte[] getFileInJar(String key) {
        return m_jarfile.get(key);
    }

    /**
     * Write, replace or update the catalog jar based on different cases. This function
     * assumes any IOException should lead to fatal crash.
     * @param path
     * @param name
     * @throws IOException
     */
    public Runnable writeCatalogJarToFile(String path, String name) throws IOException
    {
        File catalog_file = new VoltFile(path, name);
        File catalog_tmp_file = new VoltFile(path, name + ".tmp");
        if (catalog_file.exists() && catalog_tmp_file.exists())
        {
            // This means a @UpdateCore case, the asynchronous writing of
            // jar file has finished, rename the jar file
            catalog_file.delete();
            catalog_tmp_file.renameTo(catalog_file);
            return null;
        } else if (!catalog_file.exists() && !catalog_tmp_file.exists()) {
            // This happens in the beginning of cluster startup / restart,
            // when the catalog jar does not yet exist. Though the contents
            // written might be a default one and could be overwritten later
            // by @UAC, @UpdateClasses, etc.
            return m_jarfile.writeToFile(catalog_file);
        } else if (catalog_file.exists() && !catalog_tmp_file.exists()) {
            // This may happen during cluster recover step, in this case
            // we must overwrite the file (the file may have been changed)
            catalog_file.delete();
            return m_jarfile.writeToFile(catalog_file);
        }

        // The temporary catalog jar exists, yet the previous catalog jar is gone. This shouldn't happen.
        // Current implementation will crash the local voltdb upon any IOException in this function. So as long as
        // the voltdb instance is running and nobody deletes the previous catalog jar file, this should never
        // happen.
        throw new IOException("Invalid catalog jar status: cannot find any existing catalog stored on disk." +
                "\nPlease make such changes synchronously from a single connection to the cluster.");

    }

    /**
     * Get the raw bytes of a catalog file for shipping around.
     */
    public byte[] getCatalogJarBytes() throws IOException {
        if (m_jarfile == null) {
            return null;
        }
        return m_jarfile.getFullJarBytes();
    }

    /**
     * Get the JAXB XML Deployment object, which is memoized
     */
    public DeploymentType getDeployment()
    {
        if (m_memoizedDeployment == null) {
            m_memoizedDeployment = CatalogUtil.getDeployment(new ByteArrayInputStream(deploymentBytes));
            // This should NEVER happen
            if (m_memoizedDeployment == null) {
                VoltDB.crashLocalVoltDB("The internal deployment bytes are invalid.  This should never occur; please contact VoltDB support with your logfiles.");
            }
        }
        return m_memoizedDeployment;
    }

    /**
     * Get the XML Deployment bytes
     */
    public byte[] getDeploymentBytes()
    {
        return deploymentBytes;
    }

    /**
     * Given a class name in the catalog jar, loads it from the jar, even if the
     * jar is served from an URL and isn't in the classpath.
     *
     * @param procedureClassName The name of the class to load.
     * @return A java Class variable associated with the class.
     * @throws ClassNotFoundException if the class is not in the jar file.
     */
    public Class<?> classForProcedure(String procedureClassName) throws ClassNotFoundException {
        return classForProcedure(procedureClassName, m_jarfile.getLoader());
    }

    public static Class<?> classForProcedure(String procedureClassName, ClassLoader loader)
        throws ClassNotFoundException {
        // this is a safety mechanism to prevent catalog classes overriding VoltDB stuff
        if (procedureClassName.startsWith("org.voltdb.")) {
            return Class.forName(procedureClassName);
        }

        // look in the catalog for the file
        return Class.forName(procedureClassName, true, loader);
    }

    // Generate helpful status messages based on configuration present in the
    // catalog.  Used to generated these messages at startup and after an
    // @UpdateApplicationCatalog
    SortedMap<String, String> getDebuggingInfoFromCatalog(boolean verbose)
    {
        SortedMap<String, String> logLines = new TreeMap<>();

        // topology
        Deployment deployment = cluster.getDeployment().iterator().next();
        int hostCount = m_dbSettings.getCluster().hostcount();
        if (verbose) {
            Map<Integer, Integer> sphMap;
            try {
                sphMap = m_messenger.getSitesPerHostMapFromZK();
            } catch (KeeperException | InterruptedException | JSONException e) {
                hostLog.warn("Failed to get sitesperhost information from Zookeeper", e);
                sphMap = null;
            }
            int kFactor = deployment.getKfactor();
            if (sphMap == null) {
                logLines.put("deployment1",
                        String.format("Cluster has %d hosts with leader hostname: \"%s\". [unknown] local sites count. K = %d.",
                                hostCount, VoltDB.instance().getConfig().m_leader, kFactor));
                logLines.put("deployment2", "Unable to retrieve partition information from the cluster.");
            } else {
                int localSitesCount = sphMap.get(m_messenger.getHostId());
                logLines.put("deployment1",
                        String.format("Cluster has %d hosts with leader hostname: \"%s\". %d local sites count. K = %d.",
                                hostCount, VoltDB.instance().getConfig().m_leader, localSitesCount, kFactor));

                int totalSitesCount = 0;
                for (Map.Entry<Integer, Integer> e : sphMap.entrySet()) {
                    totalSitesCount += e.getValue();
                }
                int replicas = kFactor + 1;
                int partitionCount = totalSitesCount / replicas;
                logLines.put("deployment2",
                        String.format("The entire cluster has %d %s of%s %d logical partition%s.",
                                replicas,
                                replicas > 1 ? "copies" : "copy",
                                        partitionCount > 1 ? " each of the" : "",
                                                partitionCount,
                                                partitionCount > 1 ? "s" : ""));
            }
        }

        // voltdb root
        logLines.put("voltdbroot", "Using \"" + VoltDB.instance().getVoltDBRootPath() + "\" for voltdbroot directory.");

        // partition detection
        if (cluster.getNetworkpartition()) {
            logLines.put("partition-detection", "Detection of network partitions in the cluster is enabled.");
        }
        else {
            logLines.put("partition-detection", "Detection of network partitions in the cluster is not enabled.");
        }

        // security info
        if (cluster.getSecurityenabled()) {
            logLines.put("sec-enabled", "Client authentication is enabled.");
        }
        else {
            logLines.put("sec-enabled", "Client authentication is not enabled. Anonymous clients accepted.");
        }

        // auto snapshot info
        SnapshotSchedule ssched = database.getSnapshotschedule().get("default");
        if (ssched == null || !ssched.getEnabled()) {
            logLines.put("snapshot-schedule1", "No schedule set for automated snapshots.");
        }
        else {
            final String frequencyUnitString = ssched.getFrequencyunit().toLowerCase();
            final char frequencyUnit = frequencyUnitString.charAt(0);
            String msg = "[unknown frequency]";
            switch (frequencyUnit) {
            case 's':
                msg = String.valueOf(ssched.getFrequencyvalue()) + " seconds";
                break;
            case 'm':
                msg = String.valueOf(ssched.getFrequencyvalue()) + " minutes";
                break;
            case 'h':
                msg = String.valueOf(ssched.getFrequencyvalue()) + " hours";
                break;
            }
            logLines.put("snapshot-schedule1", "Automatic snapshots enabled, saved to " + VoltDB.instance().getSnapshotPath() +
                         " and named with prefix '" + ssched.getPrefix() + "'.");
            logLines.put("snapshot-schedule2", "Database will retain a history of " + ssched.getRetain() +
                         " snapshots, generated every " + msg + ".");
        }

        return logLines;
    }

    public long getCatalogCRC() {
        return catalogCRC;
    }

    public byte[] getCatalogHash()
    {
        return catalogHash;
    }

    public String getCatalogLogString() {
        return String.format("Catalog: TXN ID %d, catalog hash %s, deployment hash %s\n",
                                m_transactionId,
                                Encoder.hexEncode(catalogHash).substring(0, 10),
                                Encoder.hexEncode(deploymentHash).substring(0, 10));
    }

    public InMemoryJarfile getCatalogJar() {
        return m_jarfile;
    }
}
