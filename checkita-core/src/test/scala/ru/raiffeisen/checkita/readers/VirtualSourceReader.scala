package ru.raiffeisen.checkita.readers

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.apache.spark.sql.functions._
import org.apache.spark.storage.StorageLevel

import ru.raiffeisen.checkita.config.jobconf.Outputs.OrcFileOutputConfig
import ru.raiffeisen.checkita.config.Enums.SparkJoinType
import ru.raiffeisen.checkita.Common._
import ru.raiffeisen.checkita.config.RefinedTypes.ID
import ru.raiffeisen.checkita.config.jobconf.Sources._
import ru.raiffeisen.checkita.core.Source
import ru.raiffeisen.checkita.readers.VirtualSourceReaders._

import spark.implicits._

class VirtualSourceReader extends AnyWordSpec with Matchers {

  "SqlVirtualSourceReader" must {
    "correctly read sql query" in {
      val df = Seq(("1", "first", "f", "ff", "2023-06-21"), ("2", "second", "s", "ss", "2023-06-30"))
        .toDF("id", "name", "entity", "description", "dlk_cob_date")

      val parentSources = Map("hive_source_1" -> Source("hive_source_1", df))

      val sqlConfig = SqlVirtualSourceConfig(
        ID("sqlVS"),
        Refined.unsafeApply(Seq("hive_source_1")),
        "select id, name, entity, description from hive_source_1 where dlk_cob_date == '2023-06-30'",
        Option(StorageLevel.DISK_ONLY),
        Option(OrcFileOutputConfig("tmp/DQ/sqlVS")),
        Seq("batchId")
      )

      val sqlVirtualSource = SqlVirtualSourceReader.read(sqlConfig, parentSources)

      sqlVirtualSource.isRight shouldEqual true

      val sqlVirtualSourceDf = sqlVirtualSource.getOrElse(Source("error", spark.emptyDataFrame)).df
      val correctDf = Seq(("2", "second", "s", "ss")).toDF("id", "name", "entity", "description")

      sqlVirtualSourceDf.count shouldEqual 1
      sqlVirtualSourceDf.collect should contain theSameElementsAs correctDf.collect
    }

    "failed when sql query is wrong" in {
      val df = Seq(("1", "first", "f", "ff", "2023-06-21"), ("2", "second", "s", "ss", "2023-06-30"))
        .toDF("id", "name", "entity", "description", "dlk_cob_date")

      val parentSources = Map("hive_source_1" -> Source("hive_source_1", df))

      val sqlConfig = SqlVirtualSourceConfig(
        ID("sqlVS"),
        Refined.unsafeApply(Seq("hive_source_1")),
        "sele id, name, entity, description from hive_source_1 where dlk_cob_date == '2023-06-30'",
        Option(StorageLevel.DISK_ONLY),
        Option(OrcFileOutputConfig("tmp/DQ/sqlVS")),
        Seq("batchId")
      )

      val sqlVirtualSource = SqlVirtualSourceReader.read(sqlConfig, parentSources)

      sqlVirtualSource.isLeft shouldEqual true
    }
  }

  "JoinVirtualSourceReader" must {
    "correctly read join query" in {
      val df1 = Seq(("1", "first"), ("2", "second")).toDF("id", "name1")
      val df2 = Seq(("1", "third"), ("2", "fourth")).toDF("id", "name2")

      val parentSources = Map(
        "hdfs_avro_source" -> Source("hdfs_avro_source", df1, Seq("id", "order_id")),
        "hdfs_orc_source"  -> Source("hdfs_orc_source", df2, Seq("id", "order_id"))
      )

      val joinConfig = JoinVirtualSourceConfig(
        ID("joinVS"),
        Refined.unsafeApply(Seq("hdfs_avro_source", "hdfs_orc_source")),
        Refined.unsafeApply(Seq("id")),
        SparkJoinType.Left,
        Option(StorageLevel.MEMORY_ONLY),
        Option(OrcFileOutputConfig("tmp/DQ/sqlVS")),
        Seq("batchId")
      )

      val joinVirtualSource = JoinVirtualSourceReader.read(joinConfig, parentSources)

      joinVirtualSource.isRight shouldEqual true

      val joinVirtualSourceDf = joinVirtualSource.getOrElse(Source("error", spark.emptyDataFrame)).df
      val correctDf = Seq(("1", "first", "third"), ("2", "second", "fourth")).toDF("id", "name1", "name2")

      joinVirtualSourceDf.collect should contain theSameElementsAs correctDf.collect
    }

    "failed when pass only 1 source" in {
      val df = Seq(("1", "first"), ("2", "second")).toDF("id", "name")

      val parentSources = Map(
        "hdfs_avro_source" -> Source("hdfs_avro_source", df, Seq("id", "order_id"))
      )

      val joinConfig = JoinVirtualSourceConfig(
        ID("joinVS"),
        Refined.unsafeApply(Seq("hdfs_avro_source")),
        Refined.unsafeApply(Seq("id")),
        SparkJoinType.Left,
        Option(StorageLevel.MEMORY_ONLY),
        Option(OrcFileOutputConfig("tmp/DQ/sqlVS")),
        Seq("batchId")
      )

      val joinVirtualSource = JoinVirtualSourceReader.read(joinConfig, parentSources)

      joinVirtualSource.isLeft shouldEqual true
    }
  }

