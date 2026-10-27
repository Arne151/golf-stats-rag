package golfrag

import com.sun.net.httpserver.HttpServer
import golfrag.agent.GolfAssistant
import golfrag.llm.*
import golfrag.rag.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/** Runs the real client + agent against a local fake of the OpenAI chat-completions API (Gemini/Groq/OpenAI format). */
class OpenAiCompatibleClientSuite extends munit.FunSuite {

  test("picks the newest stable general-purpose flash model") {
    val ids = List("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.0-flash-preview", "gemini-2.5-pro",
      "gemini-2.0-flash", "gemini-2.5-flash-image", "gemini-embedding-001")
    assertEquals(OpenAiCompatibleClient.pickFlashModel(ids), Some("gemini-3.0-flash-preview"))
    assertEquals(OpenAiCompatibleClient.pickFlashModel(ids.filterNot(_.contains("3.0"))), Some("gemini-2.5-flash"))
    assertEquals(OpenAiCompatibleClient.pickFlashModel(List("models/x", "gemini-2.5-pro")), None)
  }

  test("Ollama model selection") {
    val installed = List("nomic-embed-text:latest", "mistral:7b", "llama3.1:latest")
    assertEquals(Ollama.pickChatModel(installed), Some("llama3.1:latest"))
    assertEquals(Ollama.pickChatModel(List("nomic-embed-text:latest", "phi4:14b")), Some("phi4:14b"))
    assertEquals(Ollama.pickChatModel(List("nomic-embed-text:latest")), None)
    assert(Ollama.matches("qwen2.5:7b", "qwen2.5"))
    assert(!Ollama.matches("qwen2.5:7b", "qwen2.5:14b"))
    assert(!Ollama.matches("llama3.1:latest", "llama3"))
  }

  test("tool-calling round trip through a fake Ollama server") {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    def reply(ex: com.sun.net.httpserver.HttpExchange, json: ujson.Value): Unit = {
      val bytes = ujson.write(json).getBytes(StandardCharsets.UTF_8)
      ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody.write(bytes); ex.close()
    }
    server.createContext("/api/tags", ex => reply(ex, ujson.Obj("models" -> ujson.Arr(ujson.Obj("name" -> "llama3.1:latest")))))
    server.createContext("/api/chat", ex => {
      val req  = ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      val last = req("messages").arr.last
      if (last("role").str == "tool")
        reply(ex, ujson.Obj("message" -> ujson.Obj("role" -> "assistant", "content" -> s"Ollama says: ${last("content").str}")))
      else
        reply(ex, ujson.Obj("message" -> ujson.Obj("role" -> "assistant", "content" -> "",
          "tool_calls" -> ujson.Arr(ujson.Obj("function" -> ujson.Obj("name" -> "compute_stat",
            "arguments" -> ujson.Obj("metric" -> "putts", "aggregation" -> "average", "last_n" -> 10)))))))
    })
    server.start()
    try {
      val url = s"http://127.0.0.1:${server.getAddress.getPort}"
      assert(Ollama.isRunning(url))
      assertEquals(Ollama.resolveModel(url, None), Right("llama3.1:latest"))
      assert(Ollama.requireModel(url, "qwen2.5").left.exists(_.contains("ollama pull qwen2.5")))

      val store = new InMemoryVectorStore(new HashingEmbedder())
      store.addAll(Chunker.documents(TestData.data))
      val answer = new GolfAssistant(TestData.data, new OllamaClient("llama3.1:latest", url), new Retriever(store))
        .ask("How many putts did I have on average in the last 10 rounds?")
      assert(answer.contains("37.50"), answer)
    } finally server.stop(0)
    assert(!Ollama.isRunning("http://127.0.0.1:1"), "should report not running")
  }

  test("tool-calling round trip in the OpenAI wire format") {
    val requests = ListBuffer.empty[ujson.Value]
    val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    server.createContext("/v1/models", ex => {
      val body = """{"data":[{"id":"models/gemini-2.5-flash"},{"id":"models/gemini-2.5-flash-lite"}]}""".getBytes
      ex.sendResponseHeaders(200, body.length); ex.getResponseBody.write(body); ex.close()
    })
    server.createContext("/v1/chat/completions", ex => {
      val req = ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      requests += req
      val last = req("messages").arr.last
      val reply =
        if (last("role").str == "tool")
          ujson.Obj("choices" -> ujson.Arr(ujson.Obj("message" -> ujson.Obj("role" -> "assistant",
            "content" -> s"You averaged: ${last("content").str}"))))
        else
          ujson.Obj("choices" -> ujson.Arr(ujson.Obj("message" -> ujson.Obj("role" -> "assistant", "content" -> ujson.Null,
            "tool_calls" -> ujson.Arr(ujson.Obj("id" -> "call_1", "type" -> "function", "function" -> ujson.Obj(
              "name" -> "compute_stat", "arguments" -> """{"metric":"putts","aggregation":"average","last_n":10}""")))))))
      val bytes = ujson.write(reply).getBytes(StandardCharsets.UTF_8)
      ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody.write(bytes); ex.close()
    })
    server.start()

    try {
      val base   = s"http://127.0.0.1:${server.getAddress.getPort}/v1"
      val preset = OpenAiCompatibleClient.Preset("gemini", "X", base, None, "")
      val client = OpenAiCompatibleClient.fromPreset(preset, "test-key", None).toOption.get
      assertEquals(client.model, "gemini-2.5-flash") // auto-detected from /models

      val store = new InMemoryVectorStore(new HashingEmbedder())
      store.addAll(Chunker.documents(TestData.data))
      val answer = new GolfAssistant(TestData.data, client, new Retriever(store))
        .ask("How many putts did I have on average in the last 10 rounds?")
      assert(answer.contains("37.50"), answer)

      // First request: system prompt + tools; get_profile (no params) must not send an empty schema.
      val first = requests.head
      assertEquals(first("messages")(0)("role").str, "system")
      val tools = first("tools").arr.map(t => t("function")("name").str -> t("function").obj.contains("parameters")).toMap
      assertEquals(tools("get_profile"), false)
      assertEquals(tools("compute_stat"), true)

      // Second request: the assistant tool call is echoed back and answered with a matching tool message.
      val msgs = requests(1)("messages").arr
      assertEquals(msgs(msgs.size - 2)("tool_calls")(0)("id").str, "call_1")
      assertEquals(msgs.last("role").str, "tool")
      assertEquals(msgs.last("tool_call_id").str, "call_1")
    } finally server.stop(0)
  }
}
