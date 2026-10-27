package golfrag.llm

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** Provider-neutral chat model with tool calling. Each provider client maps this to its own wire format. */
enum Role { case User, Assistant }

sealed trait ContentBlock
final case class TextBlock(text: String) extends ContentBlock
final case class ToolUseBlock(id: String, name: String, input: ujson.Obj) extends ContentBlock
final case class ToolResultBlock(toolUseId: String, toolName: String, content: String, isError: Boolean = false)
    extends ContentBlock

final case class ChatMessage(role: Role, content: List[ContentBlock])
object ChatMessage {
  def user(text: String): ChatMessage      = ChatMessage(Role.User, List(TextBlock(text)))
  def assistant(text: String): ChatMessage = ChatMessage(Role.Assistant, List(TextBlock(text)))
}

/** A function the model may call. `inputSchema` is JSON Schema. */
final case class ToolSpec(name: String, description: String, inputSchema: ujson.Obj)

final case class LlmResponse(content: List[ContentBlock]) {
  def text: String               = content.collect { case TextBlock(t) => t }.mkString("\n").trim
  def toolCalls: List[ToolUseBlock] = content.collect { case t: ToolUseBlock => t }
}

trait LlmClient {
  def name: String
  def chat(system: String, messages: List[ChatMessage], tools: List[ToolSpec] = Nil): LlmResponse
}

final class LlmException(msg: String) extends RuntimeException(msg)

object Json {
  def asObj(v: ujson.Value): ujson.Obj = v match {
    case o: ujson.Obj => o
    case _            => ujson.Obj()
  }
}

/** Tiny JSON-over-HTTP helper on top of the JDK client (no extra dependencies). */
object Http {
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

  def postJson(url: String, body: ujson.Value, headers: Map[String, String] = Map.empty): ujson.Value = {
    val builder = HttpRequest.newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(120))
      .header("content-type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
    headers.foreach { case (k, v) => builder.header(k, v) }
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() / 100 != 2)
      throw new LlmException(s"HTTP ${response.statusCode()} from $url: ${response.body().take(500)}")
    ujson.read(response.body())
  }
}
