package golfrag

import golfrag.stats.*

class StatsEngineSuite extends munit.FunSuite {
  import TestData.rounds

  test("average putts over the last 10 rounds uses the 10 most recent by date") {
    // Most recent 10 are i = 3..12 -> putts 33..42 -> mean 37.5
    val res = StatsEngine.compute(rounds, StatQuery(Metric.Putts, Aggregation.Average, lastN = Some(10)))
    assertEqualsDouble(res.value.get, 37.5, 1e-9)
    assertEquals(res.rounds.size, 10)
  }

  test("order of the input does not matter") {
    val shuffled = scala.util.Random(42).shuffle(rounds)
    val a = StatsEngine.compute(rounds, StatQuery(Metric.Putts, lastN = Some(5)))
    val b = StatsEngine.compute(shuffled, StatQuery(Metric.Putts, lastN = Some(5)))
    assertEquals(a.value, b.value)
  }

  test("total penalties, filtered by course") {
    // Tali = even i: 2,4,6,8,10,12 -> i % 3 = 2,1,0,2,1,0 -> 6
    val res = StatsEngine.compute(rounds, StatQuery(Metric.Penalties, Aggregation.Total, course = Some("tali")))
    assertEquals(res.value, Some(6.0))
  }

  test("best score names the round") {
    val res = StatsEngine.compute(rounds, StatQuery(Metric.Score, Aggregation.Min))
    assertEquals(res.value, Some(78.0))
    assert(res.explain.contains("2026-08-24"), res.explain)
  }

  test("trend is negative when scores go down") {
    val res = StatsEngine.compute(rounds, StatQuery(Metric.Score, Aggregation.Trend))
    assertEqualsDouble(res.value.get, -1.0, 1e-9)
  }

  test("median and percentages") {
    assertEquals(StatsEngine.compute(rounds, StatQuery(Metric.Putts, Aggregation.Median)).value, Some(36.5))
    assertEquals(StatsEngine.compute(rounds, StatQuery(Metric.FairwayPct)).value, Some(50.0))
  }

  test("no data gives a clear message instead of a number") {
    val res = StatsEngine.compute(rounds, StatQuery(Metric.Putts, course = Some("St Andrews")))
    assertEquals(res.value, None)
    assert(res.explain.startsWith("No data"))
  }
}
