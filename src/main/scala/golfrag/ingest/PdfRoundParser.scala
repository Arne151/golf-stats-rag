package golfrag.ingest

import golfrag.model.Round
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

import java.nio.file.Path
import scala.util.Using

/**
 * Extracts rounds from a text-based PDF (e.g. an export from a golf app or a scorecard summary).
 *
 * Two layouts are understood without any AI:
 *   1. Key/value blocks – "Date: 2026-10-12", "Course: Tali", "Score: 84", ... one block per round.
 *   2. Tables – a header line ("Date Course Score Par Putts Penalties FIR GIR") followed by
 *      rows starting with a date. The course name is the non-numeric text after the date.
 *
 * Anything else is returned as `rawText` so it can be indexed for retrieval or handed to
 * [[LlmRoundExtractor]] for AI-assisted extraction.
 */
object PdfRoundParser {

  final case class Result(
      rounds: List[Round],
      handicap: Option[Double],
      playerName: Option[String],
      rawText: String,
      errors: List[String]
  )

  def extractText(path: Path): String =
    Using.resource(Loader.loadPDF(path.toFile)) { doc => new PDFTextStripper().getText(doc) }

  def parseFile(path: Path): Result = parseText(extractText(path))

  private val DateToken = """(\d{4}-\d{2}-\d{2}|\d{1,2}[./]\d{1,2}[./]\d{4})"""
  private val KeyValue  = """^\s*([A-Za-zÄÖäö][A-Za-zÄÖäö _()%-]{1,30}?)\s*[:=]\s*(.+?)\s*$""".r
  private val HcpRegex  = """(?i)(?:handicap(?:\s*index)?|hcp|tasoitus)\s*[:=]?\s*(\+?-?\d{1,2}(?:[.,]\d)?)""".r
  private val NameRegex = """(?i)^\s*(?:player|name|pelaaja)\s*[:=]\s*(.+?)\s*$""".r
  private val TableRow  = ("""^\s*""" + DateToken + """\s+(.+?)\s+((?:-?[\d/]+\s*)+)$""").r

  def parseText(text: String): Result = {
    val lines    = text.linesIterator.toList
    val handicap = HcpRegex.findFirstMatchIn(text).flatMap(m => m.group(1).replace(',', '.').replace("+", "-").toDoubleOption)
    val name     = lines.collectFirst { case NameRegex(n) => n }

    val (kvRounds, kvErrors) = parseKeyValueBlocks(lines)
    val (tblRounds, tblErrors) = if (kvRounds.isEmpty) parseTable(lines) else (Nil, Nil)
    val rounds = kvRounds ++ tblRounds

    Result(rounds, handicap, name, text, if (rounds.isEmpty) kvErrors ++ tblErrors else Nil)
  }

  /** Layout 1: blocks of "Key: value" lines, a new block starting at each "Date:" line. */
  private def parseKeyValueBlocks(lines: List[String]): (List[Round], List[String]) = {
    val pairs = lines.collect { case KeyValue(k, v) => RoundFields.canonical(k).map(_ -> v) }.flatten
    val blocks = pairs.foldLeft(List.empty[Map[String, String]]) {
      case (acc, ("date", v))         => Map("date" -> v) :: acc
      case (current :: rest, (k, v))  =>
        // Notes may wrap across lines in a PDF; keep the first value for everything else.
        (if (current.contains(k)) current else current + (k -> v)) :: rest
      case (Nil, _)                   => Nil // fields before the first date (e.g. player header) – ignore
    }.reverse
    val results = blocks.map(RoundFields.toRound)
    (results.collect { case Right(r) => r }, results.collect { case Left(e) => e })
  }

  /** Layout 2: a header line naming the columns, then one row per round. */
  private def parseTable(lines: List[String]): (List[Round], List[String]) = {
    val headerIdx = lines.indexWhere { l =>
      val cols = l.trim.split("\\s+").toList.flatMap(RoundFields.canonical)
      cols.contains("date") && cols.contains("score") && cols.contains("putts")
    }
    if (headerIdx < 0) return (Nil, List("no key/value blocks or table header found"))

    val numericCols = lines(headerIdx).trim.split("\\s+").toList
      .flatMap(RoundFields.canonical)
      .filterNot(c => c == "date" || c == "course" || c == "notes")

    val results = lines.drop(headerIdx + 1).collect { case TableRow(date, course, nums) =>
      val values = nums.trim.split("\\s+").toList
      RoundFields.toRound(Map("date" -> date, "course" -> course) ++ numericCols.zip(values))
    }
    (results.collect { case Right(r) => r }, results.collect { case Left(e) => e })
  }
}
