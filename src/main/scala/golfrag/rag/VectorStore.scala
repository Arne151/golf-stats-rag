package golfrag.rag

/** Simple in-memory vector index with brute-force cosine search – plenty for a few hundred rounds. */
final class InMemoryVectorStore(embedder: Embedder) {

  private var entries: Vector[(Document, Array[Float])] = Vector.empty

  def size: Int = entries.size

  def addAll(docs: Seq[Document]): Unit = if (docs.nonEmpty) {
    val vectors = embedder.embed(docs.map(_.text))
    entries = entries ++ docs.zip(vectors)
  }

  def search(query: String, k: Int = 5, filter: Document => Boolean = _ => true): List[ScoredDocument] = {
    val q = embedder.embedOne(query)
    entries.iterator
      .filter { case (d, _) => filter(d) }
      .map { case (d, v) => ScoredDocument(d, VectorMath.cosine(q, v)) }
      .toList
      .sortBy(-_.score)
      .take(k)
  }
}

/** Retrieval step of RAG: question in, the most relevant context passages out. */
final class Retriever(store: InMemoryVectorStore, minScore: Double = 0.05) {
  def retrieve(question: String, k: Int = 5): List[ScoredDocument] =
    store.search(question, k).filter(_.score >= minScore)

  def formatContext(hits: List[ScoredDocument]): String =
    if (hits.isEmpty) "(no relevant passages found)"
    else hits.map(h => f"- [${h.doc.id}, relevance ${h.score}%.2f] ${h.doc.text}").mkString("\n")
}
