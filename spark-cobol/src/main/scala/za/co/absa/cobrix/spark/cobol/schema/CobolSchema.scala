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

package za.co.absa.cobrix.spark.cobol.schema

import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.sql.types._
import za.co.absa.cobrix.cobol.parser.Copybook
import za.co.absa.cobrix.cobol.parser.ast._
import za.co.absa.cobrix.cobol.parser.ast.datatype.{AlphaNumeric, Decimal, Integer}
import za.co.absa.cobrix.cobol.parser.common.{Constants, ReservedWords}

import scala.collection.mutable.ArrayBuffer

class CobolSchema(val copybook: Copybook) extends Serializable with LazyLogging {

  def getCobolSchema: Copybook = copybook

  private[this] lazy val sparkSchema = {
    logger.info("Layout positions:\n" + copybook.generateRecordLayoutPositions())
    val records = for (record <- copybook.ast) yield {
      parseGroup(record)
    }
    StructType(records.toArray)
  }

  private[this] lazy val sparkFlatSchema = {
    logger.info("Layout positions:\n" + copybook.generateRecordLayoutPositions())
    val arraySchema = copybook.ast.toArray
    val records = arraySchema.flatMap(record => {
      parseGroupFlat(record, s"${record.name}_")
    })
    StructType(records)
  }

  def getSparkSchema: StructType = {
    sparkSchema
  }

  def getSparkFlatSchema: StructType = {
    sparkFlatSchema
  }

  lazy val getRecordSize: Int = copybook.getRecordSize

  def isRecordFixedSize: Boolean = copybook.isRecordFixedSize

  private def parseGroup(group: Group): StructField = {
    val fields = for (field <- group.children if field.name.toUpperCase != ReservedWords.FILLER) yield {
      field match {
        case group: Group =>
          parseGroup(group)
        case s: Statement =>
          val dataType: DataType = s.dataType match {
            case Decimal(scale, precision, _, _, _, _, _) => DecimalType(precision, scale)
            case _: AlphaNumeric => StringType
            case dt: Integer =>
              if (dt.precision > Constants.maxIntegerPrecision) {
                LongType
              }
              else {
                IntegerType
              }
            case _ => throw new IllegalStateException("Unknown AST object")
          }
          if (s.isArray) {
            StructField(s.name, ArrayType(dataType), nullable = true)
          } else {
            StructField(s.name, dataType, nullable = true)
          }
      }

    }
    if (group.isArray) {
      StructField(group.name, ArrayType(StructType(fields.toArray)), nullable = true)
    } else {
      StructField(group.name, StructType(fields.toArray), nullable = true)
    }

  }

  private def parseGroupFlat(group: Group, structPath: String = ""): ArrayBuffer[StructField] = {
    val fields = new ArrayBuffer[StructField]()
    for (field <- group.children if field.name.toUpperCase != ReservedWords.FILLER) {
      field match {
        case group: Group =>
          if (group.isArray) {
            for (i <- Range(1, group.arrayMaxSize + 1)) {
              val path = s"$structPath${group.name}_${i}_"
              fields ++= parseGroupFlat(group, path)
            }
          } else {
            val path = s"$structPath${group.name}_"
            fields ++= parseGroupFlat(group, path)
          }
        case s: Statement =>
          val dataType: DataType = s.dataType match {
            case Decimal(scale, precision, _, _, _, _, _) => DecimalType(precision, scale)
            case _: AlphaNumeric => StringType
            case dt: Integer =>
              if (dt.precision > Constants.maxIntegerPrecision) {
                LongType
              }
              else {
                IntegerType
              }
            case _ => throw new IllegalStateException("Unknown AST object")
          }
          val path = s"$structPath"//${group.name}_"
          if (s.isArray) {
            for (i <- Range(1, s.arrayMaxSize + 1)) {
              fields += StructField(s"$path{s.name}_$i", ArrayType(dataType), nullable = true)
            }
          } else {
            fields += StructField(s"$path${s.name}", dataType, nullable = true)
          }
      }
    }

    fields
  }

}
