package golfrag

import golfrag.model.Round
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.LocalDate
import scala.util.Using

/** Demo rounds (fictional), plus writers for the CSV and PDF formats the parsers understand. */
object SampleData {

  private def r(date: String, course: String, score: Int, par: Int, putts: Int, pen: Int, fw: Int, gir: Int, notes: String) =
    Round(LocalDate.parse(date), course, score, par, putts, pen, Some(fw), Some(14), Some(gir), 18, notes)

  val rounds: List[Round] = List(
    r("2026-05-09", "Tali", 92, 72, 37, 4, 5, 3, "First round of the season. Rusty driver, two balls OB on the back nine."),
    r("2026-05-17", "Espoo Golf Ruukinranta", 90, 71, 35, 3, 6, 4, "Windy. Chipping was poor, lots of duffed chips."),
    r("2026-05-24", "Kytäjä Etelä", 94, 72, 36, 5, 4, 3, "Water hazards cost me. Long course, felt tired on the last holes."),
    r("2026-06-01", "Tali", 88, 72, 34, 2, 7, 5, "Better tempo with the driver after range session."),
    r("2026-06-08", "Nuuksio Golf", 89, 72, 33, 3, 6, 5, "Putting felt good, three one-putts. Approach shots short."),
    r("2026-06-15", "Tali", 87, 72, 35, 2, 8, 6, "Hit more fairways using 3-wood off the tee."),
    r("2026-06-22", "Espoo Golf Ruukinranta", 91, 71, 36, 4, 5, 4, "Three-putted four times. Lag putting needs work."),
    r("2026-06-29", "Kytäjä Etelä", 86, 72, 32, 2, 7, 6, "Best putting round so far. Bunker play solid."),
    r("2026-07-06", "Tali", 85, 72, 33, 1, 8, 7, "Only one penalty. Irons were crisp."),
    r("2026-07-13", "Nuuksio Golf", 88, 72, 34, 3, 6, 6, "Sliced two drives into the trees."),
    r("2026-07-20", "Tali", 84, 72, 32, 1, 9, 7, "Personal best at Tali. Short game felt sharp."),
    r("2026-07-27", "Espoo Golf Ruukinranta", 86, 71, 33, 2, 7, 6, "Rain all day, but steady."),
    r("2026-08-03", "Kytäjä Etelä", 88, 72, 35, 2, 6, 6, "Three-putted twice from long range."),
    r("2026-08-10", "Tali", 83, 72, 31, 1, 8, 8, "Great round. Driver straight, putter hot."),
    r("2026-08-17", "Nuuksio Golf", 85, 72, 32, 2, 7, 7, "Approach wedges much better after lesson."),
    r("2026-08-24", "Tali", 86, 72, 34, 2, 8, 6, "Lost focus on the back nine."),
    r("2026-09-01", "Espoo Golf Ruukinranta", 84, 71, 31, 1, 8, 7, "Chipping practice paid off, two chip-ins."),
    r("2026-09-12", "Kytäjä Etelä", 85, 72, 32, 2, 7, 7, "Club championship round. Nervous start, strong finish."),
    r("2026-09-26", "Tali", 82, 72, 30, 1, 9, 8, "Lowest score ever! 30 putts, only one penalty."),
    r("2026-10-10", "Nuuksio Golf", 87, 72, 33, 3, 6, 6, "Cold and wet late-season round. Driver went left twice into water.")
  )

  def writeCsv(path: Path): Unit = {
    def q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val header = "date,course,score,par,putts,penalties,fairways_hit,fairways_possible,gir,holes,notes"
    val rows = rounds.map(r =>
      List(r.date, q(r.course), r.score, r.par, r.putts, r.penalties, r.fairwaysHit.getOrElse(""),
        r.fairwaysPossible.getOrElse(""), r.greensInRegulation.getOrElse(""), r.holes, q(r.notes)).mkString(","))
    Files.writeString(path, (header :: rows).mkString("\n") + "\n", StandardCharsets.UTF_8)
  }

  /** A "golf app export" style PDF: a header with player + handicap, then one key/value block per round. */
  def writePdf(path: Path, playerName: String = "Demo Golfer", handicap: Double = 13.8): Unit = {
    val lines: List[(String, Boolean)] =
      List(("Golf Round History", true), (s"Player: $playerName", false), (f"Handicap index: $handicap%.1f", false), ("", false)) ++
        rounds.flatMap { r =>
          List(
            (s"Date: ${r.date}", true),
            (s"Course: ${r.course}", false),
            (s"Score: ${r.score}", false),
            (s"Par: ${r.par}", false),
            (s"Putts: ${r.putts}", false),
            (s"Penalties: ${r.penalties}", false),
            (s"Fairways: ${r.fairwaysHit.getOrElse(0)}/${r.fairwaysPossible.getOrElse(14)}", false),
            (s"GIR: ${r.greensInRegulation.getOrElse(0)}", false),
            (s"Notes: ${r.notes}", false),
            ("", false)
          )
        }

    val regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA)
    val bold    = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    val leading = 14f
    val top     = PDRectangle.A4.getHeight - 50
    val perPage = ((top - 50) / leading).toInt

    Using.resource(new PDDocument()) { doc =>
      lines.grouped(perPage).foreach { pageLines =>
        val page = new PDPage(PDRectangle.A4)
        doc.addPage(page)
        Using.resource(new PDPageContentStream(doc, page)) { cs =>
          cs.beginText()
          cs.newLineAtOffset(50, top)
          pageLines.foreach { case (text, isBold) =>
            cs.setFont(if (isBold) bold else regular, 11)
            cs.showText(text)
            cs.newLineAtOffset(0, -leading)
          }
          cs.endText()
        }
      }
      doc.save(path.toFile)
    }
  }

  /** Regenerates the files in ./data: `sbt "runMain golfrag.SampleData"` */
  def main(args: Array[String]): Unit = {
    val dir = Path.of(args.headOption.getOrElse("data"))
    Files.createDirectories(dir)
    writeCsv(dir.resolve("sample-rounds.csv"))
    writePdf(dir.resolve("sample-rounds.pdf"))
    println(s"Wrote sample-rounds.csv and sample-rounds.pdf to $dir")
  }
}
