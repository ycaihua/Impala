// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.catalog;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.stats.StatsSetupConst;
import org.apache.log4j.Logger;

import com.cloudera.impala.analysis.CreateTableStmt;
import com.cloudera.impala.analysis.SqlParser;
import com.cloudera.impala.analysis.SqlScanner;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.thrift.TAccessLevel;
import com.cloudera.impala.thrift.TCatalogObject;
import com.cloudera.impala.thrift.TCatalogObjectType;
import com.cloudera.impala.thrift.TColumn;
import com.cloudera.impala.thrift.TTable;
import com.cloudera.impala.thrift.TTableDescriptor;
import com.cloudera.impala.thrift.TTableStats;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Base class for table metadata.
 *
 * This includes the concept of clustering columns, which are columns by which the table
 * data is physically clustered. In other words, if two rows share the same values
 * for the clustering columns, those two rows are most likely colocated. Note that this
 * is more general than Hive's CLUSTER BY ... INTO BUCKETS clause (which partitions
 * a key range into a fixed number of buckets).
 */
public abstract class Table implements CatalogObject {
  private static final Logger LOG = Logger.getLogger(Table.class);

  // Lock used to serialize calls to the Hive MetaStore to work around MetaStore
  // concurrency bugs. Currently used to serialize calls to "getTable()" due to HIVE-5457.
  private static final Object metastoreAccessLock_ = new Object();
  private long catalogVersion_ = Catalog.INITIAL_CATALOG_VERSION;
  private final org.apache.hadoop.hive.metastore.api.Table msTable_;

  protected final TableId id_;
  protected final Db db_;
  protected final String name_;
  protected final String owner_;
  protected TTableDescriptor tableDesc_;
  protected List<FieldSchema> fields_;
  protected TAccessLevel accessLevel_ = TAccessLevel.READ_WRITE;

  // Number of clustering columns.
  protected int numClusteringCols_;

  // estimated number of rows in table; -1: unknown.
  protected long numRows_ = -1;

  // colsByPos[i] refers to the ith column in the table. The first numClusteringCols are
  // the clustering columns.
  protected final ArrayList<Column> colsByPos_;

  // map from lowercase column name to Column object.
  protected final Map<String, Column> colsByName_;

  // The lastDdlTime for this table; -1 if not set
  protected long lastDdlTime_;

  // Set of supported table types.
  protected static EnumSet<TableType> SUPPORTED_TABLE_TYPES = EnumSet.of(
      TableType.EXTERNAL_TABLE, TableType.MANAGED_TABLE, TableType.VIRTUAL_VIEW);

  protected Table(TableId id, org.apache.hadoop.hive.metastore.api.Table msTable, Db db,
      String name, String owner) {
    id_ = id;
    msTable_ = msTable;
    db_ = db;
    name_ = name.toLowerCase();
    owner_ = owner;
    colsByPos_ = Lists.newArrayList();
    colsByName_ = Maps.newHashMap();
    lastDdlTime_ = (msTable_ != null) ?
        CatalogServiceCatalog.getLastDdlTime(msTable_) : -1;
  }

  //number of nodes that contain data for this table; -1: unknown
  public abstract int getNumNodes();
  public abstract TTableDescriptor toThriftDescriptor();
  public abstract TCatalogObjectType getCatalogObjectType();

  /**
   * Populate members of 'this' from metastore info. Reuse metadata from oldValue if the
   * metadata is still valid.
   */
  public abstract void load(Table oldValue, HiveMetaStoreClient client,
      org.apache.hadoop.hive.metastore.api.Table msTbl) throws TableLoadingException;

  /**
   * Updates the lastDdlTime for this Table, if the new value is greater
   * than the existing value. Does nothing if the new value is less than
   * or equal to the existing value.
   */
  public void updateLastDdlTime(long ddlTime) {
    // Ensure the lastDdlTime never goes backwards.
    if (ddlTime > lastDdlTime_) lastDdlTime_ = ddlTime;
  }

