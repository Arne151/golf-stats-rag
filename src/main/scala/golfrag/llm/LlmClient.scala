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

  def postJson(url: String, body: ujson.Value, headers: Map[String, String] = Map.empty): ujson.Value =
    send(url, headers, _.header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(ujson.write(body))))

  def getJson(url: String, headers: Map[String, String] = Map.empty): ujson.Value =
    send(url, headers, _.GET())

  /**
   * Sends a request, retrying on 429 (rate limited) and 503 (overloaded). Free API tiers allow only a
   * few requests per minute, so waiting a little is much better than failing the question.
   */
  private def send(url: String, headers: Map[String, String], method: HttpRequest.Builder => HttpRequest.Builder,
                   maxRetries: Int = 3): ujson.Value = {
    val builder = method(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)))
    headers.foreach { case (k, v) => builder.header(k, v) }
    val request = builder.build()

    @annotation.tailrec
    def attempt(n: Int): ujson.Value = {
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      val status   = response.statusCode()
      if (status / 100 == 2) ujson.read(response.body())
      else if ((status == 429 || status == 503) && n < maxRetries) {
        val waitSeconds = response.headers().firstValue("retry-after").map[Option[Long]](v => v.toLongOption).orElse(None)
          .getOrElse(10L * (n + 1)).min(60L)
        System.err.println(s"  (rate limited by the API, waiting ${waitSeconds}s and retrying...)")
        Thread.sleep(waitSeconds * 1000)
        attempt(n + 1)
      } else
        throw new LlmException(s"HTTP $status from ${url.takeWhile(_ != '?')}: ${response.body().take(500)}")
    }
    attempt(0)
  }
}
