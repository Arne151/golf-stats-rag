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

/** Helpers to check that Ollama is running and the model is downloaded, with clear instructions if not. */
object Ollama {

  /** Models known to handle tool calling well, in order of preference. */
  val PreferredChatModels: List[String] = List("llama3.1", "qwen2.5", "qwen3", "llama3.2", "mistral-nemo", "mistral")

  def installedModels(baseUrl: String): Either[String, List[String]] =
    scala.util.Try(Http.getJson(s"$baseUrl/api/tags")).toEither
      .left.map(_ => s"Ollama isn't running at $baseUrl. Start the Ollama app (or run `ollama serve`) and try again.")
      .map(_("models").arr.toList.flatMap(_.obj.get("name").flatMap(_.strOpt)))

  def isRunning(baseUrl: String): Boolean = installedModels(baseUrl).isRight

  /** "llama3.1" matches "llama3.1:latest" and "llama3.1:8b"; "qwen2.5:7b" only matches exactly. */
  def matches(installed: String, wanted: String): Boolean =
    installed == wanted || (!wanted.contains(':') && installed.takeWhile(_ != ':') == wanted)

  /** The requested model if it's downloaded; otherwise the best installed chat model. */
  def resolveModel(baseUrl: String, requested: Option[String]): Either[String, String] =
    requested match {
      case Some(m) => requireModel(baseUrl, m)
      case None =>
        installedModels(baseUrl).flatMap { installed =>
          pickChatModel(installed).toRight(
            "No chat model is downloaded in Ollama yet. Open a terminal and run:  ollama pull llama3.1")
        }
    }

  def requireModel(baseUrl: String, model: String): Either[String, String] =
    installedModels(baseUrl).flatMap { installed =>
      installed.find(matches(_, model)).toRight(
        s"Ollama model '$model' isn't downloaded. Open a terminal and run:  ollama pull $model" +
          (if (installed.nonEmpty) s"\n(Downloaded models: ${installed.mkString(", ")})" else ""))
    }

  /** Pure selection logic (unit-tested). */
  def pickChatModel(installed: List[String]): Option[String] = {
    val chatModels = installed.filterNot(_.contains("embed"))
    PreferredChatModels.iterator.flatMap(p => chatModels.find(matches(_, p))).nextOption()
      .orElse(chatModels.headOption)
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
