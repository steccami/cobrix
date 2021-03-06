/*
 * Copyright 2018 Barclays Africa Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.cobrix.spark.cobol.source

import java.io.File

import org.apache.commons.io.FileUtils
import za.co.absa.cobrix.spark.cobol.source.base.SparkCobolTestBase
import za.co.absa.cobrix.spark.cobol.source.utils.SourceTestUtils.createFileInRandomDirectory
import za.co.absa.cobrix.spark.cobol.source.utils.SourceTestUtils.sampleCopybook
import za.co.absa.cobrix.spark.cobol.source.base.impl.DummyCobolSchema
import za.co.absa.cobrix.spark.cobol.source.base.impl.DummyReader
import org.apache.spark.rdd.RDD
import za.co.absa.cobrix.spark.cobol.reader.Reader
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType

import scala.collection.mutable.MapBuilder
import org.scalatest.Matchers._
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.apache.spark.sql.types.StringType
import org.apache.spark.SparkException
import za.co.absa.cobrix.spark.cobol.schema.CobolSchema

class CobolRelationSpec extends SparkCobolTestBase with Serializable {

  private val copybookFileName: String = "testCopybookFile.cob"
  private var copybookFile: File = _
  private var sparkSchema: StructType = _
  private var testData: List[Map[String, Option[String]]] = _
  private var cobolSchema: CobolSchema = _
  private var oneRowRDD: RDD[Array[Byte]] = _

  before {
    copybookFile = createFileInRandomDirectory(copybookFileName, sampleCopybook)
    sparkSchema = createSparkSchema(createTestSchemaMap())
    testData = createTestData()
    cobolSchema = new DummyCobolSchema(sparkSchema)
    oneRowRDD = sqlContext.sparkContext.parallelize(List(Array[Byte]()))
  }

  after {
    // BE CAREFUL when changing this line, DO NOT point at the wrong directory
    println("Removing test dir: "+copybookFile.getParentFile.getAbsolutePath)
    FileUtils.deleteDirectory(copybookFile.getParentFile)
  }

  behavior of "CobolRelation"

  it should "return an RDD[Row] if data are correct" in {
    val testReader: Reader = new DummyReader(sparkSchema, cobolSchema, testData)(() => Unit)
    val relation = new CobolRelation(copybookFile.getParentFile.getAbsolutePath, testReader)(sqlContext)
    val cobolData: RDD[Row] = relation.parseRecords(oneRowRDD)

    val cobolDataFrame = sqlContext.createDataFrame(cobolData, sparkSchema)
    cobolDataFrame.collect().foreach(row => {
      for (map <- testData) {
        val keys = map.keys.toList
        if (map(keys(0)) == row.getAs(keys(0))) {
          for (i <- 1 until keys.length) {
            val fromMap = map(keys(i)).toString()
            val fromRow = row.getAs(keys(i)).toString()
            assert(fromMap == fromRow)
          }
        }
      }
    })
  }

  it should "manage exceptions from Reader" in {
    val exceptionMessage = "exception expected message"
    val testReader: Reader = new DummyReader(sparkSchema, cobolSchema, testData)(() => throw new Exception(exceptionMessage))
    val relation = new CobolRelation(copybookFile.getParentFile.getAbsolutePath, testReader)(sqlContext)

    val caught = intercept[Exception] {
      relation.parseRecords(oneRowRDD).collect()
    }
    assert(caught.getMessage.contains(exceptionMessage))
  }

  it should "manage records with missing fields" in {
    val absentField = "absentField"
    val modifiedSparkSchema = sparkSchema.add(new StructField(absentField, StringType, false))
    val testReader: Reader = new DummyReader(modifiedSparkSchema, cobolSchema, testData)(() => Unit)
    val relation = new CobolRelation(copybookFile.getParentFile.getAbsolutePath, testReader)(sqlContext)
    
    val caught = intercept[SparkException] {
      relation.parseRecords(oneRowRDD).collect()
    }    
    
    assert(caught.getMessage.contains("key not found: absentField"))
  }

  private def createTestData(): List[Map[String, Option[String]]] = {
    List(
      Map("name" -> Some("Young Guy"), "id" -> Some("1"), "age" -> Some("20")),
      Map("name" -> Some("Adult Guy"), "id" -> Some("2"), "age" -> Some("30")),
      Map("name" -> Some("Senior Guy"), "id" -> Some("3"), "age" -> Some("40")),
      Map("name" -> Some("Very Senior Guy"), "id" -> Some("4"), "age" -> Some("50")))
  }

  private def createTestSchemaMap(): Map[String, Any] = {
    Map("name" -> "", "id" -> "1l", "age" -> "1")
  }
}