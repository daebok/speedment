/**
 *
 * Copyright (c) 2006-2016, Speedment, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.speedment.internal.core.db;

import com.speedment.db.SqlFunction;
import com.speedment.db.SqlSupplier;
import com.speedment.Speedment;
import com.speedment.config.db.Column;
import com.speedment.config.db.Dbms;
import com.speedment.config.db.ForeignKey;
import com.speedment.config.db.ForeignKeyColumn;
import com.speedment.config.db.Index;
import com.speedment.config.db.IndexColumn;
import com.speedment.config.db.PrimaryKeyColumn;
import com.speedment.config.db.Schema;
import com.speedment.config.db.Table;
import com.speedment.config.db.parameters.OrderType;
import com.speedment.internal.core.manager.sql.SqlStatement;
import com.speedment.internal.core.manager.sql.SqlUpdateStatement;
import com.speedment.db.AsynchronousQueryResult;
import com.speedment.db.DbmsHandler;
import com.speedment.exception.SpeedmentException;
import com.speedment.config.db.mapper.TypeMapper;
import com.speedment.db.DatabaseNamingConvention;
import com.speedment.config.db.mutator.ForeignKeyColumnMutator;
import com.speedment.config.db.parameters.DbmsType;
import com.speedment.db.SqlPredicate;
import com.speedment.internal.logging.Logger;
import com.speedment.internal.logging.LoggerManager;
import com.speedment.util.sql.SqlTypeInfo;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

import java.util.stream.Stream;
import static com.speedment.internal.util.document.DocumentDbUtil.dbmsTypeOf;
import java.util.concurrent.atomic.AtomicBoolean;
import com.speedment.internal.util.document.DocumentDbUtil;
import static java.util.Objects.nonNull;
import static com.speedment.internal.core.stream.OptionalUtil.unwrap;
import static com.speedment.util.NullUtil.requireNonNulls;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

/**
 *
 * @author pemi
 */
public abstract class AbstractRelationalDbmsHandler implements DbmsHandler {

    private static final Logger LOGGER = LoggerManager.getLogger(AbstractRelationalDbmsHandler.class);

//    static {
//        LOGGER.setLevel(Level.DEBUG);
//    }
    private static final String PASSWORD_PROTECTED = "********";

    private final Dbms dbms;
    private final DbmsType dbmsType;
    
    protected transient Map<String, Class<?>> sqlTypeMapping;

    private static final Boolean SHOW_METADATA = false;

    private final Speedment speedment;

    public AbstractRelationalDbmsHandler(Speedment speedment, Dbms dbms) {
        this.speedment = requireNonNull(speedment);
        this.dbms = requireNonNull(dbms);
        this.sqlTypeMapping = new ConcurrentHashMap<>();
        this.dbmsType = dbmsTypeOf(speedment, dbms);
    }

    @Override
    public Dbms getDbms() {
        return dbms;
    }

    // Todo: Use DataSource instead: http://docs.oracle.com/javase/tutorial/jdbc/basics/sqldatasources.html
    public Connection getConnection() {
        final String url = DocumentDbUtil.findConnectionUrl(speedment, dbms);
        final String user = unwrap(dbms.getUsername());
        final char[] password = unwrap(speedment.getPasswordComponent().get(dbms));

        final Connection conn;
        try {
            conn = speedment.getConnectionPoolComponent()
                .getConnection(url, user, password == null ? null : new String(password));
        } catch (final SQLException ex) {
            final String msg
                = "Unable to get connection for " + dbms
                + " using url \"" + url + "\", user = " + user
                + ", password = " + PASSWORD_PROTECTED;

            LOGGER.error(ex, msg);
            throw new SpeedmentException(msg, ex);
        }

        return conn;
    }

    protected Map<String, Class<?>> readTypeMapFromDB(Connection connection) throws SQLException {
        requireNonNull(connection);

        final Map<String, Class<?>> result = new ConcurrentHashMap<>();
        try (final ResultSet rs = connection.getMetaData().getTypeInfo()) {
            while (rs.next()) {
                final SqlTypeInfo typeInfo = SqlTypeInfo.from(rs);
                final Class<?> mappedClass = speedment.getSqlTypeMapperComponent().apply(dbms, typeInfo);
                result.put(typeInfo.getSqlTypeName(), mappedClass);
            }
        }

        return result;
    }

