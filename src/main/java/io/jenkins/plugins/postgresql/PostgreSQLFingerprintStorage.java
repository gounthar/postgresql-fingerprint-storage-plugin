/*
 * The MIT License
 *
 * Copyright (c) 2023, Jenkins project contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.jenkins.plugins.postgresql;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Fingerprint;
import hudson.model.TaskListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Hashtable;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.fingerprints.FingerprintStorage;
import jenkins.model.FingerprintFacet;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.jenkinsci.plugins.database.Database;
import org.jenkinsci.plugins.database.GlobalDatabaseConfiguration;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Pluggable external fingerprint storage for fingerprints into PostgreSQL.
 */
@Extension
public class PostgreSQLFingerprintStorage extends FingerprintStorage {

    private final String instanceId;
    private static final Logger LOGGER = Logger.getLogger(PostgreSQLFingerprintStorage.class.getName());

    private transient ConnectionSupplier connectionSupplier;

    public static PostgreSQLFingerprintStorage get() {
        return ExtensionList.lookupSingleton(PostgreSQLFingerprintStorage.class);
    }

    @DataBoundConstructor
    public PostgreSQLFingerprintStorage() throws IOException {
        instanceId = Util.getDigestOf(
                new ByteArrayInputStream(InstanceIdentity.get().getPublic().getEncoded()));
    }

    public ConnectionSupplier getConnectionSupplier() {
        if (connectionSupplier == null) {
            connectionSupplier = new LocalConnectionSupplier();
        }
        return connectionSupplier;
    }

