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

package za.co.absa.cobrix.cobol.parser

import com.typesafe.scalalogging.LazyLogging
import za.co.absa.cobrix.cobol.parser.ast.datatype.{AlphaNumeric, CobolType, Decimal, Integer}
import za.co.absa.cobrix.cobol.parser.ast.{BinaryProperties, CBTree, Group, Statement}
import za.co.absa.cobrix.cobol.parser.common.Constants
import za.co.absa.cobrix.cobol.parser.encoding.Encoding

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * The object contains generic function for the Copybook parser
  */
object CopybookParser extends LazyLogging{
  type MutableCopybook = mutable.ArrayBuffer[CBTree]
  type CopybookAST = Seq[Group]

  import za.co.absa.cobrix.cobol.parser.common.ReservedWords._

  case class CopybookLine(level: Int, name: String, modifiers: Map[String, String])

  case class RecordBoundary(name: String, begin: Int, end: Int)

  /**
    * Tokenizes a Cobol Copybook contents and returns the AST.
    *
    * @param enc ASCII/EBCDIC
    * @return Seq[Group] where a group is a record inside the copybook
    */
  def parseTree(enc: Encoding, copyBookContents: String): Copybook = {

    // Get start line index and one past last like index for each record (aka newElementLevel 1 field)
    def getBreakpoints(lines: Seq[CopybookLine]) = {
      // miniumum level
      val minLevel = lines.map(line => line.level).min
      // create a tuple of index value and root names for all the 01 levels
      val recordStartLines: Seq[(String, Int)] = lines.zipWithIndex.collect {
        case (CopybookLine(`minLevel`, name: String, _), i: Int) => (name, i)
      }
      val recordChangeLines: Seq[Int] = recordStartLines.drop(1).map(_._2) :+ lines.length
      val recordBeginEnd: Seq[((String, Int), Int)] = recordStartLines.zip(recordChangeLines)
      val breakpoints: Seq[RecordBoundary] = recordBeginEnd.map {
        case ((recordName, startLine), endLine) => RecordBoundary(recordName, startLine, endLine)
      }
      breakpoints
    }

    def getMatchingGroup(element: CBTree, newElementLevel: Int): Group = {
      newElementLevel match {
        case level if level < 1 => throw new IllegalStateException("Couldn't find matching level.")
        case level if level > element.level => element.asInstanceOf[Group]
        case level if level <= element.level => getMatchingGroup(element.up().get, level)
      }
    }

    val tokens = tokenize(copyBookContents)
    val lexedLines = tokens.map(lineTokens => lex(lineTokens))

    val lines: Seq[CopybookLine] =
      tokens.zip(lexedLines)
        .map { case (levelNameArray, modifiers) =>
          val nameWithoutColons = transformIdentifier(levelNameArray(1))
          CopybookLine(levelNameArray(0).toInt, nameWithoutColons, modifiers)
        }

    val breakpoints: Seq[RecordBoundary] = getBreakpoints(lines)

    // A forest can only have multiple items if there is a duplicate newElementLevel
    val forest: Seq[Seq[CopybookLine]] = breakpoints.map(p => lines.slice(p.begin, p.end))

    val schema: MutableCopybook = new MutableCopybook()
    forest.foreach { fields =>
      val root = Group(1, fields.head.name,
        mutable.ArrayBuffer(),
        redefines = None)(None)
      val trees = fields
        .drop(1) // root already added so drop first line
        .foldLeft[CBTree](root)((element, field) => {
        val keywords = field.modifiers.keys.toList
        val isLeaf = keywords.contains(PIC) || keywords.contains(COMP123)
        val redefines = field.modifiers.get(REDEFINES)
        val occurs = field.modifiers.get(OCCURS).map(i => i.toInt)
        val to = field.modifiers.get(TO).map(i => i.toInt)
        val dependingOn = field.modifiers.get(DEPENDING)

        val newElement = if (isLeaf) {
          val dataType = typeAndLengthFromString(keywords, field.modifiers)(enc)
          Statement(field.level, field.name, dataType, redefines, isRedefined = false, occurs, to, dependingOn)(None)
        }
        else {
          Group(field.level, field.name, mutable.ArrayBuffer(), redefines, isRedefined = false, occurs, to, dependingOn)(None)
        }

        val attachLevel = getMatchingGroup(element, field.level)
        attachLevel.add(newElement)
      })
      schema += root
    }

    val newTrees = renameGroupFillers(markDependeeFields(calculateBinaryProperties(schema)))
    val ast: CopybookAST = newTrees.map(grp => grp.asInstanceOf[Group])
    new Copybook(ast)
  }

