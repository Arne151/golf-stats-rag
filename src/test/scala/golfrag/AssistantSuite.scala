package golfrag

import golfrag.agent.*
import golfrag.llm.*
import golfrag.rag.*
import golfrag.stats.*

class AssistantSuite extends munit.FunSuite {
  import TestData.data

  private def retriever = {
    val store = new InMemoryVectorStore(new HashingEmbedder())
    store.addAll(Chunker.documents(data))
    new Retriever(store)
  }

  test("retrieval finds the round whose notes match the question") {
    val hits = retriever.retrieve("when did I three-putt a lot?", k = 3)
    assert(hits.head.doc.text.contains("Three-putted"), hits.map(_.doc.text).mkString("\n"))
  }

  test("offline mode parses common questions into stat queries") {
    val offline = new OfflineAssistant(data, retriever)
    assertEquals(
      offline.parse("How many putts did I have on average in the last 10 rounds?"),
      Some(StatQuery(Metric.Putts, Aggregation.Average, lastN = Some(10)))
    )
    assertEquals(offline.parse("best score at Tali").map(q => (q.metric, q.aggregation, q.course)),
      Some((Metric.Score, Aggregation.Min, Some("Tali"))))
    assertEquals(offline.parse("total penalties last five rounds").map(_.lastN), Some(Some(5)))
    assertEquals(offline.parse("when did I hit it in the water?"), None) // qualitative -> retrieval
    assert(offline.ask("How many putts did I have on average in the last 10 rounds?").contains("37.50"))
  }

  /** A scripted LLM: first asks for a tool, then answers with what the tool returned. */
  private final class ScriptedLlm extends LlmClient {
    val name = "scripted"
    var seenSystem = ""
    var toolResult = ""
    def chat(system: String, messages: List[ChatMessage], tools: List[ToolSpec]): LlmResponse = {
      seenSystem = system
      messages.last.content match {
        case List(ToolResultBlock(_, _, content, false)) =>
          toolResult = content
          LlmResponse(List(TextBlock(s"Answer: $content")))
        case _ =>
          assert(tools.exists(_.name == "compute_stat"))
          LlmResponse(List(ToolUseBlock("call-1", "compute_stat",
            ujson.Obj("metric" -> "putts", "aggregation" -> "average", "last_n" -> 10))))
      }
    }
  }

  test("agent loop: retrieved context goes into the prompt and tool results feed the answer") {
    val llm = new ScriptedLlm
    val agent = new GolfAssistant(data, llm, retriever)
    val answer = agent.ask("How many putts did I have on average in the last 10 rounds?")
    assert(llm.toolResult.contains("37.50"), llm.toolResult)
    assert(answer.contains("37.50"), answer)
    assert(llm.seenSystem.contains("handicap index 12.4"))
    assert(llm.seenSystem.contains("putts")) // retrieved passages are included
  }

  test("tool errors are returned to the model instead of crashing") {
    val tools = new GolfTools(data, retriever)
    assert(tools.execute("compute_stat", ujson.Obj("metric" -> "birdies", "aggregation" -> "average")).isLeft)
    assert(tools.execute("nope", ujson.Obj()).isLeft)
    assert(tools.execute("list_rounds", ujson.Obj("last_n" -> 2)).toOption.get.linesIterator.size == 2)
  }
}
