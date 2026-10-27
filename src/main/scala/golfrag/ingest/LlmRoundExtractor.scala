package golfrag.ingest

import golfrag.llm.{ChatMessage, LlmClient}
import golfrag.model.Round

import scala.util.Try

/**
 * Fallback for PDFs whose layout the regex parser doesn't recognise: ask the LLM to turn the raw
 * text into structured JSON, then validate every row through the same [[RoundFields]] rules.
 */
final class LlmRoundExtractor(llm: LlmClient) {

  private val system =
    """You convert golf scorecard text into JSON. Output ONLY a JSON array, no prose, no code fences.
      |Each element: {"date":"YYYY-MM-DD","course":string,"score":int,"par":int,"putts":int,
      |"penalties":int,"fairways_hit":int|null,"fairways_possible":int|null,"gir":int|null,"holes":int,"notes":string}.
      |Only include rounds that are actually present in the text. Never invent values; use null when unknown.""".stripMargin

  def extract(rawText: String): Either[String, List[Round]] =
    Try(llm.chat(system, List(ChatMessage.user(rawText.take(30000))))).toEither.left.map(_.getMessage).flatMap { resp =>
      val text = resp.text
      val json = text.substring(text.indexOf('[').max(0), (text.lastIndexOf(']') + 1).max(0))
      Try(ujson.read(json).arr.toList).toEither.left.map(e => s"LLM did not return valid JSON: ${e.getMessage}")
    }.map { items =>
      items.flatMap { item =>
        val fields = item.obj.collect {
          case (k, v) if !v.isNull =>
            val value = v.strOpt.getOrElse(v.numOpt.map(n => n.toLong.toString).getOrElse(v.toString))
            RoundFields.canonical(k).orElse(Option.when(k == "fairways_possible")("fairwaysPossible")).map(_ -> value)
        }.flatten.toMap
        RoundFields.toRound(fields).toOption
      }
    }
}
