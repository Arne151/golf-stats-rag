package golfrag

import golfrag.model.{GolfData, PlayerProfile, Round}

import java.time.LocalDate

object TestData {
  /** 12 rounds, one per week; round i has 30 + i putts and i % 3 penalties, so the expected answers are easy to compute. */
  val rounds: List[Round] = (1 to 12).toList.map { i =>
    Round(
      date = LocalDate.of(2026, 6, 1).plusWeeks(i.toLong),
      course = if (i % 2 == 0) "Tali" else "Nuuksio Golf",
      score = 90 - i,
      par = 72,
      putts = 30 + i,
      penalties = i % 3,
      fairwaysHit = Some(7),
      fairwaysPossible = Some(14),
      greensInRegulation = Some(i % 9),
      notes = if (i == 5) "Three-putted four times, lag putting was awful." else if (i == 9) "Driver went into the water twice." else ""
    )
  }

  val data: GolfData = GolfData(PlayerProfile("Test Player", Some(12.4), Some("Tali"), List("Break 80")), rounds)
}
