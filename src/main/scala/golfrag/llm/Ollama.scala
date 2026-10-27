package golfrag.llm

import golfrag.rag.{Embedder, VectorMath}

import java.util.UUID

/**
 * A local model served by Ollama (https://ollama.com) – free, runs on your own machine.
 * Use a model that supports tool calling, e.g. `ollama pull llama3.1` or `qwen2.5`.
 */
final class OllamaClient(model: String = "llama3.1", baseUrl: String = "http://localhost:11434") extends LlmClient {

  val name = s"ollama:$model"

  def chat(system: String, messages: List[ChatMessage], tools: List[ToolSpec]): LlmResponse = {
    val wireMessages = ujson.Obj("role" -> "system", "content" -> system) :: messages.flatMap(toWire)
    val body = ujson.Obj("model" -> model, "stream" -> false, "messages" -> ujson.Arr.from(wireMessages))
    if (tools.nonEmpty)
      body("tools") = ujson.Arr.from(tools.map(t => ujson.Obj(
        "type" -> "function",
        "function" -> ujson.Obj("name" -> t.name, "description" -> t.description, "parameters" -> t.inputSchema)
      )))

    val msg   = Http.postJson(s"$baseUrl/api/chat", body)("message")
    val text  = msg.obj.get("content").flatMap(_.strOpt).filter(_.trim.nonEmpty).map(TextBlock(_)).toList
    val calls = msg.obj.get("tool_calls").flatMap(_.arrOpt).toList.flatten.map { c =>
      val fn = c("function")
      val args = fn("arguments") match {
        case s: ujson.Str => Json.asObj(ujson.read(s.str))
        case other        => Json.asObj(other)
      }
      ToolUseBlock(UUID.randomUUID().toString, fn("name").str, args)
    }
    LlmResponse(text ++ calls)
  }

  // Ollama uses OpenAI-style messages: tool calls on the assistant message, one "tool" message per result.
  private def toWire(m: ChatMessage): List[ujson.Obj] = m.role match {
    case Role.Assistant =>
      val text  = m.content.collect { case TextBlock(t) => t }.mkString("\n")
      val calls = m.content.collect { case ToolUseBlock(_, n, in) => ujson.Obj("function" -> ujson.Obj("name" -> n, "arguments" -> in)) }
      val obj   = ujson.Obj("role" -> "assistant", "content" -> text)
      if (calls.nonEmpty) obj("tool_calls") = ujson.Arr.from(calls)
      List(obj)
    case Role.User =>
      val results = m.content.collect { case ToolResultBlock(_, n, c, _) => ujson.Obj("role" -> "tool", "tool_name" -> n, "content" -> c) }
      val text    = m.content.collect { case TextBlock(t) => t }
      results ++ (if (text.nonEmpty) List(ujson.Obj("role" -> "user", "content" -> text.mkString("\n"))) else Nil)
  }
}

/** Real semantic embeddings from Ollama, e.g. `ollama pull nomic-embed-text`. */
final class OllamaEmbedder(model: String = "nomic-embed-text", baseUrl: String = "http://localhost:11434") extends Embedder {
  val name = s"ollama:$model"

  def embed(texts: Seq[String]): Seq[Array[Float]] =
    if (texts.isEmpty) Nil
    else {
      val json = Http.postJson(s"$baseUrl/api/embed", ujson.Obj("model" -> model, "input" -> ujson.Arr.from(texts)))
      json("embeddings").arr.toSeq.map(v => VectorMath.normalize(v.arr.map(_.num.toFloat).toArray))
    }
}
