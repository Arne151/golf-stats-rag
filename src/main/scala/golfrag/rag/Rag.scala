package golfrag.rag

import golfrag.model.GolfData

/** A unit of retrievable text. */
final case class Document(id: String, text: String, metadata: Map[String, String] = Map.empty)

final case class ScoredDocument(doc: Document, score: Double)

/** Turns text into vectors. Swap implementations without touching the rest of the pipeline. */
trait Embedder {
  def name: String
  def embed(texts: Seq[String]): Seq[Array[Float]]
  def embedOne(text: String): Array[Float] = embed(Seq(text)).head
}

/** Splits the player's data into documents for the vector store. */
object Chunker {

  def documents(data: GolfData, maxChars: Int = 600): List[Document] = {
    val profileDoc = Document("profile", data.profile.describe, Map("type" -> "profile"))

    // One chunk per round: rounds are small and self-contained, so this keeps retrieval precise.
    val roundDocs = data.newestFirst.zipWithIndex.map { case (r, i) =>
      Document(
        id = s"round-${r.date}-$i",
        text = r.describe,
        metadata = Map("type" -> "round", "date" -> r.date.toString, "course" -> r.course)
      )
    }

    // Free text (e.g. notes pages in a PDF that weren't part of a parsed round).
    val textDocs = data.extraTexts.zipWithIndex.flatMap { case (text, t) =>
      splitText(text, maxChars).zipWithIndex.map { case (chunk, c) =>
        Document(s"text-$t-$c", chunk, Map("type" -> "text"))
      }
    }
    profileDoc :: roundDocs ++ textDocs
  }

  /** Paragraph-aware splitting with a hard size limit. */
  def splitText(text: String, maxChars: Int): List[String] = {
    val paragraphs = text.split("\\n\\s*\\n").map(_.replaceAll("\\s+", " ").trim).filter(_.nonEmpty).toList
    paragraphs.foldLeft(List.empty[String]) {
      case (current :: done, p) if current.length + p.length + 1 <= maxChars => s"$current $p" :: done
      case (acc, p) => p.grouped(maxChars).toList.reverse ++ acc
    }.reverse
  }
}
