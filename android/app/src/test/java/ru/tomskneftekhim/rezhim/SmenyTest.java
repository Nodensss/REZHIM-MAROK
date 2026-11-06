package ru.tomskneftekhim.rezhim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class SmenyTest {
  private final Map<String, Model.Obs> observations = new LinkedHashMap<>();
  private final Map<String, List<Model.Val>> values = new HashMap<>();
  private final Analysis.Data data = new Analysis.Data() {
    @Override public Model.Obs obs(String id) { return observations.get(id); }
    @Override public List<Model.Val> values(String obsId) {
      List<Model.Val> found = values.get(obsId);
      return found == null ? Collections.emptyList() : found;
    }
  };
  private final Map<String, Model.Param> params = new HashMap<>();

  public SmenyTest() {
    param("pal", "кг/ч", 2);
    param("mfr", "г/10мин", 3);
    param("z1_f", "кг/ч", 2);
    param("z2_f", "кг/ч", 2);
    param("z3_f", "кг/ч", 2);
    param("z1_sp", "°C", 0);
  }

  private void param(String key, String unit, int precision) {
    Model.Param param = new Model.Param();
    param.key = key;
    param.unit = unit;
    param.precision = precision;
    params.put(key, param);
  }

  private Model.Obs obs(String id, String grade, String time, Boolean steady) {
    Model.Obs obs = new Model.Obs();
    obs.id = id;
    obs.grade = grade;
    obs.line = "A";
    obs.time = time;
    obs.steady = steady;
    observations.put(id, obs);
    return obs;
  }

  private void value(String obs, String param, double number, String status) {
    Model.Val value = new Model.Val();
    value.id = obs + param + number;
    value.obs = obs;
    value.param = param;
    value.number = number;
    value.status = status;
    values.computeIfAbsent(obs, key -> new ArrayList<>()).add(value);
  }

  private void zones(String obs, double z1, double z2, double z3) {
    value(obs, "z1_f", z1, Model.REVIEW);
    value(obs, "z2_f", z2, Model.REVIEW);
    value(obs, "z3_f", z3, Model.REVIEW);
  }

  private Model.Transition transition(String start, String end) {
    Model.Transition t = new Model.Transition();
    t.id = "t1";
    t.from = "g20220";
    t.to = "g158";
    t.line = "A";
    t.start = start;
    t.end = end;
    return t;
  }

  /** 20220 → (points marked «переход») → 158, as on 21–22.10.2026. */
  private void october() {
    obs("a", "g20220", "2026-10-21T05:03:03", null);
    value("a", "pal", 21.28, Model.REVIEW);
    zones("a", 17.21, 23.04, 17.16);
    obs("p1", "g158", "2026-10-22T03:55:47", false).initiatorMixed = true;
    value("p1", "pal", 5.62, Model.REVIEW);
    zones("p1", 26.23, 31.47, 21.91);
    obs("p2", "g158", "2026-10-22T04:54:48", false);
    value("p2", "mfr", 2.0, Model.REVIEW);
    obs("b", "g158", "2026-11-07T03:51:22", true);
    value("b", "pal", 6.61, Model.REVIEW);
    value("b", "mfr", 2.0, Model.REVIEW);
    zones("b", 28.16, 36.49, 18.71);
  }

  @Test public void transitionPointsLieBetweenRunsAndTheMarkingIsFound() {
    october();
    List<Smeny.Pair> pairs = Smeny.pairs(observations.values(), List.of(transition("2026-10-22T02:50:00", "2026-10-22T05:30:00")), data);
    assertEquals(1, pairs.size());
    Smeny.Pair pair = pairs.get(0);
    assertFalse(pair.ongoing);
    assertEquals("g20220", pair.from.grade);
    assertEquals("g158", pair.toGrade);
    assertEquals(2, pair.mid.size());
    assertEquals("b", pair.to.first().id);
    assertNotNull(pair.marked);
    assertTrue(pair.finished());
  }

  @Test public void trailingTransitionPointsAreAnOngoingChange() {
    obs("a", "g20220", "2026-10-21T05:03:03", null);
    value("a", "pal", 21.28, Model.REVIEW);
    obs("p1", "g158", "2026-10-22T03:55:47", false);
    value("p1", "pal", 5.62, Model.REVIEW);
    List<Smeny.Pair> pairs = Smeny.pairs(observations.values(), List.of(transition("2026-10-22T02:50:00", null)), data);
    assertEquals(1, pairs.size());
    assertTrue(pairs.get(0).ongoing);
    assertFalse(pairs.get(0).finished());
    assertEquals("p1", pairs.get(0).lastSeen().id);
  }

  @Test public void markingOutsideTheChangeIsIgnored() {
    october();
    List<Smeny.Pair> pairs = Smeny.pairs(observations.values(), List.of(transition("2025-01-01T00:00:00", null)), data);
    assertNull(pairs.get(0).marked);
  }

  @Test public void nearestPhotoAndMeanDiffer() {
    obs("x1", "g1", "2026-01-01T00:00:00", null);
    value("x1", "pal", 4.0, Model.REVIEW);
    obs("x2", "g1", "2026-01-01T06:00:00", null);
    value("x2", "pal", 6.0, Model.CONFIRMED);
    List<Model.Obs> run = List.of(observations.get("x1"), observations.get("x2"));
    assertEquals(6.0, Smeny.side(run, true, false, false, data).get("pal").v, 1e-9);
    assertEquals(4.0, Smeny.side(run, false, false, false, data).get("pal").v, 1e-9);
    Smeny.Side mean = Smeny.side(run, true, true, false, data).get("pal");
    assertEquals(5.0, mean.v, 1e-9);
    assertEquals(2, mean.n);
    assertFalse(mean.confirmed);
    assertEquals(6.0, Smeny.side(run, false, false, true, data).get("pal").v, 1e-9);
  }

  @Test public void initiatorSumSharesAndMixFlag() {
    october();
    Map<String, Smeny.Side> now = Smeny.side(List.of(observations.get("p1")), true, false, false, data);
    assertEquals(79.61, now.get("sum").v, 1e-9);
    assertEquals(100 * 26.23 / 79.61, now.get("share1").v, 1e-9);
    assertTrue(now.get("sum").mixed);
    assertFalse(now.get("pal").mixed);
  }

  @Test public void headlineSkipsMixedFlowsAndOrdersBySize() {
    october();
    Map<String, Smeny.Side> before = Smeny.side(List.of(observations.get("a")), true, false, false, data);
    Map<String, Smeny.Side> during = Smeny.side(List.of(observations.get("p1")), true, false, false, data);
    assertEquals("ПАЛ — меньше в 3,8 раза", Smeny.headline(before, during, params));
    Map<String, Smeny.Side> after = Smeny.side(List.of(observations.get("b")), false, false, false, data);
    String headline = Smeny.headline(before, after, params);
    assertEquals("ПАЛ — меньше в 3,2 раза; инициатор в 1-ю зону — больше в 1,6 раза; инициатор в 2-ю зону — больше в 1,6 раза", headline);
  }

  @Test public void changesInWords() {
    assertEquals("▲ больше в 2,1 раза", Smeny.change(3.52, 7.46, "кг/ч", 2).text);
    assertEquals("▼ ниже на 13 °C", Smeny.change(295, 282, "°C", 0).text);
    assertEquals("▲ больше на 17 %", Smeny.change(92.37, 108.5, "кг/ч", 2).text);
    assertEquals("▼ до нуля", Smeny.change(45.1, 0, "м³/ч", 1).text);
    assertEquals("▲ больше, было 0", Smeny.change(0, 24.9, "м³/ч", 1).text);
    assertEquals("без изменений", Smeny.change(2.0, 2.0, "г/10мин", 3).text);
    assertEquals("почти без изменений", Smeny.change(100, 100.5, "", 2).text);
    assertEquals(0, Smeny.change(100, 100.5, "", 2).direction);
  }

  @Test public void numbersDatesAndGaps() {
    assertEquals("3,52", Smeny.num(3.52, 2));
    assertEquals("2", Smeny.num(2.0019, 2));
    assertEquals("318", Smeny.num(318.0, 0));
    assertEquals("108,5", Smeny.num(108.5, 2));
    assertEquals("1 ч 29 мин", Smeny.gap(1.4833333));
    assertEquals("2 ч 40 мин", Smeny.gap(2 + 40 / 60.0));
    assertEquals("24 часа", Smeny.gap(24));
    assertEquals("16 дней", Smeny.gap(16 * 24 + 1));
    assertEquals("Октябрь 2026", Smeny.month("2026-10-22T02:50:00"));
    assertEquals("22 октября 2026, 02:50", Smeny.day("2026-10-22T02:50:00", true));
    assertEquals("7 ноября, 03:51", Smeny.day("2026-11-07T03:51:22", false));
  }

  @Test public void towardTargetSplitsDoneAndRemaining() {
    october();
    Map<String, Smeny.Side> now = Smeny.side(List.of(observations.get("p2"), observations.get("p1")), true, false, false, data);
    Map<String, Smeny.Side> target = new HashMap<>();
    target.put("pal", new Smeny.Side(4.48, false, false, 1));
    target.put("mfr", new Smeny.Side(1.997, false, false, 1));
    target.put("z1_f", new Smeny.Side(32, false, false, 1));
    String[] split = Smeny.towardTarget(now, target, params);
    assertEquals("ПАЛ 5,62 (было 4,48)", split[0]);
    assertEquals("ПТР", split[1]);
  }

  @Test public void sharesSentence() {
    Map<String, Smeny.Side> a = new HashMap<>(), b = new HashMap<>();
    for (int i = 1; i <= 3; i++) {
      a.put("share" + i, new Smeny.Side(new double[]{38, 37, 25}[i - 1], false, false, 1));
      b.put("share" + i, new Smeny.Side(new double[]{31, 42, 27}[i - 1], false, false, 1));
    }
    assertEquals("Больше инициатора стало уходить в 2-ю зону: 37 % → 42 %.", Smeny.shares(a, b));
  }

  @Test public void previousRunOfTheTargetGrade() {
    obs("o1", "g158", "2026-10-08T07:00:00", null);
    obs("o2", "g20220", "2026-10-21T05:03:03", null);
    obs("p1", "g158", "2026-10-22T03:55:47", false);
    List<Smeny.Run> runs = Smeny.runs(observations.values());
    Smeny.Pair pair = Smeny.pairs(observations.values(), List.of(), data).get(1);
    assertTrue(pair.ongoing);
    assertSame(runs.get(0).first(), Smeny.previousRun(runs, pair).first());
  }
}
