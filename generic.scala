package generic.library
import java.sql.{DriverManager, Timestamp}
import java.util.Properties

import com.databricks.dbutils_v1.DBUtilsHolder.dbutils
import com.microsoft.azure.sqldb.spark.bulkcopy.BulkCopyMetadata
import com.microsoft.azure.sqldb.spark.config.Config
import com.microsoft.azure.sqldb.spark.connect._
import com.microsoft.azure.sqldb.spark.query._
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType, TimestampType}
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SparkSession}

import scala.util.Try

case class TableColumn(column: Int, columnName: String, columnType: Int, precision: Int, scale: Int)

class GenericLibrary {

  dbutils.widgets.text("Hostname", "")
  var Hostname = dbutils.widgets.get("Hostname")
  print ("Param -\'Hostname':")
  print (Hostname)

  dbutils.widgets.text("Database", "")
  var Database = dbutils.widgets.get("Database")
  print ("Param -\'Database':")
  print (Database)

  dbutils.widgets.text("Port", "")
  var Port = dbutils.widgets.get("Port")
  print ("Param -\'Port':")
  print (Port)

  dbutils.widgets.text("database_user", "")
  val database_user = dbutils.widgets.get("database_user")
  print ("Param -\'database_user':")
  print (database_user)

  dbutils.widgets.text("database_password", "")
  val key = dbutils.widgets.get("database_password")
  print ("Param -\'key':")
  print (key)

  dbutils.widgets.text("database_secret_scope", "")
  val scope = dbutils.widgets.get("database_secret_scope")
  print ("Param -\'scope':")
  print (scope)


  val database_password = dbutils.secrets.get(scope , key )

  dbutils.widgets.text("SourceName", "")
  val source_name = dbutils.widgets.get("SourceName")
  print ("Param -\'source_name':")
  print (source_name)

  dbutils.widgets.text("TargetTable", "")
  val TargetName = dbutils.widgets.get("TargetTable")
  print ("Param -\'TargetTable':")
  print (TargetName)

  dbutils.widgets.text("StartTime", "")
  val StartTime = dbutils.widgets.get("StartTime")
  print ("Param -\'StartTime':")
  print (StartTime)

  dbutils.widgets.text("TargetSchema", "")
  val TargetSchema = dbutils.widgets.get("TargetSchema")
  print ("Param -\'TargetSchema':")
  print (TargetSchema)
  val TargetTable = TargetSchema+"."+TargetName

  //Added bulkCopy Configs
  dbutils.widgets.text("bulkCopyBatchSize", "1048576")
  val bulkCopyBatchSize = dbutils.widgets.get("bulkCopyBatchSize")
  print ("Param -\'bulkCopyBatchSize':")
  print (bulkCopyBatchSize)

  dbutils.widgets.text("bulkCopyTableLock", "true")
  val bulkCopyTableLock = dbutils.widgets.get("bulkCopyTableLock")
  print ("Param -\'bulkCopyTableLock':")
  print (bulkCopyTableLock)

  dbutils.widgets.text("bulkCopyTimeout", "600000")
  val bulkCopyTimeout = dbutils.widgets.get("bulkCopyTimeout")
  print ("Param -\'bulkCopyTimeout':")
  print (bulkCopyTimeout)

  var  v_insertrowcount_source = 0
  var v_extractrowcount_source = 0
  var  v_updaterowcount_source = 0
  var  v_deleterowcount_source = 0
  var v_errorrowcount_source = 0



  def readFromFile(spark: SparkSession, FileName: String) : DataFrame = {
    spark.read.format("csv").option("header", "true").option("inferSchema", "true").load(FileName)
  }

  def writeToFile(df: DataFrame, FileName: String, saveMode: String) : Unit = {
    df.write.format("com.databricks.spark.csv").option("header", "true").mode(saveMode).save(FileName)
  }

  def readDB(sqlContext: SQLContext, SourceTable_query: String) : DataFrame = {
    val config = Config(Map(
      "url"            -> Hostname,
      "databaseName"   -> Database,
      "dbTable"        -> SourceTable_query,
      "user"           -> database_user,
      "password"       -> database_password,
      "connectTimeout" -> "50000", //second
      "queryTimeout"   -> "50000"  //seconds
    ))
    sqlContext.read.sqlDB(config)
  }

  def writeToDB(df: DataFrame, saveMode: String) : Unit = {
    val config2 = Config(Map(
      "url"            -> Hostname,
      "databaseName"   -> Database,
      "dbTable"        -> TargetTable,
      "user"           -> database_user,
      "password"       -> database_password,
      "connectTimeout" -> "50000", //seconds
      "queryTimeout"   -> "50000"  //seconds
    ))
    df.write.mode(saveMode).sqlDB(config2)
  }


  def updateDB(sqlContext: SQLContext, query: String) : Unit = {
    val config = Config(Map(
      "url"            -> Hostname,
      "databaseName"   -> Database,
      "queryCustom"    -> query,
      "user"           -> database_user,
      "password"       -> database_password,
      "connectTimeout" -> "50000", //seconds
      "queryTimeout"   -> "50000"  //seconds
    ))
    sqlContext.sqlDBQuery(config)
  }