  /** Calculate binary properties based on the whole AST
    *
    * @param originalSchema An AST as a set of copybook records
    * @return The same AST with binary properties set for every field
    */
  def calculateBinaryProperties(originalSchema: MutableCopybook): MutableCopybook = {
    val schema = calculateSchemaSizes(originalSchema)
    getSchemaWithOffsets(0, schema)
  }

  /**
    * Calculate binary properties for a mutble Cobybook schema which is just an array of AST objects
    *
    * @param subSchema An array of AST objects
    * @return The same AST with binary properties set for every field
    */
  def calculateSchemaSizes(subSchema: MutableCopybook ): MutableCopybook  = {

    def calculateGroupSize(originalGroup: Group): Group = {
      val children: MutableCopybook = calculateSchemaSizes(originalGroup.children)
      val groupSize = (for (child <- children if child.redefines.isEmpty) yield child.binaryProperties.actualSize).sum
      val groupSizeAllOccurs = groupSize*originalGroup.arrayMaxSize
      val newBinProps = BinaryProperties(originalGroup.binaryProperties.offset, groupSize, groupSizeAllOccurs)
      originalGroup.withUpdatedChildren(children).withUpdatedBinaryProperties(newBinProps)
    }

    def calculateStatementSize(originalStatement: Statement): Statement = {
      val size = originalStatement.getBinarySizeBits
      val sizeAllOccurs = size*originalStatement.arrayMaxSize
      val binProps = BinaryProperties(originalStatement.binaryProperties.offset, size, sizeAllOccurs)
      originalStatement.withUpdatedBinaryProperties(binProps)
    }

    val newSchema: MutableCopybook = new MutableCopybook()
    val redefinedSizes = new mutable.ArrayBuffer[Int]()
    val redefinedNames = new mutable.HashSet[String]()

    // Calculate sizes of all elements of the AST array
    for (i <- subSchema.indices) {
      val child = subSchema(i)

      child.redefines match {
        case None =>
          redefinedSizes.clear()
          redefinedNames.clear()
        case Some(redefines) =>
          if (i == 0) {
            throw new IllegalStateException(s"First field ${child.name} of a group cannot use REDEFINES keyword.")
          }
          if (!redefinedNames.contains(redefines.toUpperCase)) {
            throw new IllegalStateException(s"The field ${child.name} redefines $redefines, which is not part if the redefined fields block.")
          }
          newSchema(i-1) = newSchema(i-1).withUpdatedIsRedefined(newIsRedefined = true)
      }

      val childWithSizes = child match {
        case group: Group => calculateGroupSize(group)
        case st: Statement => calculateStatementSize(st)
      }
      redefinedSizes += childWithSizes.binaryProperties.actualSize
      redefinedNames += childWithSizes.name.toUpperCase
      newSchema += childWithSizes
      if (child.redefines.nonEmpty) {
        // Calculate maximum redefine size
        val maxSize = redefinedSizes.max
        for (j <- redefinedSizes.indices) {
          val updatedBinProps = newSchema(i-j).binaryProperties.copy(actualSize = maxSize)
          val updatedChild = newSchema(i-j).withUpdatedBinaryProperties(updatedBinProps)
          newSchema(i-j) = updatedChild
        }
      }
    }
    newSchema
  }

