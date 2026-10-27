package golfrag.stats

import golfrag.model.Round

import java.time.LocalDate

/** Stats the engine can compute. `key` is the identifier the LLM uses in tool calls. */
enum Metric(val key: String, val label: String, val extract: Round => Option[Double]) {
  case Score       extends Metric("score", "score", r => Some(r.score.toDouble))
  case ToPar       extends Metric("to_par", "score relative to par", r => Some(r.toPar.toDouble))
  case Putts       extends Metric("putts", "putts", r => Some(r.putts.toDouble))
  case Penalties   extends Metric("penalties", "penalty strokes", r => Some(r.penalties.toDouble))
  case FairwaysHit extends Metric("fairways_hit", "fairways hit", r => r.fairwaysHit.map(_.toDouble))
  case FairwayPct  extends Metric("fairway_pct", "fairway hit %", _.fairwayPct)
  case Gir         extends Metric("gir", "greens in regulation", r => r.greensInRegulation.map(_.toDouble))
  case GirPct      extends Metric("gir_pct", "greens in regulation %", _.girPct)
  case PuttsPerHole extends Metric("putts_per_hole", "putts per hole", r => Some(r.putts.toDouble / r.holes))
}

object Metric {
  def fromKey(key: String): Option[Metric] = values.find(_.key.equalsIgnoreCase(key.trim))
}

enum Aggregation(val key: String) {
  case Average extends Aggregation("average")
  case Total   extends Aggregation("total")
  case Min     extends Aggregation("min")
  case Max     extends Aggregation("max")
  case Median  extends Aggregation("median")
  case Count   extends Aggregation("count")
  /** Least-squares slope per round, oldest -> newest. Negative = going down. */
  case Trend   extends Aggregation("trend")
}

object Aggregation {
  def fromKey(key: String): Option[Aggregation] = values.find(_.key.equalsIgnoreCase(key.trim))
}

final case class StatQuery(
    metric: Metric,
    aggregation: Aggregation = Aggregation.Average,
    lastN: Option[Int] = None,
    course: Option[String] = None,
    from: Option[LocalDate] = None,
    to: Option[LocalDate] = None
)

final case class StatResult(query: StatQuery, value: Option[Double], rounds: List[Round]) {

  /** A precise sentence the LLM (or the offline mode) can quote directly. */
  def explain: String = {
    val scope = (
      query.lastN.fold("over all rounds")(n => if (n == 1) "in the most recent round" else s"over the last $n rounds") ::
        List(
          query.course.map(c => s"at $c"),
          query.from.map(d => s"from $d"),
          query.to.map(d => s"until $d")
        ).flatten
    ).mkString(" ")

    val what = query.aggregation match {
      case Aggregation.Average => s"Average ${query.metric.label}"
      case Aggregation.Total   => s"Total ${query.metric.label}"
      case Aggregation.Min     => s"Lowest ${query.metric.label}"
      case Aggregation.Max     => s"Highest ${query.metric.label}"
      case Aggregation.Median  => s"Median ${query.metric.label}"
      case Aggregation.Count   => s"Rounds with ${query.metric.label} recorded"
      case Aggregation.Trend   => s"Trend in ${query.metric.label}"
    }

    value match {
      case None => s"No data for ${query.metric.label} $scope."
      case Some(v) =>
        val dates = if (rounds.isEmpty) "" else s" between ${rounds.map(_.date).min} and ${rounds.map(_.date).max}"
        val formatted = query.aggregation match {
          case Aggregation.Count => f"$v%.0f"
          case Aggregation.Trend =>
            f"$v%+.2f per round (${if (v < -0.05) "going down" else if (v > 0.05) "going up" else "flat"})"
          case Aggregation.Min | Aggregation.Max =>
            // Name the round(s) that hold the record.
            val holders = rounds.filter(r => query.metric.extract(r).contains(v)).map(r => s"${r.date} at ${r.course}")
            s"${fmt(v)} (${holders.mkString(", ")})"
          case _ => fmt(v)
        }
        s"$what $scope: $formatted. Based on ${rounds.size} rounds$dates."
    }
  }

  private def fmt(v: Double): String = if (v == math.rint(v)) f"$v%.0f" else f"$v%.2f"
}

/**
 * Deterministic number crunching. The LLM never does arithmetic itself – it calls this through a
 * tool, which is what makes answers like "average putts in my last 10 rounds" exact.
 */
object StatsEngine {

  def select(rounds: List[Round], q: StatQuery): List[Round] = {
    val filtered = rounds
      .filter(r => q.course.forall(c => r.course.toLowerCase.contains(c.toLowerCase.trim)))
      .filter(r => q.from.forall(d => !r.date.isBefore(d)))
      .filter(r => q.to.forall(d => !r.date.isAfter(d)))
      .sortBy(_.date)(using Ordering[LocalDate].reverse)
    q.lastN.fold(filtered)(n => filtered.take(math.max(n, 0)))
  }

  def compute(rounds: List[Round], q: StatQuery): StatResult = {
    val selected = select(rounds, q)
    // Only rounds that actually have this stat count (e.g. some cards don't track GIR).
    val withValue = selected.flatMap(r => q.metric.extract(r).map(r -> _))
    val values    = withValue.map(_._2)

    val value: Option[Double] = q.aggregation match {
      case Aggregation.Count => Some(withValue.size.toDouble)
      case _ if values.isEmpty => None
      case Aggregation.Average => Some(values.sum / values.size)
      case Aggregation.Total   => Some(values.sum)
      case Aggregation.Min     => Some(values.min)
      case Aggregation.Max     => Some(values.max)
      case Aggregation.Median  =>
        val s = values.sorted
        Some(if (s.size % 2 == 1) s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2)
      case Aggregation.Trend   => slope(withValue.reverse.map(_._2)) // oldest first
    }
    StatResult(q, value, withValue.map(_._1))
  }

  /** Least-squares slope of ys against 0,1,2,... */
  def slope(ys: List[Double]): Option[Double] =
    if (ys.size < 2) None
    else {
      val n    = ys.size.toDouble
      val xs   = ys.indices.map(_.toDouble)
      val mx   = xs.sum / n
      val my   = ys.sum / n
      val num  = xs.zip(ys).map { case (x, y) => (x - mx) * (y - my) }.sum
      val den  = xs.map(x => (x - mx) * (x - mx)).sum
      Some(num / den)
    }

  /** One-line overview used in the system prompt so the model knows what data exists. */
  def overview(rounds: List[Round]): String =
    if (rounds.isEmpty) "No rounds loaded."
    else {
      val sorted = rounds.sortBy(_.date)
      val courses = rounds.groupBy(_.course).view.mapValues(_.size).toList.sortBy(-_._2)
        .map { case (c, n) => s"$c ($n)" }.mkString(", ")
      s"${rounds.size} rounds from ${sorted.head.date} to ${sorted.last.date}. Courses: $courses."
    }
}