  "FilterVirtualSourceReader" must {
    "correctly read filter query" in {
      val df = Seq(("1", "first"), (null, "second")).toDF("key", "name")

      val parentSources = Map("kafka_source" -> Source("kafka_source", df))

      val filterConfig = FilterVirtualSourceConfig(
        ID("filterVS"),
        Refined.unsafeApply(Seq("kafka_source")),
        Refined.unsafeApply(Seq(expr("key is not null"))),
        None,
        None,
        Seq("batchId", "dttm")
      )

      val filterVirtualSource = FilterVirtualSourceReader.read(filterConfig, parentSources)

      filterVirtualSource.isRight shouldEqual true

      val filterVirtualSourceDf = filterVirtualSource.getOrElse(Source("error", spark.emptyDataFrame)).df

      filterVirtualSourceDf.count shouldEqual 1
    }

    "failed when pass more than 1 source" in {
      val df1 = Seq(("1", "first"), (null, "second")).toDF("key", "name")
      val df2 = Seq(("2", "second"), (null, "third")).toDF("key", "name")

      val parentSources = Map(
        "kafka_source"     -> Source("kafka_source", df1),
        "hdfs_avro_source" -> Source("hdfs_avro_source", df2)
      )

      val filterConfig = FilterVirtualSourceConfig(
        ID("filterVS"),
        Refined.unsafeApply(Seq("kafka_source", "hdfs_avro_source")),
        Refined.unsafeApply(Seq(expr("key is not null"))),
        None,
        None,
        Seq("batchId", "dttm")
      )

      val filterVirtualSource = FilterVirtualSourceReader.read(filterConfig, parentSources)

      filterVirtualSource.isLeft shouldEqual true
    }
  }

  "SelectVirtualSourceReader" must {
    "correctly read select query" in {
      val df = Seq(("1", "first"), ("2", "second")).toDF("id", "name")

      val parentSources = Map("table_source_1" -> Source("table_source_1", df))

      val selectConfig = SelectVirtualSourceConfig(
        ID("selectVS"),
        Refined.unsafeApply(Seq("table_source_1")),
        Refined.unsafeApply(Seq(expr("count(id) as id_cnt"), expr("count(name) as name_cnt"))),
        None,
        None
      )

      val selectVirtualSource = SelectVirtualSourceReader.read(selectConfig, parentSources)

      selectVirtualSource.isRight shouldEqual true

      val selectVirtualSourceDf = selectVirtualSource.getOrElse(Source("error", spark.emptyDataFrame)).df
      val correctDf = Seq((2, 2)).toDF("id_cnt", "name_cnt")

      selectVirtualSourceDf.collect should contain theSameElementsAs correctDf.collect
    }

    "failed when pass more than 1 source" in {
      val df1 = Seq(("1", "first"), ("2", "second")).toDF("id", "name")
      val df2 = Seq(("3", "3"), ("4", "4")).toDF("id", "name")

      val parentSources = Map(
        "table_source_1" -> Source("table_source_1", df1),
        "table_source_2" -> Source("table_source_2", df2)
      )

      val selectConfig = SelectVirtualSourceConfig(
        ID("selectVS"),
        Refined.unsafeApply(Seq("table_source_1", "table_source_2")),
        Refined.unsafeApply(Seq(expr("count(id) as id_cnt"), expr("count(name) as name_cnt"))),
        None,
        None
      )

      val selectVirtualSource = SelectVirtualSourceReader.read(selectConfig, parentSources)

      selectVirtualSource.isLeft shouldEqual true
    }
  }

  "AggregateVirtualSourceReader" must {
    "correctly read agg query" in {
      val df = Seq(("1", 2, 3), ("1", 4, 5)).toDF("col1", "col2", "col3")

      val parentSources = Map("hdfs_fixed_file" -> Source("hdfs_fixed_file", df))

      val selectConfig = AggregateVirtualSourceConfig(
        ID("aggVS"),
        Refined.unsafeApply(Seq("hdfs_fixed_file")),
        Refined.unsafeApply(Seq("col1")),
        Refined.unsafeApply(Seq(expr("avg(col2) as avg_col2"), expr("sum(col3) as sum_col3"))),
        None,
        None,
        Seq("col1", "avg_col2", "sum_col3")
      )

      val aggregateVirtualSource = AggregateVirtualSourceReader.read(selectConfig, parentSources)

      aggregateVirtualSource.isRight shouldEqual true

      val aggregateVirtualSourceDf = aggregateVirtualSource.getOrElse(Source("error", spark.emptyDataFrame)).df
      val correctDf = Seq(("1", 3.0, 8)).toDF("col1", "avg_col2", "sum_col3")

      aggregateVirtualSourceDf.collect should contain theSameElementsAs correctDf.collect
    }

    "failed when pass more than 1 source" in {
      val df1 = Seq(("1", 2, 3), ("1", 4, 5)).toDF("col1", "col2", "col3")
      val df2 = Seq(("1", 2, 3), ("1", 4, 5)).toDF("col1", "col2", "col3")

      val parentSources = Map(
        "hdfs_fixed_file" -> Source("hdfs_fixed_file", df1),
        "table_source_1"  -> Source("table_source_2", df2)
      )

      val selectConfig = AggregateVirtualSourceConfig(
        ID("aggVS"),
        Refined.unsafeApply(Seq("hdfs_fixed_file", "table_source_1")),
        Refined.unsafeApply(Seq("col1")),
        Refined.unsafeApply(Seq(expr("avg(col2) as avg_col2"), expr("sum(col3) as sum_col3"))),
        None,
        None,
        Seq("col1", "avg_col2", "sum_col3")
      )

      val aggregateVirtualSource = AggregateVirtualSourceReader.read(selectConfig, parentSources)

      aggregateVirtualSource.isLeft shouldEqual true
    }
  }

}