    @Override
    public void readSchemaMetadata(Predicate<String> filterCriteria) {
        try (final Connection connection = getConnection()) {
            readSchemaMetadata(connection, filterCriteria);
        } catch (SQLException sqle) {
            LOGGER.error(sqle, "Unable to read from " + dbms.toString());
        }
    }

    protected void readSchemaMetadata(Connection connection, Predicate<String> filterCriteria) {
        requireNonNull(connection);
        LOGGER.info("Reading metadata from " + dbms.toString());

        final Set<String> discardedSchemas = new HashSet<>();
        final DatabaseNamingConvention naming = dbmsType.getDatabaseNamingConvention();

        try {
            final Set<SqlTypeInfo> preSet = dbmsType.getDataTypes();
            sqlTypeMapping = !preSet.isEmpty() ? readTypeMapFromSet(preSet) : readTypeMapFromDB(connection);

            try (final ResultSet rs = connection.getMetaData().getSchemas(null, null)) {
                while (rs.next()) {

                    final String schemaName = rs.getString(dbmsType.getResultSetTableSchema());
                    String catalogName = "";
                    try {
                        // This column is not there for Oracle so handle it
                        // gracefully....
                        catalogName = rs.getString("TABLE_CATALOG");
                    } catch (final SQLException ex) {
                        LOGGER.info("TABLE_CATALOG not in result set.");
                    }

                    boolean schemaWasUsed = false;
                    if (!naming.getSchemaExcludeSet().contains(schemaName)) {
                        final String name = Optional.ofNullable(schemaName).orElse(catalogName);
                        if (filterCriteria.test(name)) {
                            final Schema schema = dbms.mutator().addNewSchema();
                            schema.mutator().setName(name);
                            schemaWasUsed = true;
                        }
                    }

                    if (!schemaWasUsed) {
                        discardedSchemas.add(schemaName);
                    }
                }
            }

            try (final ResultSet catalogResultSet = connection.getMetaData().getCatalogs()) {
                while (catalogResultSet.next()) {
                    final String schemaName = catalogResultSet.getString(1);

                    boolean schemaWasUsed = false;
                    if (filterCriteria.test(schemaName)) {
                        if (!naming.getSchemaExcludeSet().contains(schemaName)) {
                            final Schema schema = dbms.mutator().addNewSchema();
                            schema.mutator().setName(schemaName);
                            schemaWasUsed = true;
                        }
                    }

                    if (!schemaWasUsed) {
                        discardedSchemas.add(schemaName);
                    }
                }
            }

        } catch (final SQLException sqle) {
            throw new SpeedmentException(sqle);
        }

        final AtomicBoolean atleastOneSchema = new AtomicBoolean(false);
        dbms.schemas().forEach(schema -> {
            tables(connection, schema);
            atleastOneSchema.set(true);
        });

        if (!atleastOneSchema.get()) {
            throw new SpeedmentException(
                "Could not find anymatching schema. The following schemas was considered: " + discardedSchemas + "."
            );
        }
    }

    protected void tables(Connection connection, Schema schema) {
        requireNonNulls(connection, schema);
        LOGGER.info("Parsing " + schema.toString());

        try {
            try (final ResultSet rsTable = connection.getMetaData().getTables(jdbcCatalogLookupName(schema), jdbcSchemaLookupName(schema), null, new String[]{"TABLE"})) {

                final ResultSetMetaData rsmd = rsTable.getMetaData();
                int numberOfColumns = rsmd.getColumnCount();

                if (SHOW_METADATA) {
                    for (int x = 1; x <= numberOfColumns; x++) {
                        LOGGER.debug(rsmd.getColumnName(x) + ", " + rsmd.getColumnClassName(x) + ", " + rsmd.getColumnType(x));
                    }
                }

                while (rsTable.next()) {
                    if (SHOW_METADATA) {
                        for (int x = 1; x <= numberOfColumns; x++) {
                            LOGGER.debug(rsmd.getColumnName(x) + ":'" + rsTable.getObject(x) + "'");
                        }
                    }
                    final Table table = schema.mutator().addNewTable();
                    final String tableName = rsTable.getString("TABLE_NAME");
                    table.mutator().setName(tableName);
                }
            }
        } catch (SQLException sqle) {
            throw new SpeedmentException(sqle);
        }

        schema.tables().forEach(table -> {
            columns(connection, table);
            indexes(connection, table);
            foreignKeys(connection, table);
            primaryKeyColumns(connection, table);
        });
    }

