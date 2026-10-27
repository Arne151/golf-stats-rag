package golfrag.ingest

import golfrag.model.Round

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.util.Try

/** Shared logic for turning loosely named key/value pairs (from CSV headers or PDF text) into a [[Round]]. */
object RoundFields {

  /** Canonical field name -> accepted spellings (lower-case, spaces/underscores removed). */
  private val aliases: Map[String, Set[String]] = Map(
    "date"      -> Set("date", "played", "dateplayed", "päivä", "pvm"),
    "course"    -> Set("course", "coursename", "club", "kenttä"),
    "score"     -> Set("score", "strokes", "gross", "grossscore", "total", "lyönnit"),
    "par"       -> Set("par", "coursepar"),
    "putts"     -> Set("putts", "putt", "puttit", "totalputts"),
    "penalties" -> Set("penalties", "penalty", "penaltystrokes", "penalties strokes", "rangaistukset"),
    "fairways"  -> Set("fairways", "fairwayshit", "fir", "fairwayhit"),
    "fairwaysPossible" -> Set("fairwayspossible", "fairwaystotal", "possiblefairways"),
    "gir"       -> Set("gir", "greens", "greensinregulation", "greenshit"),
    "holes"     -> Set("holes", "holesplayed", "väylät"),
    "notes"     -> Set("notes", "note", "comments", "comment", "muistiinpanot")
  )

  def normalizeKey(raw: String): String = raw.toLowerCase.replaceAll("[\\s_\\-()%]", "")

  def canonical(rawKey: String): Option[String] = {
    val k = normalizeKey(rawKey)
    aliases.collectFirst { case (canon, names) if names.map(normalizeKey).contains(k) => canon }
  }

  private val dateFormats = List(
    DateTimeFormatter.ISO_LOCAL_DATE,             // 2026-10-12
    DateTimeFormatter.ofPattern("d.M.yyyy"),      // 12.10.2026 (Finnish style)
    DateTimeFormatter.ofPattern("d/M/yyyy"),
    DateTimeFormatter.ofPattern("M/d/yyyy")
  )

  def parseDate(s: String): Option[LocalDate] =
    dateFormats.iterator.flatMap(f => Try(LocalDate.parse(s.trim, f)).toOption).nextOption()

  private def int(s: String): Option[Int] = "-?\\d+".r.findFirstIn(s).flatMap(_.toIntOption)

  /**
   * Build a round from canonical-keyed values. Fairways may be given as "7/14".
   * Returns Left(reason) when a required field is missing.
   */
  def toRound(fields: Map[String, String]): Either[String, Round] = {
    def req[A](key: String, parse: String => Option[A]): Either[String, A] =
      fields.get(key).flatMap(parse).toRight(s"missing or invalid '$key' in ${fields.mkString(", ")}")

    val (fwHit, fwPossible) = fields.get("fairways") match {
      case Some(v) if v.contains("/") =>
        val parts = v.split("/").map(_.trim)
        (int(parts(0)), parts.lift(1).flatMap(int))
      case other => (other.flatMap(int), fields.get("fairwaysPossible").flatMap(int))
    }
    val gir = fields.get("gir").flatMap(v => int(v.split("/").head))

    for {
      date   <- req("date", parseDate)
      course <- req("course", s => Option(s.trim).filter(_.nonEmpty))
      score  <- req("score", int)
      putts  <- req("putts", int)
    } yield {
      val holes = fields.get("holes").flatMap(int).getOrElse(18)
      Round(
        date = date,
        course = course,
        score = score,
        par = fields.get("par").flatMap(int).getOrElse(if (holes == 9) 36 else 72),
        putts = putts,
        penalties = fields.get("penalties").flatMap(int).getOrElse(0),
        fairwaysHit = fwHit,
        fairwaysPossible = fwPossible.orElse(fwHit.map(_ => if (holes == 9) 7 else 14)),
        greensInRegulation = gir,
        holes = holes,
        notes = fields.getOrElse("notes", "").trim
      )
    }
  }
}
