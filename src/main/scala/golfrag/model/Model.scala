package golfrag.model

import java.time.LocalDate

/** One played round. Optional fields are stats not every scorecard tracks. */
final case class Round(
    date: LocalDate,
    course: String,
    score: Int,
    par: Int,
    putts: Int,
    penalties: Int,
    fairwaysHit: Option[Int] = None,
    fairwaysPossible: Option[Int] = None,
    greensInRegulation: Option[Int] = None,
    holes: Int = 18,
    notes: String = ""
) {
  def toPar: Int = score - par

  def fairwayPct: Option[Double] =
    for (hit <- fairwaysHit; possible <- fairwaysPossible if possible > 0)
      yield hit.toDouble / possible * 100

  def girPct: Option[Double] =
    greensInRegulation.map(g => g.toDouble / holes * 100)

  /** A short human-readable description; also used as the RAG chunk text. */
  def describe: String = {
    val sign = if (toPar >= 0) "+" else ""
    val fw   = (fairwaysHit, fairwaysPossible) match {
      case (Some(h), Some(p)) => s", fairways $h/$p"
      case _                  => ""
    }
    val gir  = greensInRegulation.fold("")(g => s", greens in regulation $g/$holes")
    val n    = if (notes.trim.nonEmpty) s". Notes: ${notes.trim}" else ""
    s"Round on $date at $course ($holes holes): score $score ($sign$toPar vs par $par), " +
      s"$putts putts, $penalties penalties$fw$gir$n"
  }
}

final case class PlayerProfile(
    name: String = "Golfer",
    handicap: Option[Double] = None,
    homeCourse: Option[String] = None,
    goals: List[String] = Nil
) {
  def describe: String = {
    val hcp   = handicap.fold("unknown")(h => f"$h%.1f")
    val home  = homeCourse.fold("")(c => s", home course $c")
    val goal  = if (goals.isEmpty) "" else s". Goals: ${goals.mkString("; ")}"
    s"Player $name, handicap index $hcp$home$goal"
  }
}

/** Everything the assistant knows about the player. */
final case class GolfData(profile: PlayerProfile, rounds: List[Round], extraTexts: List[String] = Nil) {
  /** Rounds ordered newest first – "last N rounds" always means the N most recent. */
  lazy val newestFirst: List[Round] = rounds.sortBy(_.date)(using Ordering[LocalDate].reverse)
}
