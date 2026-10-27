package golfrag.llm

/**
 * Any provider that speaks the OpenAI "chat completions" format – which most do. Presets below cover:
 *   - Google Gemini (free tier, key from https://aistudio.google.com/apikey)
 *   - Groq          (free tier, key from https://console.groq.com/keys)
 *   - OpenAI
 */
final class OpenAiCompatibleClient(
    providerName: String,
    baseUrl: String,
    apiKey: String,
    val model: String,
    maxTokens: Int = 1024
) extends LlmClient {

  val name = s"$providerName:$model"

  def chat(system: String, messages: List[ChatMessage], tools: List[ToolSpec]): LlmResponse = {
    val wire = ujson.Obj("role" -> "system", "content" -> system) :: messages.flatMap(toWire)
    val body = ujson.Obj("model" -> model, "max_tokens" -> maxTokens, "messages" -> ujson.Arr.from(wire))
    if (tools.nonEmpty) body("tools") = ujson.Arr.from(tools.map(toolToWire))

    val json = Http.postJson(s"$baseUrl/chat/completions", body, Map("authorization" -> s"Bearer $apiKey"))
    val msg  = json("choices")(0)("message")

    val text = msg.obj.get("content").flatMap(_.strOpt).filter(_.trim.nonEmpty).map(TextBlock(_)).toList
    val calls = msg.obj.get("tool_calls").flatMap(_.arrOpt).toList.flatten.map { c =>
      val fn = c("function")
      val args = fn.obj.get("arguments") match {
        case Some(s: ujson.Str) if s.str.trim.nonEmpty => Json.asObj(ujson.read(s.str))
        case Some(o: ujson.Obj)                        => o
        case _                                         => ujson.Obj()
      }
      val id = c.obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty).getOrElse(java.util.UUID.randomUUID().toString)
      ToolUseBlock(id, fn("name").str, args)
    }
    LlmResponse(text ++ calls)
  }

  private def toolToWire(t: ToolSpec): ujson.Obj = {
    val fn = ujson.Obj("name" -> t.name, "description" -> t.description)
    // Some providers (e.g. Gemini) reject an object schema with no properties, so omit it entirely.
    val hasParams = t.inputSchema.value.get("properties").flatMap(_.objOpt).exists(_.nonEmpty)
    if (hasParams) fn("parameters") = t.inputSchema
    ujson.Obj("type" -> "function", "function" -> fn)
  }

  private def toWire(m: ChatMessage): List[ujson.Obj] = m.role match {
    case Role.Assistant =>
      val text  = m.content.collect { case TextBlock(t) => t }.mkString("\n")
      val calls = m.content.collect { case ToolUseBlock(id, n, in) =>
        ujson.Obj("id" -> id, "type" -> "function", "function" -> ujson.Obj("name" -> n, "arguments" -> ujson.write(in)))
      }
      val obj = ujson.Obj("role" -> "assistant")
      if (text.nonEmpty || calls.isEmpty) obj("content") = text
      if (calls.nonEmpty) obj("tool_calls") = ujson.Arr.from(calls)
      List(obj)
    case Role.User =>
      val results = m.content.collect { case ToolResultBlock(id, n, c, _) =>
        ujson.Obj("role" -> "tool", "tool_call_id" -> id, "name" -> n, "content" -> c)
      }
      val text = m.content.collect { case TextBlock(t) => t }
      results ++ (if (text.nonEmpty) List(ujson.Obj("role" -> "user", "content" -> text.mkString("\n"))) else Nil)
  }
}

object OpenAiCompatibleClient {

  final case class Preset(provider: String, keyEnv: String, baseUrl: String, defaultModel: Option[String], keyUrl: String)

  val presets: Map[String, Preset] = Map(
    // No fixed default for Gemini: model names change often, so we pick the newest "flash" model the key can use.
    "gemini" -> Preset("gemini", "GEMINI_API_KEY", "https://generativelanguage.googleapis.com/v1beta/openai", None,
      "https://aistudio.google.com/apikey"),
    "groq"   -> Preset("groq", "GROQ_API_KEY", "https://api.groq.com/openai/v1", Some("llama-3.3-70b-versatile"),
      "https://console.groq.com/keys"),
    "openai" -> Preset("openai", "OPENAI_API_KEY", "https://api.openai.com/v1", Some("gpt-4o-mini"),
      "https://platform.openai.com/api-keys")
  )

  /** Builds a client; `modelOverride` (LLM_MODEL env var) wins over the preset/auto-detected model. */
  def fromPreset(p: Preset, apiKey: String, modelOverride: Option[String]): Either[String, OpenAiCompatibleClient] =
    modelOverride.orElse(p.defaultModel).map(Right(_)).getOrElse(detectFlashModel(p.baseUrl, apiKey))
      .map(model => new OpenAiCompatibleClient(p.provider, p.baseUrl, apiKey, model))

  /** Lists the models available to this key and picks the newest general-purpose Gemini "flash" model. */
  def detectFlashModel(baseUrl: String, apiKey: String): Either[String, String] =
    scala.util.Try(Http.getJson(s"$baseUrl/models", Map("authorization" -> s"Bearer $apiKey"))).toEither
      .left.map(e => s"could not list models (is the API key correct?): ${e.getMessage}")
      .flatMap { json =>
        val ids = json("data").arr.toList.flatMap(_.obj.get("id").flatMap(_.strOpt)).map(_.stripPrefix("models/"))
        pickFlashModel(ids).toRight(s"no suitable 'flash' model found; set LLM_MODEL. Available: ${ids.take(15).mkString(", ")}")
      }

  /** Pure selection logic (unit-tested): stable before preview, newest version first, no specialised variants. */
  def pickFlashModel(ids: List[String]): Option[String] = {
    val excluded = List("lite", "image", "tts", "audio", "live", "embedding", "thinking", "exp", "omni", "native", "8b", "computer")
    val version  = """gemini-(\d+(?:\.\d+)?)""".r
    ids.filter(id => id.startsWith("gemini-") && id.contains("flash") && !excluded.exists(id.contains))
      .sortBy(id => (
        -version.findFirstMatchIn(id).flatMap(_.group(1).toDoubleOption).getOrElse(0.0),
        id.contains("preview"),
        id.length
      ))
      .headOption
  }
}
