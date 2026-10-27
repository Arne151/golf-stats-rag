package golfrag.agent

import golfrag.llm.*
import golfrag.model.GolfData
import golfrag.rag.Retriever
import golfrag.stats.StatsEngine

import java.time.LocalDate

trait Assistant {
  def description: String
  def ask(question: String): String
}

/**
 * The RAG agent:
 *   1. Retrieve – embed the question and pull the most relevant rounds/notes from the vector store.
 *   2. Augment  – put that context, the profile and a data overview into the system prompt.
 *   3. Generate – let the LLM answer, calling tools for exact numbers or more context, in a loop
 *                 until it produces a final text answer.
 * Conversation history is kept so follow-up questions ("and the 10 before that?") work.
 */
final class GolfAssistant(
    data: GolfData,
    llm: LlmClient,
    retriever: Retriever,
    maxToolRounds: Int = 6,
    today: () => LocalDate = () => LocalDate.now(),
    log: String => Unit = _ => ()
) extends Assistant {

  private val tools = new GolfTools(data, retriever)
  private var history: List[ChatMessage] = Nil

  def description: String = s"LLM agent (${llm.name})"

  def reset(): Unit = history = Nil

  private def systemPrompt(context: String): String =
    s"""You are a friendly, knowledgeable golf statistics assistant. Today is ${today()}.
       |
       |Player profile: ${data.profile.describe}
       |Data available: ${StatsEngine.overview(data.rounds)}
       |
       |Rules:
       |- For ANY number (averages, totals, best/worst, counts, trends) call the compute_stat tool. Never do arithmetic yourself.
       |- "Last N rounds" means the N most recent rounds by date.
       |- If the question is ambiguous, state the assumption you made.
       |- Only use the player's data; if something isn't in the data, say so.
       |- Keep answers short. You may add one practical tip relating stats to the handicap or goals.
       |
       |Passages retrieved for the current question (may be incomplete – use tools for more):
       |$context""".stripMargin

  def ask(question: String): String = {
    val hits = retriever.retrieve(question, k = 6)
    log(s"retrieved ${hits.size} passages: ${hits.map(_.doc.id).mkString(", ")}")
    val system = systemPrompt(retriever.formatContext(hits))

    var messages = history :+ ChatMessage.user(question)
    var answer: Option[String] = None
    var round = 0

    while (answer.isEmpty && round <= maxToolRounds) {
      val response = llm.chat(system, messages, if (round < maxToolRounds) tools.specs else Nil)
      messages = messages :+ ChatMessage(Role.Assistant, response.content)

      if (response.toolCalls.isEmpty) answer = Some(response.text)
      else {
        val results = response.toolCalls.map { call =>
          val result = tools.execute(call.name, call.input)
          log(s"tool ${call.name}(${ujson.write(call.input)}) -> ${result.fold("ERROR " + _, identity).take(200)}")
          ToolResultBlock(call.id, call.name, result.merge, isError = result.isLeft)
        }
        messages = messages :+ ChatMessage(Role.User, results)
      }
      round += 1
    }

    val finalAnswer = answer.filter(_.nonEmpty).getOrElse("Sorry, I couldn't work that out – try rephrasing the question.")
    // Keep only the question/answer pairs in history; tool chatter would bloat later prompts.
    history = (history :+ ChatMessage.user(question) :+ ChatMessage.assistant(finalAnswer)).takeRight(20)
    finalAnswer
  }
}