  private def cacheMetadataFromDeltaLake(spark: SparkSession, table:  String): Unit = {
    import spark.implicits._

    Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
    val driverClass = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    val properties = new Properties()
    properties.put("user", database_user)
    properties.put("password", database_password)
    properties.setProperty("Driver", driverClass)

    val url = s"jdbc:sqlserver://$Hostname:$Port;database=$Database"
    val connection = DriverManager.getConnection(url, properties)

    val meta = connection.createStatement().executeQuery(s"SELECT TOP 0 * FROM $table").getMetaData
    (1 to meta.getColumnCount).map { i =>
      TableColumn(
        i, meta.getColumnName(i), meta.getColumnType(i),
        meta.getPrecision(i), meta.getScale(i))
    }.toDS.coalesce(1).write.format("delta")
      .save(s"/mnt/deltalake/schemas/$table")

    connection.close()
  }

  private def retrieveMetadataFromDeltaLake(spark: SparkSession, table: String): BulkCopyMetadata = {
    import spark.implicits._

    val path = s"/mnt/deltalake/schemas/${table}"

    val cols: Seq[TableColumn] = Try(spark.read.format("delta").load(path)).recover {
      case e: Exception =>  {
        // Assume it tailed because metadata is not cached yet
        cacheMetadataFromDeltaLake(spark, table)

        spark.read.format("delta").load(path)
      }}.get.as[TableColumn].collect()

    val meta = new BulkCopyMetadata

    for {
      TableColumn(column, columnName, columnType, precision, scale) <- cols
    } meta.addColumnMetadata(
      column,
      columnName,
      columnType,
      precision,
      scale
    )
    meta
  }

  def bulkCopyToDB(df: DataFrame) : Unit = {
    val bulkCopyMetadata: BulkCopyMetadata = retrieveMetadataFromDeltaLake(
      df.sparkSession, TargetTable
    )
    val bulkCopyConfig = Config(Map(
      "url"            -> Hostname,
      "databaseName"   -> Database,
      "dbTable"        -> TargetTable,
      "user"           -> database_user,
      "password"       -> database_password,
      "bulkCopyBatchSize" -> bulkCopyBatchSize,
      "bulkCopyTableLock" -> bulkCopyTableLock,
      "bulkCopyTimeout"   -> bulkCopyTimeout
    ))
    df.bulkCopyToSqlDB(bulkCopyConfig, bulkCopyMetadata)
  }

  def logProcessEnd(error: String, extractrowcount_source: Int, insertrowcount_source: Int, updaterowcount_source: Int,deleterowcount_source: Int,errorrowcount_source: Int ) : Unit = {

    val Status = "C"
    //val Error = error.substring(100)
    val errorString = "error:"
    val NoExtractRowsString = "NoExtractRows:"
    val NoInsertRowsString = "NoInsertRows:"
    val NoUpdateRowsString = "NoUpdateRows:"
    val NoDeletedRowsString = "NoDeletedRows:"
    val NoErroredRowsString = "NoErroredRows:"
    val df_process_log_test = s"""[[$errorString$error,$NoExtractRowsString$extractrowcount_source,$NoInsertRowsString$insertrowcount_source,$NoUpdateRowsString$updaterowcount_source,$NoDeletedRowsString$deleterowcount_source,$NoErroredRowsString$errorrowcount_source]]"""
    dbutils.notebook.exit(df_process_log_test)

  }

  def auditTargetTable(spark: SparkSession, e: String, extractrowcount_source: Int, insertrowcount_source: Int, updaterowcount_source: Int,deleterowcount_source: Int,errorrowcount_source: Int): Unit = {
    var cSchema = List(StructField("Entity_Name", StringType)
      ,StructField("ExtractRowCount", IntegerType)
      ,StructField("InsertRowCount", IntegerType)
      ,StructField("UpdateRowCount", IntegerType)
      ,StructField("DeleteRowCount", IntegerType)
      ,StructField("ErrorRowCount", IntegerType)
      ,StructField("StartTime", TimestampType)
      ,StructField("EndTime", TimestampType)
      ,StructField("Error", StringType)
      ,StructField("Task_Run_Id", StringType))
    var audit_log = Seq(Row(TargetTable,extractrowcount_source , insertrowcount_source, updaterowcount_source, deleterowcount_source, errorrowcount_source, Timestamp.valueOf(StartTime.replaceAll("T", " ").substring( 0, StartTime.indexOf("."))), Timestamp.valueOf(StartTime.replaceAll("T", " ").substring( 0, StartTime.indexOf("."))), e, ""))

    var df_audit_log = spark.createDataFrame(spark.sparkContext.parallelize(audit_log), StructType(cSchema))

    val config2 = Config(Map(
      "url"            -> Hostname,
      "databaseName"   -> Database,
      "dbTable"        -> TargetTable,
      "user"           -> database_user,
      "password"       -> database_password,
      "connectTimeout" -> "50000", //seconds
      "queryTimeout"   -> "50000"  //seconds
    ))

    val notebookExit = e.contains("NotebookExit")
    if (!notebookExit) {
        df_audit_log.write.mode("append").sqlDB(config2)
    }


  }




}