    protected void columns(Connection connection, Table table) {
        requireNonNulls(connection, table);

        final Schema schema = table.getParent().get();

        final SqlSupplier<ResultSet> supplier = ()
            -> connection.getMetaData().getColumns(
                jdbcCatalogLookupName(schema),
                jdbcSchemaLookupName(schema),
                table.getName(),
                null
            );

        final TableChildMutator<Column, ResultSet> mutator = (column, rs) -> {

            column.mutator().setName(rs.getString("COLUMN_NAME"));
            column.mutator().setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));

            final boolean nullable;
            final int nullableValue = rs.getInt("NULLABLE");
            switch (nullableValue) {
                case DatabaseMetaData.columnNullable:
                case DatabaseMetaData.columnNullableUnknown: {
                    nullable = true;
                    break;
                }
                case DatabaseMetaData.columnNoNulls: {
                    nullable = false;
                    break;
                }
                default:
                    throw new SpeedmentException("Unknown nullable type " + nullableValue);
            }

            column.mutator().setNullable(nullable);

            final String classMappingString = rs.getString("TYPE_NAME");
            final int columnSize = rs.getInt("COLUMN_SIZE");
            final int decimalDigits = rs.getInt("DECIMAL_DIGIT");
            final Class<?> mapping = lookupJdbcClass(classMappingString, columnSize, decimalDigits);
            if (mapping != null) {

                final TypeMapper<?, ?> typeMapper = speedment.getTypeMapperComponent().stream()
                    .filter(tm -> Objects.equals(mapping, tm.getDatabaseType()))
                    .filter(tm -> Objects.equals(mapping, tm.getJavaType()))
                    .findFirst().orElseThrow(() -> new SpeedmentException(
                        "Found no identity type mapper for mapping '" + mapping.getName() + "'."
                    ));

                column.mutator().setTypeMapper(typeMapper);
                column.mutator().setDatabaseType(mapping);

            } else {
                LOGGER.warn("Unable to determine mapping for table " + table.getName() + ", column " + column.getName());
            }

