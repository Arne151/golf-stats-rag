package golfrag

import golfrag.agent.*
import golfrag.llm.*
import golfrag.rag.*
import golfrag.stats.StatsEngine

import java.nio.file.{Path, Paths}
import scala.io.StdIn
import scala.util.control.NonFatal

/**
 * Command line entry point.
 *
 * {{{
 *   sbt run                                         # sample data, interactive chat
 *   sbt "run --rounds my-rounds.pdf --handicap 14.2"
 *   sbt "run --llm ollama --ask \"Average putts in my last 10 rounds?\""
 *   sbt "run --generate-sample-pdf data/sample-rounds.pdf"
 * }}}
 */
object Main {

  final case class Options(
      roundFiles: List[Path] = Nil,
      profile: Option[Path] = None,
      handicap: Option[Double] = None,
      llm: Option[String] = None,
      embedder: Option[String] = None,
      ask: Option[String] = None,
      verbose: Boolean = false,
      samplePdf: Option[Path] = None,
      help: Boolean = false
  )

  private val usage =
    """Usage: golf-stats-rag [options] [files...]
      |  --rounds <file>        Rounds file (.pdf, .csv, .txt). Repeatable. Bare file arguments work too.
      |  --profile <file.json>  Player profile (name, handicap, homeCourse, goals)
      |  --handicap <number>    Handicap index (overrides profile / PDF)
      |  --llm <provider>       gemini | groq | openai | anthropic | ollama | offline
      |                         (default: the first provider whose API key is set, else offline)
      |  --embedder <name>      hashing | ollama               (default: hashing)
      |  --ask "<question>"     Answer one question and exit
      |  --verbose              Show retrieval hits and tool calls
      |  --generate-sample-pdf <out.pdf>  Write a demo PDF of rounds and exit
      |
      |Environment: GEMINI_API_KEY, GROQ_API_KEY, OPENAI_API_KEY, ANTHROPIC_API_KEY,
      |             LLM_MODEL (override the model), OLLAMA_URL, OLLAMA_MODEL, OLLAMA_EMBED_MODEL""".stripMargin

  def parseArgs(args: List[String], o: Options = Options()): Either[String, Options] = args match {
    case Nil                               => Right(o)
    case ("-h" | "--help") :: rest         => parseArgs(rest, o.copy(help = true))
    case "--rounds" :: f :: rest           => parseArgs(rest, o.copy(roundFiles = o.roundFiles :+ Paths.get(f)))
    case "--profile" :: f :: rest          => parseArgs(rest, o.copy(profile = Some(Paths.get(f))))
    case "--handicap" :: h :: rest         =>
      h.replace(',', '.').toDoubleOption.toRight(s"invalid handicap '$h'").flatMap(v => parseArgs(rest, o.copy(handicap = Some(v))))
    case "--llm" :: p :: rest              => parseArgs(rest, o.copy(llm = Some(p.toLowerCase)))
    case "--embedder" :: e :: rest         => parseArgs(rest, o.copy(embedder = Some(e.toLowerCase)))
    case "--ask" :: q :: rest              => parseArgs(rest, o.copy(ask = Some(q)))
    case "--verbose" :: rest               => parseArgs(rest, o.copy(verbose = true))
    case "--generate-sample-pdf" :: f :: rest => parseArgs(rest, o.copy(samplePdf = Some(Paths.get(f))))
    case flag :: _ if flag.startsWith("--") => Left(s"unknown or incomplete option '$flag'")
    case file :: rest                      => parseArgs(rest, o.copy(roundFiles = o.roundFiles :+ Paths.get(file)))
  }

  def main(args: Array[String]): Unit = parseArgs(args.toList) match {
    case Left(err)                => System.err.println(s"Error: $err\n\n$usage"); sys.exit(2)
    case Right(o) if o.help       => println(usage)
    case Right(o) if o.samplePdf.isDefined =>
      SampleData.writePdf(o.samplePdf.get)
      println(s"Wrote ${o.samplePdf.get}")
    case Right(o)                 => run(o)
  }

  private def env(key: String): Option[String] = sys.env.get(key).map(_.trim).filter(_.nonEmpty)

