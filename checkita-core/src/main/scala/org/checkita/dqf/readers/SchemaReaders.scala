package org.checkita.dqf.readers

import org.apache.avro.Schema
import org.apache.avro.Schema.Parser
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.avro.SchemaConverters
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.checkita.dqf.config.jobconf.Schemas._
import org.checkita.dqf.connections.schemaregistry.SchemaRegistryConnection
import org.checkita.dqf.utils.ResultUtils._

import java.io.File
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object SchemaReaders {

  /**
   * Source schema: standard representation for all explicitly defined schemas:
   * all schemas are converted to spark StructType.
   * @param id Schema ID
   * @param schema Schema in form of StructType
   * @param columnWidths Sequence of column widths in order (to support fixed-width file reading)
   * @param rawAvro Raw avro schema representation (string in JSON format).
   *                In case when Avro schema is read from file, the original schema is stored to
   *                prevent unnecessary conversions.
   */
  final case class SourceSchema(
                                 id: String,
                                 schema: StructType,
                                 columnWidths: Seq[Int] = Seq.empty,
                                 rawAvro: Option[String] = None
                               ) {

    // todo: fix decimal type conversion:
    //       by default spark decimal type is converted to fixed avro type.
    //       But often decimal type is represented as bytes type in avro schema.
    //       As conversion is fixed, then rawAvro will become unnecessary and will be removed.
    /**
     * Converts Spark StructType into Avro schema rendered as JSON string.
     *
     * @return Avro schema JSON string
     */
    def toAvroSchema: String = rawAvro.getOrElse(
      SchemaConverters.toAvroType(schema, nullable = false, recordName = id).toString
    )
  }
  
  object SourceSchema {

    /**
     * Gets avro schema parser.
     *
     * @param validateDefaults Boolean flag enabling or disabling default values validation in Avro schema.
     * @return Avro schema parser
     */
    private def getParser(validateDefaults: Boolean): Parser =
      new Parser().setValidateDefaults(validateDefaults)

    /**
     * Builds SourceSchema instance from parsed avro schema.
     *
     * @param id         Schema ID
     * @param avroSchema Parsed Avro schema
     * @return SourceSchema instance
     */
    private def fromAvroSchema(id: String, avroSchema: Schema): SourceSchema = {
      val sparkSchema = SchemaConverters
        .toSqlType(avroSchema)
        .dataType
        .asInstanceOf[StructType]
      SourceSchema(id, sparkSchema, rawAvro = Some(avroSchema.toString))
    }

    /**
     * Construct SourceSchema from file with Avro schema.
     *
     * @param id               Schema ID
     * @param schema           File with Avro schema (.avsc)
     * @param validateDefaults Boolean flag enabling or disabling default values validation in Avro schema.
     * @return SourceSchema instance
     */
    def parseAvro(id: String, schema: File, validateDefaults: Boolean): SourceSchema = {
      val schemaParser = getParser(validateDefaults)
      val avroSchema = schemaParser.parse(schema)
      fromAvroSchema(id, avroSchema)
    }

    /**
     * Construct SourceSchema from Avro schema string.
     *
     * @param id               Schema ID
     * @param schema           Avro schema (string)
     * @param validateDefaults Boolean flag enabling or disabling default values validation in Avro schema.
     * @return SourceSchema instance
     */
    def parseAvro(id: String, schema: String, validateDefaults: Boolean): SourceSchema = {
      val schemaParser = getParser(validateDefaults)
      val avroSchema = schemaParser.parse(schema)
      fromAvroSchema(id, avroSchema)
    }
  }

  /**
   * Base schema reader trait
   * @tparam T Type of schema configuration
   */
  sealed trait SchemaReader[T <: SchemaConfig] {

    /**
     * Tries to read schema given the schema configuration
     *
     * @param config Schema configuration
     * @param spark    Implicit spark session object
     * @return Parsed schema
     */
    def tryToRead(config: T)(implicit spark: SparkSession): SourceSchema

    /**
     * Safely reads schema given schema configuration
     *
     * @param config Schema configuration
     * @param spark    Implicit spark session object
     * @return Either a parsed schema or a list of schema reading errors.
     */
    def read(config: T)(implicit spark: SparkSession): Result[SourceSchema] =
      Try(tryToRead(config)).toResult(
        preMsg = s"Unable to read schema '${config.id.value}' due to following error: "
      )
  }

  /**
   * Delimited schema reader (schema defined explicitly in job configuration file)
   */
  implicit object DelimitedSchemaReader extends SchemaReader[DelimitedSchemaConfig] {

    /**
     * Tries to read schema given the schema configuration
     *
     * @param config Delimited schema configuration
     * @param spark    Implicit spark session object
     * @return Parsed schema
     */
    def tryToRead(config: DelimitedSchemaConfig)(implicit spark: SparkSession): SourceSchema =
      SourceSchema(config.id.value, StructType(config.schema.value.map { col =>
        StructField(col.name.value, col.`type`, nullable = true)
      }))
  }

  /**
   * Reader for fixed schema with fully defined columns (name, type, width)
   */
  implicit object FixedFullSchemaReader extends SchemaReader[FixedFullSchemaConfig] {

    /**
     * Tries to read schema given the schema configuration
     *
     * @param config Fixed full schema configuration
     * @param spark    Implicit spark session object
     * @return Parsed schema
     */
    def tryToRead(config: FixedFullSchemaConfig)(implicit spark: SparkSession): SourceSchema = {
      val (columns, colWidths) = config.schema.value.map { col =>
        (StructField(col.name.value, col.`type`, nullable = true), col.width.value)
      }.unzip
      SourceSchema(config.id.value, StructType(columns), colWidths)
    }
  }

  /**
   * Reader for fixed schema with shortly defined columns (name, width)
   *
   * @note Type of columns is always a StringType
   */
  implicit object FixedShortSchemaReader extends SchemaReader[FixedShortSchemaConfig] {

    /**
     * Tries to read schema given the schema configuration
     *
     * @param config Fixed short schema configuration
     * @param spark    Implicit spark session object
     * @return Parsed schema
     */
    def tryToRead(config: FixedShortSchemaConfig)(implicit spark: SparkSession): SourceSchema = {
      val (columns, colWidths) = config.schema.value.map { col =>
        val (name, width) = col.value.split(":", 2) match {
          case Array(n, w) if w.forall(_.isDigit) => n -> w.toInt
          case _ => throw new IllegalArgumentException(
            s"Error reading fixedShortSchema with ID '${config.id.value}': unable to parse column '${col.value}'."
          )
        }
        (StructField(name, StringType, nullable = true), width)
      }.unzip
      SourceSchema(config.id.value, StructType(columns), colWidths)
    }
  }

  /**
   * Hive schema reader: retrieves schema for given Hive `schema.table`
   */
  implicit object HiveSchemaReader extends SchemaReader[HiveSchemaConfig] {

    /**
     * Tries to read schema given the schema configuration
     *
     * @param config Hive schema configuration
     * @param spark    Implicit spark session object
     * @return Parsed schema
     */
    def tryToRead(config: HiveSchemaConfig)(implicit spark: SparkSession): SourceSchema = {
      val sparkSchema = StructType(
        spark.read.table(s"${config.schema.value}.${config.table.value}").schema
          .filterNot(col => config.excludeColumns.map(_.value).contains(col.name))
      )
      SourceSchema(config.id.value, sparkSchema)
    }
  }

  /**
   * Avro schema reader: reads schema from .avsc file
   */
  implicit object AvroSchemaReader extends SchemaReader[AvroSchemaConfig] {

    /**
     * Tries to read schema given the schema configuration
     *
     * @param config Avro schema configuration
     * @param spark    Implicit spark session object
     * @return Parsed schema
     */
    def tryToRead(config: AvroSchemaConfig)(implicit spark: SparkSession): SourceSchema =
      SourceSchema.parseAvro(config.id.value, new File(config.schema.value), config.validateDefaults)
  }

  implicit object RegistrySchemaReader extends SchemaReader[RegistrySchemaConfig] {
    /**
     * Executes a function with retries in case of failure, waiting for a fixed delay between attempts.
     *
     * @param attempts  Maximum number of attempts. Must be greater than 0.
     * @param backOffMs Delay in milliseconds between retries.
     * @param fn        The function to execute.
     * @return          The result of the successfully executed function, or throws an exception if all attempts fail.
     */
    @tailrec
    private def retry(attempts: Int, backOffMs: Int)(fn: => String): String = {
      Try(fn) match {
        case Success(result) => result
        case Failure(e) =>
          if (attempts > 1) {
            Thread.sleep(backOffMs)
            retry(attempts - 1, backOffMs)(fn)
          } else throw e
      }
    }

    /**
     * Tries to read schema given the schema configuration
     *
     * @param config Schema configuration
     * @param spark  Implicit spark session object
     * @return Parsed schema
     */
    override def tryToRead(config: RegistrySchemaConfig)(implicit spark: SparkSession): SourceSchema = {
      val conn = SchemaRegistryConnection(config)
      val rawSchema = (config.schemaId, config.schemaSubject.map(_.value)) match {
        case (Some(id), None) =>
          retry(config.retryAttempts, config.retryIntervalMs){
            conn.getSchemaById(id, config.version)
          }
        case (None, Some(subject)) =>
          retry(config.retryAttempts, config.retryIntervalMs){
            conn.getSchemaBySubject(subject, config.version)
          }
        case _ => throw new IllegalArgumentException(
          "Schema can be read from registry either by its ID or by its subject but not both."
        )
      }
      SourceSchema.parseAvro(config.id.value, rawSchema, config.validateDefaults)
    }
  }
  
  /**
   * General schema reader: invokes read method from schema reader that matches provided schema configuration
   */
  implicit object AnySchemaReader extends SchemaReader[SchemaConfig] {
    /**
     * Tries to read schema given the schema configuration
     *
     * @param config Any schema configuration
     * @param spark    Implicit spark session object
     * @return Parsed schema
     */
    def tryToRead(config: SchemaConfig)(implicit spark: SparkSession): SourceSchema = config match {
      case delimited: DelimitedSchemaConfig => DelimitedSchemaReader.tryToRead(delimited)
      case fixedFull: FixedFullSchemaConfig => FixedFullSchemaReader.tryToRead(fixedFull)
      case fixedSort: FixedShortSchemaConfig => FixedShortSchemaReader.tryToRead(fixedSort)
      case hive: HiveSchemaConfig => HiveSchemaReader.tryToRead(hive)
      case avro: AvroSchemaConfig => AvroSchemaReader.tryToRead(avro)
      case registry: RegistrySchemaConfig => RegistrySchemaReader.tryToRead(registry)
      case other => throw new IllegalArgumentException(s"Unsupported schema type: '${other.getClass.getTypeName}'")
    }
  }

  /**
   * Implicit conversion for schema configurations that enables read method for them.
   * @param config Schema configuration
   * @param reader Implicit reader for given schema configuration
   * @param spark Implicit spark session object
   * @tparam T Type of schema configuration
   */
  implicit class SchemaReaderOps[T <: SchemaConfig](config: T)
                                                   (implicit reader: SchemaReader[T],
                                                    spark: SparkSession) {
    def read: Result[SourceSchema] = reader.read(config)
  }
}
