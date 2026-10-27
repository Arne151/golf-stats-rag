package golfrag.ingest

import golfrag.model.PlayerProfile

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Reads the player profile from a small JSON file, e.g.
 *  {{{ {"name": "Arne", "handicap": 14.2, "homeCourse": "Tali", "goals": ["Break 80"]} }}}
 */
object ProfileLoader {

  def fromFile(path: Path): PlayerProfile = fromJson(Files.readString(path, StandardCharsets.UTF_8))

  def fromJson(json: String): PlayerProfile = {
    val obj = ujson.read(json).obj
    PlayerProfile(
      name = obj.get("name").map(_.str).getOrElse("Golfer"),
      handicap = obj.get("handicap").flatMap(v => v.numOpt.orElse(v.strOpt.flatMap(_.replace(',', '.').toDoubleOption))),
      homeCourse = obj.get("homeCourse").flatMap(_.strOpt),
      goals = obj.get("goals").flatMap(_.arrOpt).map(_.toList.flatMap(_.strOpt)).getOrElse(Nil)
    )
  }
}