  /**
    * Calculate binary offsets for a mutble Cobybook schema which is just an array of AST objects
    *
    * @param subSchema An array of AST objects
    * @return The same AST with all offsets set for every field
    */
  def getSchemaWithOffsets(bitOffset: Int, subSchema: MutableCopybook): MutableCopybook = {

    def getGroupWithOffsets(bitOffset: Int, group: Group): Group = {
      val newChildern = getSchemaWithOffsets(bitOffset, group.children)
      val binProps = BinaryProperties(bitOffset, group.binaryProperties.dataSize, group.binaryProperties.actualSize)
      group.withUpdatedChildren(newChildern).withUpdatedBinaryProperties(binProps)
    }

    var offset = bitOffset
    var redefinedOffset =  bitOffset
    val newSchema = for (field <- subSchema) yield {
      val useOffset = if (field.redefines.isEmpty) {
        redefinedOffset = offset
        offset
      } else redefinedOffset
      val newField = field match {
        case grp: Group =>
          getGroupWithOffsets(useOffset, grp)
        case st: Statement =>
          val binProps = BinaryProperties(useOffset, st.binaryProperties.dataSize, st.binaryProperties.actualSize)
          st.withUpdatedBinaryProperties(binProps)
        case _ => field
      }
      if (field.redefines.isEmpty) {
        offset += newField.binaryProperties.actualSize
      }
      newField
    }
    newSchema
  }

  /**
    * Sets isDependee attribute for fields in the schema which are used by other fields in DEPENDING ON clause
    *
    * @param originalSchema An AST as a set of copybook records
    * @return The same AST with binary properties set for every field
    */
  def markDependeeFields(originalSchema: MutableCopybook): MutableCopybook = {
    val flatFields = new mutable.ArrayBuffer[Statement]()
    val dependees = new mutable.HashSet[Statement]()

    def addDependeeField(name: String): Unit = {
      val nameUpper = name.toUpperCase
      // Find all the fields that match DEPENDING ON name
      val foundFields = flatFields.filter( f => f.name.toUpperCase == nameUpper)
      if (foundFields.isEmpty) {
        throw new IllegalStateException(s"Unable to find dependee field $nameUpper from DEPENDING ON clause.")
      }
      if (foundFields.length > 1) {
        logger.warn("Field $name used in DEPENDING ON clause has multiple instances.")
      }
      dependees ++= foundFields
    }

    def traverseDepends(subSchema: MutableCopybook): Unit = {
      for (field <- subSchema) {
        field.dependingOn.foreach(name => addDependeeField(name))
        field match {
          case grp: Group => traverseDepends(grp.children)
          case st: Statement => flatFields += st
        }
      }
    }

    def markDependeesForGroup(group: Group): Group = {
      val newChildren = markDependees(group.children)
      var groupWithMarkedDependees = group.copy(children = newChildren)(group.parent)
      groupWithMarkedDependees
    }

    def markDependees(subSchema: MutableCopybook): MutableCopybook = {
      val newSchema = for (field <- subSchema) yield {
        val newField: CBTree = field match {
          case grp: Group => markDependeesForGroup(grp)
          case st: Statement =>
            val newStatement = if (dependees contains st) {
              st.dataType match {
                case _: Integer => true
                case dt => throw new IllegalStateException(s"Field ${st.name} is an a DEPENDING ON field of an OCCURS, should be integral, found ${dt.getClass}.")
              }
              st.withUpdatedIsDependee(newIsDependee = true)
            } else {
              st
            }
            newStatement
        }
        newField
      }
      newSchema
    }

    traverseDepends(originalSchema)
    markDependees(originalSchema)
  }