  def buildLlm(choice: Option[String]): Either[String, Option[LlmClient]] = {
    val ollamaUrl = env("OLLAMA_URL").getOrElse("http://localhost:11434")
    val modelOverride = env("LLM_MODEL")
    // With no --llm flag, use whichever API key is set.
    val autoChoice = List("ANTHROPIC_API_KEY" -> "anthropic", "GEMINI_API_KEY" -> "gemini", "GROQ_API_KEY" -> "groq",
      "OPENAI_API_KEY" -> "openai").collectFirst { case (k, p) if env(k).isDefined => p }
      // No key? Use Ollama if it's running on this machine, otherwise the offline mode.
      .getOrElse(if (Ollama.isRunning(ollamaUrl)) "ollama" else "offline")

    choice.getOrElse(autoChoice) match {
      case "anthropic" =>
        env("ANTHROPIC_API_KEY")
          .toRight("ANTHROPIC_API_KEY is not set")
          .map(k => Some(new AnthropicClient(k, modelOverride.orElse(env("ANTHROPIC_MODEL")).getOrElse(AnthropicClient.DefaultModel))))
      case p if OpenAiCompatibleClient.presets.contains(p) =>
        val preset = OpenAiCompatibleClient.presets(p)
        env(preset.keyEnv)
          .toRight(s"${preset.keyEnv} is not set. Get a key at ${preset.keyUrl}")
          .flatMap(k => OpenAiCompatibleClient.fromPreset(preset, k, modelOverride))
          .map(Some(_))
      case "ollama"  =>
        Ollama.resolveModel(ollamaUrl, modelOverride.orElse(env("OLLAMA_MODEL")))
          .map(model => Some(new OllamaClient(model, ollamaUrl)))
      case "offline" => Right(None)
      case other     => Left(s"unknown LLM provider '$other'")
    }
  }

  def run(o: Options): Unit = {
    val llm = buildLlm(o.llm) match {
      case Left(err) => System.err.println(s"Error: $err"); sys.exit(2)
      case Right(c)  => c
    }

    val (files, profile) =
      if (o.roundFiles.nonEmpty) (o.roundFiles, o.profile)
      else {
        println("No files given – using the sample data in ./data (see --help).")
        (List(Paths.get("data/sample-rounds.csv")), o.profile.orElse(Some(Paths.get("data/profile.json"))))
      }

    val loaded = DataLoader.load(files, profile.filter(_.toFile.exists), o.handicap, llm)
    loaded.messages.foreach(println)
    val data = loaded.data
    if (data.rounds.isEmpty) println("Warning: no rounds were loaded – only notes search will work.")

    val embedder: Embedder = o.embedder.getOrElse("hashing") match {
      case "ollama" =>
        val url   = env("OLLAMA_URL").getOrElse("http://localhost:11434")
        val model = env("OLLAMA_EMBED_MODEL").getOrElse("nomic-embed-text")
        Ollama.requireModel(url, model) match {
          case Left(err) => System.err.println(s"Error: $err"); sys.exit(2)
          case Right(m)  => new OllamaEmbedder(m, url)
        }
      case _        => new HashingEmbedder()
    }
    val store = new InMemoryVectorStore(embedder)
    store.addAll(Chunker.documents(data))
    val retriever = new Retriever(store)

    val log: String => Unit = if (o.verbose) s => println(s"  [debug] $s") else _ => ()
    val assistant: Assistant = llm match {
      case Some(client) => new GolfAssistant(data, client, retriever, log = log)
      case None         => new OfflineAssistant(data, retriever)
    }

    println(s"\n${data.profile.describe}")
    println(StatsEngine.overview(data.rounds))
    println(s"Indexed ${store.size} passages with ${embedder.name}. Answering with ${assistant.description}.\n")

    def answer(q: String): Unit =
      try println(s"\n${assistant.ask(q)}\n")
      catch {
        case _: java.net.ConnectException => println("\nError: could not connect to the AI service. If you use Ollama, make sure the Ollama app is running.\n")
        case NonFatal(e)                  => println(s"\nError: ${Option(e.getMessage).getOrElse(e.toString)}\n")
      }

    o.ask match {
      case Some(q) => answer(q)
      case None =>
        println("Ask about your golf (e.g. \"How many putts did I average in my last 10 rounds?\"). Type :quit to exit, :rounds to list rounds.")
        Iterator.continually(Option(StdIn.readLine("golf> ")))
          .takeWhile(_.isDefined).flatten.map(_.trim)
          .takeWhile(l => l != ":quit" && l != ":q" && l != "exit")
          .foreach {
            case ""        => ()
            case ":rounds" => data.newestFirst.foreach(r => println(s"  ${r.describe}"))
            case ":profile" => println(data.profile.describe)
            case q         => answer(q)
          }
        println("Good luck out there!")
    }
  }
}