  /**
   * Loads the column stats for col from the Hive Metastore.
   */
  protected void loadColumnStats(Column col, HiveMetaStoreClient client) {
    ColumnStatistics colStats = null;
    try {
      colStats = client.getTableColumnStatistics(db_.getName(), name_, col.getName());
    } catch (Exception e) {
      // don't try to load stats for this column
      return;
    }

    // we should never see more than one ColumnStatisticsObj here
    if (colStats.getStatsObj().size() > 1) return;

    if (!ColumnStats.isSupportedColType(col.getType())) {
      LOG.warn(String.format("Column stats are available for table %s / " +
          "column '%s', but Impala does not currently support column stats for this " +
          "type of column (%s)",  name_, col.getName(), col.getType().toString()));
      return;
    }

    // Update the column stats data
    if (!col.updateStats(colStats.getStatsObj().get(0).getStatsData())) {
      LOG.warn(String.format("Applying the column stats update to table %s / " +
          "column '%s' did not succeed because column type (%s) was not compatible " +
          "with the column stats data. Performance may suffer until column stats are" +
          " regenerated for this column.",
          name_, col.getName(), col.getType().toString()));
    }
  }

  /**
   * Returns the value of the ROW_COUNT constant, or -1 if not found.
   */
  protected static long getRowCount(Map<String, String> parameters) {
    if (parameters == null) return -1;
    String numRowsStr = parameters.get(StatsSetupConst.ROW_COUNT);
    if (numRowsStr == null) return -1;
    try {
      return Long.valueOf(numRowsStr);
    } catch (NumberFormatException exc) {
      // ignore
    }
    return -1;
  }

  /**
   * Creates a table of the appropriate type based on the given hive.metastore.api.Table
   * object.
   */
  public static Table fromMetastoreTable(TableId id, Db db,
      org.apache.hadoop.hive.metastore.api.Table msTbl) {
    // Create a table of appropriate type
    Table table = null;
    if (TableType.valueOf(msTbl.getTableType()) == TableType.VIRTUAL_VIEW) {
      table = new View(id, msTbl, db, msTbl.getTableName(), msTbl.getOwner());
    } else if (msTbl.getSd().getInputFormat().equals(HBaseTable.getInputFormat())) {
      table = new HBaseTable(id, msTbl, db, msTbl.getTableName(), msTbl.getOwner());
    } else if (HdfsFileFormat.isHdfsFormatClass(msTbl.getSd().getInputFormat())) {
      table = new HdfsTable(id, msTbl, db, msTbl.getTableName(), msTbl.getOwner());
    }
    return table;
  }

  /**
   * Factory method that creates a new Table from its Thrift representation.
   * Determines the type of table to create based on the Thrift table provided.
   */
  public static Table fromThrift(Db parentDb, TTable thriftTable)
      throws TableLoadingException {
    Table newTable;
    if (!thriftTable.isSetLoad_status() && thriftTable.isSetMetastore_table())  {
      newTable = Table.fromMetastoreTable(new TableId(thriftTable.getId()),
          parentDb, thriftTable.getMetastore_table());
    } else {
      newTable = IncompleteTable.createUninitializedTable(
          TableId.createInvalidId(), parentDb, thriftTable.getTbl_name());
    }
    newTable.loadFromThrift(thriftTable);
    return newTable;
  }

  protected void loadFromThrift(TTable thriftTable) throws TableLoadingException {
    List<TColumn> columns = new ArrayList<TColumn>();
    columns.addAll(thriftTable.getClustering_columns());
    columns.addAll(thriftTable.getColumns());

    fields_ = new ArrayList<FieldSchema>();
    colsByPos_.clear();
    colsByPos_.ensureCapacity(columns.size());
    for (int i = 0; i < columns.size(); ++i) {
      Column col = Column.fromThrift(columns.get(i));
      colsByPos_.add(col.getPosition(), col);
      colsByName_.put(col.getName().toLowerCase(), col);
      fields_.add(new FieldSchema(col.getName(),
        col.getType().toString().toLowerCase(), col.getComment()));
    }

    numClusteringCols_ = thriftTable.getClustering_columns().size();

    // Estimated number of rows
    numRows_ = thriftTable.isSetTable_stats() ?
        thriftTable.getTable_stats().getNum_rows() : -1;

    // Default to READ_WRITE access if the field is not set.
    accessLevel_ = thriftTable.isSetAccess_level() ? thriftTable.getAccess_level() :
        TAccessLevel.READ_WRITE;
  }

  public TTable toThrift() {
    TTable table = new TTable(db_.getName(), name_);
    table.setId(id_.asInt());
    table.setAccess_level(accessLevel_);

    // Populate both regular columns and clustering columns (if there are any).
    table.setColumns(new ArrayList<TColumn>());
    table.setClustering_columns(new ArrayList<TColumn>());
    for (int i = 0; i < colsByPos_.size(); ++i) {
      TColumn colDesc = colsByPos_.get(i).toThrift();
      // Clustering columns come first.
      if (i < numClusteringCols_) {
        table.addToClustering_columns(colDesc);
      } else {
        table.addToColumns(colDesc);
      }
    }

    table.setMetastore_table(getMetaStoreTable());
    if (numRows_ != -1) {
      table.setTable_stats(new TTableStats());
      table.getTable_stats().setNum_rows(numRows_);
    }
    return table;
  }