  /**
    * Rename group fillers so filed names in the scheme doesn't repeat
    * Also, remove all group fillers that doesn't have child nodes
    *
    * @param originalSchema An AST as a set of copybook records
    * @return The same AST with group fillers renamed
    */
  private def renameGroupFillers(originalSchema: MutableCopybook): MutableCopybook = {
    var lastFillerIndex = 0

    def renameSubGroupFillers(group: Group): Group = {
      val newChildren = renameFillers(group.children)
      var renamedGroup = if (group.name.toUpperCase == FILLER) {
        lastFillerIndex += 1
        group.copy(name = s"${FILLER}_$lastFillerIndex")(group.parent)
      } else group
      renamedGroup.copy(children = newChildren)(renamedGroup.parent)
    }

    def renameFillers(subSchema: MutableCopybook): MutableCopybook = {
      val newSchema = ArrayBuffer[CBTree]()
      subSchema.foreach {
        case grp: Group =>
          val newGrp = renameSubGroupFillers(grp)
          if (newGrp.children.nonEmpty) {
            newSchema += newGrp
          }
        case st: Statement => newSchema += st
      }
      newSchema
    }

    renameFillers(originalSchema)
  }

  /**
    * Get the type and length from a cobol data structure.
    *
    * @param keywords Keywords of a Copybook statement
    * @param modifiers Modifiers of a Copybook field
    * @return Cobol data type
    */
  def typeAndLengthFromString(
                               keywords: List[String],
                               modifiers: Map[String, String]
                             )(enc: Encoding): CobolType = {
    val comp: Option[Int] =
      if (keywords.contains(COMP123))
        Some(modifiers.getOrElse(COMP123, "1").toInt)
      else {
        if (keywords.contains(COMP) || keywords.contains(BINARY)) Some(4)
        else None
      }

    val sync = keywords.contains(SYNC)
    modifiers.get(PIC).head match {
      case s if s.contains("X") || s.contains("A") =>
        AlphaNumeric(s.length, wordAlligned = if (sync) Some(position.Left) else None, Some(enc))
      case s if s.contains("V") || s.contains(".") =>
        CopybookParser.decimalLength(s) match {
          case (integralDigits, fractureDigits) =>
            //println(s"DECIMAL LENGTH for $s => ($integralDigits, $fractureDigits)")
            Decimal(
              fractureDigits,
              integralDigits + fractureDigits,
              s.contains("."),
              if (s.startsWith("S")) Some(position.Left) else None,
              if (sync) Some(position.Right) else None,
              comp,
              Some(enc))
        }
      case s if s.contains("9") =>
        Integer(
          precision = if (s.startsWith("S")) s.length-1 else s.length,
          signPosition = if (s.startsWith("S")) Some(position.Left) else None,
          wordAlligned = if (sync) Some(position.Right) else None,
          comp,
          Some(enc)
        )
    }
  }

  /**
    * Tokenizes a copybook to lift the relevant information
    */
  def tokenize(cpyBook: String): Array[Array[String]] = {
    val tokens = cpyBook
      // split by line breaks
      .split("\\r?\\n")
      .map(
        line =>
          line
            // ignore all columns after 72th one and
            // first 6 columns (historically for line numbers)
            .slice(6, 72)
            // remove unnecessary white space
            .replaceAll("\\s\\s+", " ")
            .trim()
      )
      // ignore commented lines
      .filterNot(l => l.startsWith("*"))
      .mkString(" ")
      .split('.')
      .map(l => l.replaceAll("^\\s+", ""))
      // filter out aliases and enumerations
      .filterNot(l => l.startsWith("66") || l.startsWith("77") || l.startsWith("88") || l.trim.isEmpty)
      .map(l => l.trim().split("\\s+"))
    tokens
  }