    /**
     * Saves the given fingerprint inside the PostgreSQL instance.
     */
    public synchronized void save(@NonNull Fingerprint fingerprint) throws IOException {
        try (Connection connection = getConnectionSupplier().connection()) {
            connection.setAutoCommit(false);

            delete(fingerprint.getHashString(), connection);

            try (PreparedStatement preparedStatement =
                    connection.prepareStatement(Queries.getQuery(Queries.INSERT_FINGERPRINT))) {
                preparedStatement.setString(1, fingerprint.getHashString());
                preparedStatement.setString(2, instanceId);
                preparedStatement.setTimestamp(
                        3, new Timestamp(fingerprint.getTimestamp().getTime()));
                preparedStatement.setString(4, fingerprint.getFileName());

                Fingerprint.BuildPtr original = fingerprint.getOriginal();
                if (original != null) {
                    preparedStatement.setString(5, original.getName());
                    preparedStatement.setInt(6, original.getNumber());
                } else {
                    preparedStatement.setNull(5, Types.NULL);
                    preparedStatement.setNull(6, Types.NULL);
                }

                preparedStatement.executeUpdate();
            }

            Hashtable<String, Fingerprint.RangeSet> usages = fingerprint.getUsages();
            if (usages != null) {
                for (Map.Entry<String, Fingerprint.RangeSet> usage : usages.entrySet()) {
                    String jobName = usage.getKey();
                    Fingerprint.RangeSet rangeSet = usage.getValue();

                    for (int buildNumber : rangeSet.listNumbers()) {
                        try (PreparedStatement preparedStatement = connection.prepareStatement(
                                Queries.getQuery(Queries.INSERT_FINGERPRINT_JOB_BUILD_RELATION))) {
                            preparedStatement.setString(1, fingerprint.getHashString());
                            preparedStatement.setString(2, instanceId);
                            preparedStatement.setString(3, jobName);
                            preparedStatement.setInt(4, buildNumber);
                            preparedStatement.executeUpdate();
                        }
                    }
                }
            }

            for (FingerprintFacet fingerprintFacet : fingerprint.getPersistedFacets()) {
                JSONObject fingerprintFacetJSON =
                        new JSONObject(XStreamHandler.getXStream().toXML(fingerprintFacet));
                String fingerprintFacetName = fingerprintFacetJSON.keys().next();
                String fingerprintFacetEntry =
                        fingerprintFacetJSON.getJSONObject(fingerprintFacetName).toString();

                try (PreparedStatement preparedStatement =
                        connection.prepareStatement(Queries.getQuery(Queries.INSERT_FINGERPRINT_FACET_RELATION))) {
                    preparedStatement.setString(1, fingerprint.getHashString());
                    preparedStatement.setString(2, instanceId);
                    preparedStatement.setString(3, fingerprintFacetName);
                    preparedStatement.setString(4, fingerprintFacetEntry);
                    preparedStatement.setBoolean(5, fingerprintFacet.isFingerprintDeletionBlocked());

                    preparedStatement.executeUpdate();
                }
            }

            connection.commit();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "PostgreSQL failed in saving fingerprint: " + fingerprint.toString(), e);
            throw new IOException(e);
        }
    }

    /**
     * Returns the fingerprint associated with the given unique id and the Jenkins instance ID, from the PostgreSQL
     * instance.
     */
    public @CheckForNull Fingerprint load(@NonNull String id) throws IOException {
        try (Connection connection = getConnectionSupplier().connection();
                PreparedStatement preparedStatement =
                        connection.prepareStatement(Queries.getQuery(Queries.SELECT_FINGERPRINT))) {

            preparedStatement.setString(1, id);
            preparedStatement.setString(2, instanceId);

            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                Map<String, String> fingerprintMetadata = DataConversion.extractFingerprintMetadata(
                        id,
                        resultSet.getTimestamp(ColumnName.TIMESTAMP),
                        resultSet.getString(ColumnName.FILENAME),
                        resultSet.getString(ColumnName.ORIGINAL_JOB_NAME),
                        resultSet.getString(ColumnName.ORIGINAL_JOB_BUILD_NUMBER));
                Map<String, Fingerprint.RangeSet> usageMetadata =
                        DataConversion.extractUsageMetadata(resultSet.getString(ColumnName.USAGES));
                JSONArray facets = DataConversion.extractFacets(resultSet.getString(ColumnName.FACETS));
                String json = DataConversion.constructFingerprintJSON(fingerprintMetadata, usageMetadata, facets);
                return (Fingerprint) XStreamHandler.getXStream().fromXML(json);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "PostgreSQL failed in loading fingerprint: " + id, e);
            throw new IOException(e);
        }
    }

    /**
     * Deletes the fingerprint with the given id from the PostgreSQL instance.
     */
    public void delete(@NonNull String id) throws IOException {
        try (Connection connection = getConnectionSupplier().connection()) {
            connection.setAutoCommit(false);
            delete(id, connection);
            connection.commit();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "PostgreSQL failed in deleting fingerprint: " + id, e);
            throw new IOException(e);
        }
    }

    private void delete(@NonNull String id, @NonNull Connection connection) throws SQLException {
        try (PreparedStatement preparedStatement =
                connection.prepareStatement(Queries.getQuery(Queries.DELETE_FINGERPRINT))) {
            preparedStatement.setString(1, id);
            preparedStatement.setString(2, instanceId);
            preparedStatement.executeUpdate();
        }
    }

    /**
     * Returns true if there are fingerprints associate with the instance ID inside PostgreSQL instance.
     */
    public boolean isReady() {
        try (Connection connection = getConnectionSupplier().connection();
                PreparedStatement preparedStatement =
                        connection.prepareStatement(Queries.getQuery(Queries.SELECT_FINGERPRINT_EXISTS_FOR_INSTANCE))) {
            preparedStatement.setString(1, instanceId);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getBoolean(ColumnName.EXISTS);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed connecting to PostgreSQL.", e);
        }
        return false;
    }

    @Override
    public void iterateAndCleanupFingerprints(TaskListener taskListener) {
        // TODO
    }

    static class LocalConnectionSupplier extends ConnectionSupplier {
        @Override
        protected Database database() {
            return GlobalDatabaseConfiguration.get().getDatabase();
        }

        @Override
        protected void initialize(Connection connection) throws SQLException {
            if (!DatabaseSchemaLoader.MIGRATED) {
                DatabaseSchemaLoader.migrateSchema();
            }
        }
    }

    abstract static class ConnectionSupplier implements AutoCloseable {

        private transient Connection connection;

        protected abstract Database database();

        protected void initialize(Connection connection) throws SQLException {}

        synchronized Connection connection() throws SQLException {
            if (connection == null || connection.isClosed()) {
                Connection _connection = database().getDataSource().getConnection();
                initialize(_connection);
                connection = _connection;
            }
            return connection;
        }

        @Override
        public void close() {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            connection = null;
        }
    }

    @Extension
    public static class DescriptorImpl extends PostgreSQLFingerprintStorageDescriptor {}
}
