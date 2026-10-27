package golfrag.agent

import golfrag.model.GolfData
import golfrag.rag.Retriever
import golfrag.stats.*

/**
 * Works with no LLM at all: a keyword intent parser maps common questions onto [[StatsEngine]]
 * queries, and anything it can't parse falls back to showing the best retrieved passages.
 * Handy for demos without an API key, and a baseline to compare the LLM agent against.
 */
final class OfflineAssistant(data: GolfData, retriever: Retriever) extends Assistant {

  def description = "offline mode (keyword parser + retrieval, no LLM)"

  private val metricWords: List[(Metric, List[String])] = List(
    Metric.PuttsPerHole -> List("putts per hole"),
    Metric.Putts        -> List("putt"),
    Metric.Penalties    -> List("penalt", "out of bounds", "water"),
    Metric.FairwayPct   -> List("fairway %", "fairway percentage", "fairway pct", "% of fairways", "percent of fairways"),
    Metric.FairwaysHit  -> List("fairway", "fir"),
    Metric.GirPct       -> List("gir %", "gir percentage", "% of greens", "percent of greens"),
    Metric.Gir          -> List("gir", "greens in regulation", "green"),
    Metric.ToPar        -> List("to par", "over par", "under par", "vs par"),
    Metric.Score        -> List("score", "strokes", "shot")
  )

  private val aggWords: List[(Aggregation, List[String])] = List(
    Aggregation.Trend   -> List("trend", "improv", "getting better", "getting worse", "progress"),
    Aggregation.Median  -> List("median"),
    Aggregation.Min     -> List("best", "lowest", "fewest", "least", "minimum", "min "),
    Aggregation.Max     -> List("worst", "highest", "most", "maximum", "max "),
    Aggregation.Total   -> List("total", "sum", "in total", "altogether"),
    Aggregation.Count   -> List("how many rounds", "number of rounds", "count"),
    Aggregation.Average -> List("average", "avg", "mean", "typically", "usually", "per round")
  )

  private val LastN   = """(?i)(?:last|past|previous|recent)\s+(\d+)""".r
  private val QualitativeStart = """(when|why|which round|where|tell me about|describe)\b""".r
  private val NumWord =Map("five" -> 5, "ten" -> 10, "three" -> 3, "twenty" -> 20, "fifteen" -> 15)

  /** Exposed for tests: the query this question would run, if any. */
  def parse(question: String): Option[StatQuery] = {
    val q = question.toLowerCase
    if (q.contains("handicap")) return None
    // "When/why/which round..." questions are about notes, not numbers.
    if (QualitativeStart.findPrefixOf(q.trim).isDefined) return None
    metricWords.collectFirst { case (m, ws) if ws.exists(q.contains) => m }.map { metric =>
      val agg = aggWords.collectFirst { case (a, ws) if ws.exists(q.contains) => a }.getOrElse(Aggregation.Average)
      val lastN = LastN.findFirstMatchIn(q).map(_.group(1).toInt)
        .orElse(NumWord.collectFirst { case (w, n) if q.matches(s".*(last|past|previous) $w\\b.*") => n })
        .orElse(Option.when(q.contains("last round") || q.contains("latest round"))(1))
      val course = data.rounds.map(_.course).distinct.find(c => q.contains(c.toLowerCase))
      StatQuery(metric, agg, lastN, course)
    }
  }

  def ask(question: String): String = {
    val q = question.toLowerCase
    if (q.contains("handicap")) data.profile.describe
    else parse(question) match {
      case Some(query) => StatsEngine.compute(data.rounds, query).explain
      case None =>
        val hits = retriever.retrieve(question, k = 3)
        if (hits.isEmpty) "I couldn't find anything relevant. Try asking about putts, score, penalties, fairways or GIR."
        else "I can't compute that offline, but these look relevant:\n" + hits.map(h => s"  - ${h.doc.text}").mkString("\n")
    }
  }
}