  /** Lex the parsed tokens
    *
    * @param tokens Tokens to lex
    * @return lexed properties
    */
  def lex(tokens: Array[String]): Map[String, String] = {
    val keywordsWithModifiers = List(REDEFINES, OCCURS, TO, PIC)
    val keywordsWithoutModifiers = List(COMP, BINARY)

    if (tokens.length < 3) {
      Map[String, String]()
    } else {
      var index = 2
      val mapAccumulator = mutable.Map[String, String]()
      while (index < tokens.length) {
        if (tokens(index) == PIC) {
          if (index >= tokens.length - 1) {
            throw new IllegalStateException("PIC should be followed by a pattern")
          }
          // Expand PIC, e.g. S9(5) -> S99999
          mapAccumulator += tokens(index) -> expandPic(tokens(index + 1))
          index += 1
        } else if (tokens(index) == REDEFINES) {
          // Expand REDEFINES, ensure current field redefines the consequent field
          if (index >= tokens.length - 1) {
            throw new IllegalStateException(s"Modifier ${tokens(index)} should be followed by a field name")
          }
          // Add modifiers with parameters
          mapAccumulator += tokens(index) -> transformIdentifier(tokens(index + 1))
          index += 1
        } else if (keywordsWithModifiers.contains(tokens(index))) {
          if (index >= tokens.length - 1) {
            throw new IllegalStateException(s"Modifier ${tokens(index)} should be followed by a parameter")
          }
          // Add modifiers with parameters
          mapAccumulator += tokens(index) -> tokens(index + 1)
          index += 1
        } else if (tokens(index).startsWith(COMP123)) {
          // Handle COMP-1 / COMP-2 / COMP-3
          mapAccumulator += COMP123 -> tokens(index).split('-')(1)
        } else if (tokens(index) == SYNC) {
          // Handle SYNC
          mapAccumulator += tokens(index) -> "Right"
        } else if (tokens(index) == DEPENDING) {
          // Handle DEPENDING ON
          if (index >= tokens.length - 2 || tokens(index+1) != ON) {
            throw new IllegalStateException(s"Modifier DEPENDING should be followed by ON FIELD")
          }
          mapAccumulator += tokens(index) -> transformIdentifier(tokens(index + 2))
        } else if (keywordsWithoutModifiers.contains(tokens(index))) {
          // Handle parameterless modifiers (COMP)
          mapAccumulator += tokens(index) -> ""
        }
        index += 1
      }
      mapAccumulator.toMap
    }

  }

  /**
    * Expands a PIC by replacing parenthesis with a number by the actual symbols.
    * For example: 99(3).9(2) -> 9999.99
    *
    * @param inputPIC An input PIC specification, e.g. "9(5)V9(2)"
    * @return The expanded version of a PIC value, e.g. "99999V99"
    */
  def expandPic(inputPIC: String): String = {
    val outputCharacters = new ArrayBuffer[Char]()
    val repeatCount = new ArrayBuffer[Char]()
    var parsingCount = false
    var lastCharacter = ' '
    for (c <- inputPIC) {
      if (!parsingCount) {
        if (c == '(') {
          parsingCount = true
        }
        else {
          outputCharacters += c
          lastCharacter = c
        }
      } else {
        if (c == ')') {
          parsingCount = false
          val num = repeatCount.mkString("").toInt - 1
          repeatCount.clear()
          if (num > 0 && num <= Constants.maxFieldLength) {
            for (i <- Range(0, num)) {
              outputCharacters += lastCharacter
            }
          } else {
            throw new IllegalStateException(s"Incorrect field size of $inputPIC. Supported size is in range from 1 to ${Constants.maxFieldLength}.")
          }
        }
        else {
          repeatCount += c
        }
      }
    }
    outputCharacters.mkString
  }

  /** Transforms the Cobol identifiers to be useful in Spark context. Removes characters an identifier cannot contain. */
  def transformIdentifier(identifier: String): String = {
    identifier
      .replaceAll(":", "")
      .replaceAll("-", "_")
  }

  /**
    * Get number of decimal digits given a PIC of a numeric field
    *
    * @param s A PIC string
    * @return A pair specifying the number of digits before and after decimal separator
    */
  def decimalLength(s: String): (Int, Int) = {
    var str = expandPic(s)
    val separator = if (str.contains('V')) 'V' else '.'
    val parts = str.split(separator)
    val nines1 = parts.head.count(_ == '9')
    val nines2 = if (parts.length > 1) parts.last.count(_ == '9') else 0
    (nines1, nines2)  }
}