            try {
                column.mutator().setAutoIncrement(rs.getBoolean("IS_AUTOINCREMENT"));
            } catch (final SQLException sqle) {
                LOGGER.warn("Unable to determine IS_AUTOINCREMENT for table " + table.getName() + ", column " + column.getName());
            }
        };

        tableChilds(table.mutator()::addNewColumn, supplier, mutator);
    }

    /**
     * Looks up a column TYPE_NAME and returns a mapped Class (e.g. Timestamp or
     * String)
     *
     * @param typeName
     * @return the mapped Class
     */
    protected Class<?> lookupJdbcClass(String typeName, int columnSize, int decimalDigits) {
        return sqlTypeMapping.get(typeName);
    }

    protected void primaryKeyColumns(Connection connection, Table table) {
        requireNonNulls(connection, table);

        final Schema schema = table.getParent().get();

        final SqlSupplier<ResultSet> supplier = ()
            -> connection.getMetaData().getPrimaryKeys(
                jdbcCatalogLookupName(schema),
                jdbcSchemaLookupName(schema),
                table.getName()
            );

        final TableChildMutator<PrimaryKeyColumn, ResultSet> mutator = (primaryKeyColumn, rs) -> {
            primaryKeyColumn.mutator().setName(rs.getString("COLUMN_NAME"));
            primaryKeyColumn.mutator().setOrdinalPosition(rs.getInt("KEY_SEQ"));
        };

        tableChilds(table.mutator()::addNewPrimaryKeyColumn, supplier, mutator);
    }

    protected void indexes(Connection connection, Table table) {
        requireNonNulls(connection, table);

        final Schema schema = table.getParent().get();
        final SqlSupplier<ResultSet> supplier = ()
            -> connection.getMetaData().getIndexInfo(
                jdbcCatalogLookupName(schema),
                jdbcSchemaLookupName(schema),
                table.getName(),
                false,
                false
            );

        final TableChildMutator<Index, ResultSet> mutator = (index, rs) -> {
            final String indexName = rs.getString("INDEX_NAME");
            final boolean unique = !rs.getBoolean("NON_UNIQUE");

            index.mutator().setName(indexName);
            index.mutator().setUnique(unique);

            final IndexColumn indexColumn = index.mutator().addNewIndexColumn();
            indexColumn.mutator().setName(rs.getString("COLUMN_NAME"));
            indexColumn.mutator().setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
            final String ascOrDesc = rs.getString("ASC_OR_DESC");

            if ("A".equalsIgnoreCase(ascOrDesc)) {
                indexColumn.mutator().setOrderType(OrderType.ASC);
            } else if ("D".equalsIgnoreCase(ascOrDesc)) {
                indexColumn.mutator().setOrderType(OrderType.DESC);
            } else {
                indexColumn.mutator().setOrderType(OrderType.NONE);
            }
        };

        final SqlPredicate<ResultSet> filter = rs -> {
            final String type = rs.getString("TYPE");
            final String indexName = rs.getString("INDEX_NAME");
            return nonNull(indexName);
        };

        tableChilds(table.mutator()::addNewIndex, supplier, mutator, filter);
    }

    protected void foreignKeys(Connection connection, Table table) {
        requireNonNulls(connection, table);

        final Schema schema = table.getParent().get();
        final SqlSupplier<ResultSet> supplier = ()
            -> connection.getMetaData().getImportedKeys(
                jdbcCatalogLookupName(schema),
                jdbcSchemaLookupName(schema),
                table.getName()
            );

        final TableChildMutator<ForeignKey, ResultSet> mutator = (foreignKey, rs) -> {

            final String foreignKeyName = rs.getString("FK_NAME");
            foreignKey.mutator().setName(foreignKeyName);

            final ForeignKeyColumn foreignKeyColumn = foreignKey.mutator().addNewForeignKeyColumn();
            final ForeignKeyColumnMutator fkcMutator = foreignKeyColumn.mutator();
            fkcMutator.setName(rs.getString("FKCOLUMN_NAME"));
            fkcMutator.setOrdinalPosition(rs.getInt("KEY_SEQ"));
            fkcMutator.setForeignTableName(rs.getString("PKTABLE_NAME"));
            fkcMutator.setForeignColumnName(rs.getString("PKCOLUMN_NAME"));
        };

        tableChilds(table.mutator()::addNewForeignKey, supplier, mutator);
    }

    protected <T> void tableChilds(
        Supplier<T> childSupplier,
        SqlSupplier<ResultSet> resultSetSupplier,
        TableChildMutator<T, ResultSet> resultSetMutator
    ) {
        tableChilds(childSupplier, resultSetSupplier, resultSetMutator, rs -> true);
    }

    protected <T> void tableChilds(
        Supplier<T> childSupplier,
        SqlSupplier<ResultSet> resultSetSupplier,
        TableChildMutator<T, ResultSet> resultSetMutator,
        SqlPredicate<ResultSet> filter
    ) {
        requireNonNulls(childSupplier, resultSetSupplier, resultSetMutator);

        try (final ResultSet rsColumn = resultSetSupplier.get()) {

            final ResultSetMetaData rsmd = rsColumn.getMetaData();
            final int numberOfColumns = rsmd.getColumnCount();

            if (SHOW_METADATA) {
                for (int x = 1; x <= numberOfColumns; x++) {
                    LOGGER.debug(rsmd.getColumnName(x) + ", " + rsmd.getColumnClassName(x) + ", " + rsmd.getColumnType(x));
                }
            }

            while (rsColumn.next()) {
                if (SHOW_METADATA) {
                    for (int x = 1; x <= numberOfColumns; x++) {
                        LOGGER.debug(rsmd.getColumnName(x) + ":'" + rsColumn.getObject(x) + "'");
                    }
                }
                if (filter.test(rsColumn)) {
                    resultSetMutator.mutate(childSupplier.get(), rsColumn);
                } else {
                    LOGGER.debug("Skipped due to RS filtering");
                }
            }
        } catch (SQLException sqle) {
            throw new SpeedmentException(sqle);
        }
    }

    protected String jdbcSchemaLookupName(Schema schema) {
        return null;
    }

    protected String jdbcCatalogLookupName(Schema schema) {
        return schema.getName();
    }

    @Override
    public <T> Stream<T> executeQuery(String sql, List<?> values, SqlFunction<ResultSet, T> rsMapper) {
        requireNonNulls(sql, values, rsMapper);

        try (
            final Connection connection = getConnection();
            final PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (final Object o : values) {
                ps.setObject(i++, o);
            }
            final ResultSet rs = ps.executeQuery();

            // Todo: Make a transparent stream with closeHandler added.
            final Stream.Builder<T> streamBuilder = Stream.builder();
            while (rs.next()) {
                streamBuilder.add(rsMapper.apply(rs));
            }
            return streamBuilder.build();
        } catch (SQLException sqle) {
            LOGGER.error(sqle, "Error querying " + sql);
            throw new SpeedmentException(sqle);
        }
    }

    @Override
    public <T> AsynchronousQueryResult<T> executeQueryAsync(
        String sql, List<?> values, Function<ResultSet, T> rsMapper) {

        return new AsynchronousQueryResultImpl<>(
            Objects.requireNonNull(sql),
            Objects.requireNonNull(values),
            Objects.requireNonNull(rsMapper),
            () -> getConnection()
        );
    }

    @Override
    public void executeUpdate(
        String sql, List<?> values, Consumer<List<Long>> generatedKeysConsumer)
        throws SQLException {

        final List<SqlUpdateStatement> sqlStatementList = new ArrayList<>();
        final SqlUpdateStatement sqlUpdateStatement
            = new SqlUpdateStatement(sql, values, generatedKeysConsumer);

        sqlStatementList.add(sqlUpdateStatement);
        executeUpdate(sqlStatementList);
    }

    private void executeUpdate(List<SqlUpdateStatement> sqlStatementList)
        throws SQLException {

        requireNonNull(sqlStatementList);
        int retryCount = 5;
        boolean transactionCompleted = false;

        do {
            SqlStatement lastSqlStatement = null;
            Connection conn = null;
            try {
                conn = getConnection();
                conn.setAutoCommit(false);
                for (final SqlUpdateStatement sqlStatement : sqlStatementList) {
                    try (final PreparedStatement ps = conn.prepareStatement(sqlStatement.getSql(), Statement.RETURN_GENERATED_KEYS)) {

                        int i = 1;
                        for (Object o : sqlStatement.getValues()) {
                            ps.setObject(i++, o);
                        }

                        ps.executeUpdate();

                        try (final ResultSet generatedKeys = ps.getGeneratedKeys()) {
                            while (generatedKeys.next()) {
                                final Object genKey = generatedKeys.getObject(1);
                                if (!"oracle.sql.ROWID".equals(genKey.getClass()
                                    .getName())) {
                                    sqlStatement.addGeneratedKey(generatedKeys.getLong(1));
                                } else {
                                    // Handle ROWID, make result = map<,String>
                                    // instead...
                                }
                            }
                        }

                    }
                }
                conn.commit();
                conn.close();
                conn = null;
                transactionCompleted = true;
            } catch (SQLException sqlEx) {
                LOGGER.error("SqlStatementList: " + sqlStatementList);
                LOGGER.error("SQL: " + lastSqlStatement);
                LOGGER.error(sqlEx, sqlEx.getMessage());
                final String sqlState = sqlEx.getSQLState();

                if ("08S01".equals(sqlState) || "40001".equals(sqlState)) {
                    retryCount--;
                } else {
                    retryCount = 0;
                    throw sqlEx; // Finally will be executed...
                }
            } finally {

                if (!transactionCompleted) {
                    try {
                        // If we got here, and conn is not null, the
                        // transaction should be rolled back, as not
                        // all work has been done
                        try {
                            conn.rollback();
                        } finally {
                            conn.close();
                        }
                    } catch (SQLException sqlEx) {
                        //
                        // If we got an exception here, something
                        // pretty serious is going on, so we better
                        // pass it up the stack, rather than just
                        // logging it. . .
                        LOGGER.error(sqlEx, "Rollback error! connection:" + sqlEx.getMessage());
                        throw sqlEx;
                    }
                }
            }
        } while (!transactionCompleted && (retryCount > 0));

        if (transactionCompleted) {
            sqlStatementList.forEach(SqlUpdateStatement::acceptGeneratedKeys);
        }
    }

    <T> Supplier<T> wrapSupplierInSpeedmentException(SqlSupplier<T> innerSupplier) {
        requireNonNull(innerSupplier);
        return () -> {
            try {
                return innerSupplier.get();
            } catch (SQLException sqle) {
                throw new SpeedmentException(sqle);
            }
        };

    }

    @FunctionalInterface
    protected interface TableChildMutator<T, U> {

        void mutate(T t, U u) throws SQLException;
    }

    protected Map<String, Class<?>> readTypeMapFromSet(Set<SqlTypeInfo> typeInfos) {
        requireNonNull(typeInfos);

        return typeInfos.stream()
            .collect(toMap(
                SqlTypeInfo::getSqlTypeName,
                sti -> speedment.getSqlTypeMapperComponent().apply(dbms, sti))
            );
    }

}
