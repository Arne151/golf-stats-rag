package golfrag.rag

import scala.util.hashing.MurmurHash3

/**
 * Offline embedder using the "hashing trick": unigrams + bigrams hashed into a fixed-size vector
 * with sub-linear term weighting, then L2-normalised. No model download or API key needed, which
 * keeps the project runnable anywhere and the tests deterministic. For semantic matching
 * ("short game" ~ "chipping") use [[golfrag.llm.OllamaEmbedder]] instead.
 */
final class HashingEmbedder(dimensions: Int = 1024) extends Embedder {

  val name = s"hashing-$dimensions"

  private val stopWords = Set("the", "a", "an", "and", "or", "of", "in", "on", "at", "to", "my", "i", "was", "is", "with", "for", "did", "how", "what")

  def tokenize(text: String): List[String] =
    text.toLowerCase.split("[^\\p{L}\\p{N}]+").toList.filter(t => t.nonEmpty && !stopWords(t)).map(stem)

  /** Crude suffix stripping so "putts", "putted" and "putting" all match "putt". */
  private def stem(t: String): String =
    List("ing", "ed", "es", "s").find(s => t.length > s.length + 3 && t.endsWith(s)).fold(t)(s => t.dropRight(s.length))

  def embed(texts: Seq[String]): Seq[Array[Float]] = texts.map(embedText)

  private def embedText(text: String): Array[Float] = {
    val tokens   = tokenize(text)
    val features = tokens ++ tokens.sliding(2).collect { case List(a, b) => s"$a $b" }
    val vec      = new Array[Float](dimensions)
    features.groupBy(identity).foreach { case (feature, occurrences) =>
      val h    = MurmurHash3.stringHash(feature)
      val idx  = Math.floorMod(h, dimensions)
      val sign = if ((h >>> 31) == 0) 1f else -1f // signed hashing reduces collision bias
      vec(idx) += sign * (1f + math.log(occurrences.size.toDouble).toFloat)
    }
    VectorMath.normalize(vec)
  }
}

object VectorMath {
  def normalize(v: Array[Float]): Array[Float] = {
    val norm = math.sqrt(v.map(x => x.toDouble * x).sum).toFloat
    if (norm == 0f) v else v.map(_ / norm)
  }

  def cosine(a: Array[Float], b: Array[Float]): Double = {
    var dot, na, nb = 0.0
    var i = 0
    while (i < math.min(a.length, b.length)) {
      dot += a(i) * b(i); na += a(i) * a(i); nb += b(i) * b(i); i += 1
    }
    if (na == 0 || nb == 0) 0.0 else dot / math.sqrt(na * nb)
  }
}
