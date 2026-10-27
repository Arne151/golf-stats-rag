package golfrag

import golfrag.ingest.*

import java.nio.file.Files
import java.time.LocalDate

class IngestSuite extends munit.FunSuite {

  test("CSV with aliased headers, quotes and 'hit/possible' fairways") {
    val csv = List(
      "Date;Course;Strokes;Par;Putts;Penalty strokes;FIR;GIR;Notes",
      "12.10.2026;Tali;84;72;31;2;7/14;6;\"Windy; tough day\"",
      "2026-10-01;Kytäjä Etelä;88;72;34;;5/14;4;"
    )
    val res = CsvRoundParser.parse(csv)
    assertEquals(res.errors, Nil)
    assertEquals(res.rounds.size, 2)
    val r = res.rounds.head
    assertEquals(r.date, LocalDate.of(2026, 10, 12))
    assertEquals((r.score, r.putts, r.penalties, r.fairwaysHit, r.fairwaysPossible), (84, 31, 2, Some(7), Some(14)))
    assertEquals(r.notes, "Windy; tough day")
    assertEquals(res.rounds(1).penalties, 0) // empty -> default 0
  }

  test("CSV rows with missing required fields are reported, not silently dropped") {
    val res = CsvRoundParser.parse(List("date,course,score,putts", "2026-10-01,Tali,85,", "2026-10-02,Tali,86,33"))
    assertEquals(res.rounds.size, 1)
    assertEquals(res.errors.size, 1)
    assert(res.errors.head.contains("putts"))
  }

  test("PDF text with key/value blocks and a handicap header") {
    val text =
      """Golf Round History
        |Player: Arne
        |Handicap index: 14,2
        |Date: 2026-10-12
        |Course: Tali
        |Score: 84
        |Putts: 31
        |Penalties: 1
        |Fairways: 8/14
        |GIR: 7
        |Notes: Good round
        |Date: 2026-10-05
        |Course: Nuuksio Golf
        |Score: 90
        |Putts: 36
        |""".stripMargin
    val res = PdfRoundParser.parseText(text)
    assertEquals(res.handicap, Some(14.2))
    assertEquals(res.playerName, Some("Arne"))
    assertEquals(res.rounds.map(_.score), List(84, 90))
    assertEquals(res.rounds.head.greensInRegulation, Some(7))
  }

  test("PDF text in table layout") {
    val text =
      """My season
        |Date Course Score Par Putts Penalties GIR
        |2026-09-01 Espoo Golf Ruukinranta 84 71 31 1 7
        |2026-09-12 Kytäjä Etelä 85 72 32 2 7
        |""".stripMargin
    val res = PdfRoundParser.parseText(text)
    assertEquals(res.rounds.map(_.course), List("Espoo Golf Ruukinranta", "Kytäjä Etelä"))
    assertEquals(res.rounds.map(_.putts), List(31, 32))
    assertEquals(res.rounds.head.par, 71)
  }

  test("real PDF round trip: generated sample PDF parses back to the same rounds") {
    val file = Files.createTempFile("rounds", ".pdf")
    try {
      SampleData.writePdf(file, "Demo", 13.8)
      val res = PdfRoundParser.parseFile(file)
      assertEquals(res.rounds, SampleData.rounds)
      assertEquals(res.handicap, Some(13.8))
    } finally Files.deleteIfExists(file)
  }

  test("profile JSON") {
    val p = ProfileLoader.fromJson("""{"name":"Arne","handicap":"14,2","goals":["Break 80"]}""")
    assertEquals(p.handicap, Some(14.2))
    assertEquals(p.goals, List("Break 80"))
  }
}
