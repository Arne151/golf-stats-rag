package golfrag.ingest

import golfrag.model.Round

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Reads rounds from a CSV file with a header row. Column names are matched loosely (see [[RoundFields]]). */
object CsvRoundParser {

  final case class Result(rounds: List[Round], errors: List[String])

  def parseFile(path: Path): Result =
    parse(Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList)

  def parse(lines: List[String]): Result = {
    val nonEmpty = lines.filter(_.trim.nonEmpty)
    if (nonEmpty.isEmpty) return Result(Nil, List("CSV is empty"))

    val delimiter = if (nonEmpty.head.count(_ == ';') > nonEmpty.head.count(_ == ',')) ';' else ','
    val header    = splitLine(nonEmpty.head, delimiter).map(h => RoundFields.canonical(h))

    val parsed = nonEmpty.tail.zipWithIndex.map { case (line, i) =>
      val values = splitLine(line, delimiter)
      val fields = header.zip(values).collect { case (Some(key), v) if v.trim.nonEmpty => key -> v.trim }.toMap
      RoundFields.toRound(fields).left.map(err => s"row ${i + 2}: $err")
    }
    Result(parsed.collect { case Right(r) => r }, parsed.collect { case Left(e) => e })
  }

  /** Minimal RFC-4180 style splitting: supports quoted fields containing the delimiter and "" escapes. */
  def splitLine(line: String, delimiter: Char = ','): List[String] = {
    val out     = List.newBuilder[String]
    val current = new StringBuilder
    var inQuotes = false
    var i = 0
    while (i < line.length) {
      val c = line.charAt(i)
      if (inQuotes) {
        if (c == '"' && i + 1 < line.length && line.charAt(i + 1) == '"') { current += '"'; i += 1 }
        else if (c == '"') inQuotes = false
        else current += c
      } else if (c == '"') inQuotes = true
      else if (c == delimiter) { out += current.toString; current.clear() }
      else current += c
      i += 1
    }
    out += current.toString
    out.result()
  }
}
