package golfrag.agent

import golfrag.ingest.RoundFields
import golfrag.llm.ToolSpec
import golfrag.model.GolfData
import golfrag.rag.Retriever
import golfrag.stats.*

import scala.util.Try

/**
 * The tools exposed to the LLM. Numbers come from [[StatsEngine]] (exact), free-text context comes
 * from the [[Retriever]] (semantic search) – the model decides which it needs for each question.
 */
final class GolfTools(data: GolfData, retriever: Retriever) {

  private val filterProps = ujson.Obj(
    "last_n"    -> ujson.Obj("type" -> "integer", "description" -> "Only the N most recent rounds"),
    "course"    -> ujson.Obj("type" -> "string", "description" -> "Only rounds whose course name contains this text"),
    "from_date" -> ujson.Obj("type" -> "string", "description" -> "Earliest date, YYYY-MM-DD"),
    "to_date"   -> ujson.Obj("type" -> "string", "description" -> "Latest date, YYYY-MM-DD")
  )

  val specs: List[ToolSpec] = List(
    ToolSpec(
      "compute_stat",
      "Compute an exact statistic over the player's rounds. ALWAYS use this for any number " +
        "(averages, totals, best/worst, trends) instead of calculating yourself.",
      ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj.from(ujson.Obj(
          "metric" -> ujson.Obj("type" -> "string", "enum" -> ujson.Arr.from(Metric.values.map(_.key)),
            "description" -> "to_par = score minus par; *_pct are percentages"),
          "aggregation" -> ujson.Obj("type" -> "string", "enum" -> ujson.Arr.from(Aggregation.values.map(_.key)),
            "description" -> "min/max = best/worst value; trend = change per round (least-squares slope)")
        ).value ++ filterProps.value),
        "required" -> ujson.Arr("metric", "aggregation")
      )
    ),
    ToolSpec(
      "list_rounds",
      "List rounds (newest first) with all their stats and notes.",
      ujson.Obj("type" -> "object", "properties" -> filterProps)
    ),
    ToolSpec(
      "search_notes",
      "Semantic search over round descriptions, notes and other uploaded text. Use for qualitative " +
        "questions (e.g. 'when did I struggle with my driver?').",
      ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "query" -> ujson.Obj("type" -> "string"),
          "k"     -> ujson.Obj("type" -> "integer", "description" -> "Number of passages, default 5")
        ),
        "required" -> ujson.Arr("query")
      )
    ),
    ToolSpec(
      "get_profile",
      "Get the player's profile: name, handicap index, home course and goals.",
      ujson.Obj("type" -> "object", "properties" -> ujson.Obj())
    )
  )

  /** Runs a tool. Left = error message that is sent back to the model so it can correct itself. */
  def execute(name: String, input: ujson.Obj): Either[String, String] = Try {
    name match {
      case "compute_stat" =>
        for {
          metric <- Metric.fromKey(str(input, "metric").getOrElse("")).toRight(s"unknown metric; use one of ${Metric.values.map(_.key).mkString(", ")}")
          agg    <- Aggregation.fromKey(str(input, "aggregation").getOrElse("average")).toRight("unknown aggregation")
        } yield StatsEngine.compute(data.rounds, filters(input).copy(metric = metric, aggregation = agg)).explain

      case "list_rounds" =>
        val rounds = StatsEngine.select(data.rounds, filters(input))
        Right(if (rounds.isEmpty) "No matching rounds." else rounds.map(r => s"- ${r.describe}").mkString("\n"))

      case "search_notes" =>
        val k = input.value.get("k").flatMap(_.numOpt).map(_.toInt).getOrElse(5)
        str(input, "query").toRight("query is required").map(q => retriever.formatContext(retriever.retrieve(q, k)))

      case "get_profile" => Right(data.profile.describe)

      case other => Left(s"unknown tool '$other'")
    }
  }.toEither.left.map(e => s"tool failed: ${e.getMessage}").flatten

  private def str(input: ujson.Obj, key: String): Option[String] =
    input.value.get(key).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)

  private def filters(input: ujson.Obj): StatQuery = StatQuery(
    metric = Metric.Score,
    lastN = input.value.get("last_n").flatMap(v => v.numOpt.orElse(v.strOpt.flatMap(_.toDoubleOption))).map(_.toInt),
    course = str(input, "course"),
    from = str(input, "from_date").flatMap(RoundFields.parseDate),
    to = str(input, "to_date").flatMap(RoundFields.parseDate)
  )
}