  public TCatalogObject toTCatalogObject() {
    TCatalogObject catalogObject = new TCatalogObject();
    catalogObject.setType(getCatalogObjectType());
    catalogObject.setCatalog_version(getCatalogVersion());
    catalogObject.setTable(toThrift());
    return catalogObject;
  }

  /*
   * Gets the ColumnType from the given FieldSchema by using Impala's SqlParser.
   * Throws a TableLoadingException if the FieldSchema could not be parsed.
   * The type can either be:
   *   - Supported by Impala, in which case the type is returned.
   *   - A type Impala understands but is not yet implemented (e.g. date), the type is
   *     returned but type.IsSupported() returns false.
   *   - A type Impala can't understand at all, and a TableLoadingException is thrown.
   */
   protected ColumnType parseColumnType(FieldSchema fs) throws TableLoadingException {
     // Wrap the type string in a CREATE TABLE stmt and use Impala's Parser
     // to get the ColumnType.
     // Pick a table name that can't be used.
     String stmt = String.format("CREATE TABLE $DUMMY ($DUMMY %s)", fs.getType());
     SqlScanner input = new SqlScanner(new StringReader(stmt));
     SqlParser parser = new SqlParser(input);
     CreateTableStmt createTableStmt;
     try {
       Object o = parser.parse().value;
       if (!(o instanceof CreateTableStmt)) {
         // Should never get here.
         throw new InternalException("Couldn't parse create table stmt.");
       }
       createTableStmt = (CreateTableStmt) o;
       if (createTableStmt.getColumnDescs().isEmpty()) {
         // Should never get here.
         throw new InternalException("Invalid create table stmt.");
       }
     } catch (Exception e) {
       throw new TableLoadingException(String.format(
           "Unsupported type '%s' in column '%s' of table '%s'",
           fs.getType(), fs.getName(), getName()));
     }
     ColumnType type = createTableStmt.getColumnDescs().get(0).getColType();
     if (type == null) {
       throw new TableLoadingException(String.format(
           "Unsupported type '%s' in column '%s' of table '%s'",
           fs.getType(), fs.getName(), getName()));
     }
     // Return type even if !isSupported() to allow the table loading to succeed.
     return type;
   }

  /**
   * Returns true if this table is not a base table that stores data (e.g., a view).
   * Virtual tables should not be added to the descriptor table sent to the BE, i.e.,
   * toThrift() should not work on virtual tables.
   */
  public boolean isVirtualTable() { return false; }
  public Db getDb() { return db_; }
  public String getName() { return name_; }
  public String getFullName() { return (db_ != null ? db_.getName() + "." : "") + name_; }
  public String getOwner() { return owner_; }
  public ArrayList<Column> getColumns() { return colsByPos_; }

  /**
   * Subclasses should override this if they provide a storage handler class. Currently
   * only HBase tables need to provide a storage handler.
   */
  public String getStorageHandlerClassName() { return null; }

  /**
   * Returns the list of all columns, but with partition columns at the end of
   * the list rather than the beginning. This is equivalent to the order in
   * which Hive enumerates columns.
   */
  public ArrayList<Column> getColumnsInHiveOrder() {
    ArrayList<Column> columns = Lists.newArrayList();
    for (Column column: colsByPos_.subList(numClusteringCols_, colsByPos_.size())) {
      columns.add(column);
    }

    for (Column column: colsByPos_.subList(0, numClusteringCols_)) {
      columns.add(column);
    }
    return columns;
  }

  /**
   * Case-insensitive lookup.
   */
  public Column getColumn(String name) { return colsByName_.get(name.toLowerCase()); }

  /**
   * Returns the metastore.api.Table object this Table was created from. Returns null
   * if the derived Table object was not created from a metastore Table (ex. InlineViews).
   */
  public org.apache.hadoop.hive.metastore.api.Table getMetaStoreTable() {
    return msTable_;
  }

  public int getNumClusteringCols() { return numClusteringCols_; }
  public TableId getId() { return id_; }
  public long getNumRows() { return numRows_; }

  @Override
  public long getCatalogVersion() { return catalogVersion_; }

  @Override
  public void setCatalogVersion(long catalogVersion) {
    catalogVersion_ = catalogVersion;
  }

  @Override
  public boolean isLoaded() { return true; }
}
