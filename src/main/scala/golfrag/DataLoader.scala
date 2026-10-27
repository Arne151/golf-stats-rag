package golfrag

import golfrag.ingest.*
import golfrag.llm.LlmClient
import golfrag.model.{GolfData, PlayerProfile, Round}

import java.nio.file.{Files, Path}

/** Loads every input file into one [[GolfData]], picking the parser from the file extension. */
object DataLoader {

  final case class Loaded(data: GolfData, messages: List[String])

  def load(
      roundFiles: List[Path],
      profileFile: Option[Path],
      handicapOverride: Option[Double],
      llmForExtraction: Option[LlmClient]
  ): Loaded = {
    val msgs = List.newBuilder[String]
    var profile = profileFile.map(ProfileLoader.fromFile).getOrElse(PlayerProfile())
    val rounds  = List.newBuilder[Round]
    val texts   = List.newBuilder[String]

    roundFiles.foreach { path =>
      val fileName = path.getFileName.toString
      if (!Files.exists(path)) msgs += s"[!] $fileName not found"
      else fileName.toLowerCase.split('.').last match {
        case "csv" =>
          val r = CsvRoundParser.parseFile(path)
          rounds ++= r.rounds
          msgs += s"[ok] $fileName: ${r.rounds.size} rounds" + warn(r.errors)

        case "pdf" =>
          val r = PdfRoundParser.parseFile(path)
          var found = r.rounds
          if (found.isEmpty && llmForExtraction.isDefined) {
            msgs += s"  $fileName: layout not recognised, asking the LLM to extract rounds..."
            new LlmRoundExtractor(llmForExtraction.get).extract(r.rawText) match {
              case Right(rs) => found = rs
              case Left(err) => msgs += s"  LLM extraction failed: $err"
            }
          }
          rounds ++= found
          // If the layout wasn't recognised, index the raw text so it is at least searchable.
          if (r.rounds.isEmpty) texts += r.rawText
          if (profile.handicap.isEmpty) profile = profile.copy(handicap = r.handicap)
          if (profile.name == "Golfer") r.playerName.foreach(n => profile = profile.copy(name = n))
          msgs += s"[ok] $fileName: ${found.size} rounds" +
            r.handicap.fold("")(h => s", handicap $h") + warn(if (found.isEmpty) r.errors else Nil)

        case "json" =>
          profile = ProfileLoader.fromFile(path)
          msgs += s"[ok] $fileName: profile"

        case "txt" | "md" =>
          texts += Files.readString(path)
          msgs += s"[ok] $fileName: added as searchable notes"

        case other => msgs += s"[!] $fileName: unsupported file type .$other (use .pdf, .csv, .json, .txt)"
      }
    }

    handicapOverride.foreach(h => profile = profile.copy(handicap = Some(h)))
    // The same round can appear in two exports; keep one copy.
    val unique = rounds.result().distinctBy(r => (r.date, r.course.toLowerCase, r.score))
    Loaded(GolfData(profile, unique, texts.result()), msgs.result())
  }

  private def warn(errors: List[String]): String =
    if (errors.isEmpty) "" else s" (${errors.size} rows skipped: ${errors.take(2).mkString("; ")})"
}
