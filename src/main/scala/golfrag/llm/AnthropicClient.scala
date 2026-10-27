package golfrag.llm

/**
 * Claude via the Anthropic Messages API (https://docs.claude.com).
 * Needs the ANTHROPIC_API_KEY environment variable.
 */
final class AnthropicClient(
    apiKey: String,
    model: String = AnthropicClient.DefaultModel,
    maxTokens: Int = 1024,
    baseUrl: String = "https://api.anthropic.com"
) extends LlmClient {

  val name = s"anthropic:$model"

  def chat(system: String, messages: List[ChatMessage], tools: List[ToolSpec]): LlmResponse = {
    val body = ujson.Obj(
      "model"      -> model,
      "max_tokens" -> maxTokens,
      "system"     -> system,
      "messages"   -> ujson.Arr.from(messages.map(toWire))
    )
    if (tools.nonEmpty)
      body("tools") = ujson.Arr.from(tools.map(t =>
        ujson.Obj("name" -> t.name, "description" -> t.description, "input_schema" -> t.inputSchema)))

    val json = Http.postJson(
      s"$baseUrl/v1/messages",
      body,
      Map("x-api-key" -> apiKey, "anthropic-version" -> "2023-06-01")
    )
    LlmResponse(json("content").arr.toList.flatMap(fromWire))
  }

  private def toWire(m: ChatMessage): ujson.Obj = ujson.Obj(
    "role" -> (if (m.role == Role.User) "user" else "assistant"),
    "content" -> ujson.Arr.from(m.content.map {
      case TextBlock(t)             => ujson.Obj("type" -> "text", "text" -> t)
      case ToolUseBlock(id, n, in)  => ujson.Obj("type" -> "tool_use", "id" -> id, "name" -> n, "input" -> in)
      case ToolResultBlock(id, _, c, err) =>
        ujson.Obj("type" -> "tool_result", "tool_use_id" -> id, "content" -> c, "is_error" -> err)
    })
  )

  private def fromWire(block: ujson.Value): Option[ContentBlock] = block("type").str match {
    case "text"     => Some(TextBlock(block("text").str))
    case "tool_use" => Some(ToolUseBlock(block("id").str, block("name").str, Json.asObj(block("input"))))
    case _          => None // e.g. thinking blocks – not needed here
  }
}

object AnthropicClient {
  /** Override with the ANTHROPIC_MODEL environment variable. */
  val DefaultModel = "claude-sonnet-5-5"
}
