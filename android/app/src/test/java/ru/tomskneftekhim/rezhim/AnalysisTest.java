package ru.tomskneftekhim.rezhim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class AnalysisTest {
  private final Map<String, Model.Obs> observations = new HashMap<>();
  private final Map<String, List<Model.Val>> values = new HashMap<>();
  private final Analysis.Data data = new Analysis.Data() {
    @Override public Model.Obs obs(String id) { return observations.get(id); }
    @Override public List<Model.Val> values(String obsId) {
      List<Model.Val> found = values.get(obsId);
      return found == null ? Collections.emptyList() : found;
    }
  };

  private Model.Obs obs(String id, String grade, String line, String time, String origin) {
    Model.Obs obs = new Model.Obs();
    obs.id = id;
    obs.grade = grade;
    obs.line = line;
    obs.time = time;
    obs.timeOrigin = origin;
    observations.put(id, obs);
    return obs;
  }

  private void value(String obs, String param, double number, String unit, String status) {
    Model.Val value = new Model.Val();
    value.id = obs + param + number;
    value.obs = obs;
    value.param = param;
    value.number = number;
    value.unit = unit;
    value.status = status;
    values.computeIfAbsent(obs, key -> new ArrayList<>()).add(value);
  }

  private Model.Transition transition(String id, String line, double palBefore, double palAfter, String status) {
    obs(id + "b", "g1", line, "2026-01-01T00:00:00", "screen");
    obs(id + "a", "g2", line, "2026-01-01T06:00:00", "screen");
    value(id + "b", "pal", palBefore, "кг/ч", Model.CONFIRMED);
    value(id + "a", "pal", palAfter, "кг/ч", Model.CONFIRMED);
    Model.Transition transition = new Model.Transition();
    transition.id = id;
    transition.from = "g1";
    transition.to = "g2";
    transition.line = line;
    transition.status = status;
    transition.before.add(id + "b");
    transition.after.add(id + "a");
    return transition;
  }

  private Analysis.Request request(double pal, boolean confirmed) {
    Analysis.Request request = new Analysis.Request();
    request.line = "A";
    request.from = "g1";
    request.to = "g2";
    request.current.put("pal", new Calc.Input(pal, "кг/ч", Model.FACT, confirmed, "ПАЛ"));
    return request;
  }

  @Test public void neighbouringGradesAreOnlyCandidatesWithWarnings() {
    obs("o52", "g4", "A", "2025-09-12T09:10:19", "exif");
    obs("o47", "g5", "A", "2025-09-13T08:42:04", "exif");
    obs("o48", "g5", "A", "2025-09-13T17:34:51", "exif");
    obs("ob", "g5", "B", "2025-09-12T10:00:00", "screen");
    List<Analysis.Candidate> candidates = Analysis.candidates(observations.values(), Collections.emptySet());
    assertEquals(1, candidates.size());
    Analysis.Candidate candidate = candidates.get(0);
    assertEquals("o52", candidate.before.id);
    assertEquals("o47", candidate.after.id);
    assertEquals(23.53, candidate.gapHours, 0.01);
    assertTrue(candidate.warnings.get(0).contains("EXIF"));
  }

  @Test public void excludedAndUndatedObservationsStayOutOfChronology() {
    obs("a", "g1", "A", "2026-01-01T00:00:00", "screen");
    obs("b", "g2", "A", "2026-01-01T01:00:00", "screen").excluded = true;
    obs("c", "g3", "A", null, "unknown");
    assertTrue(Analysis.candidates(observations.values(), Collections.emptySet()).isEmpty());
  }

  @Test public void identicalReadingsOfDifferentGradesAreFlagged() {
    obs("o46", "g3", "A", "2026-07-18T01:06:48", "screen");
    obs("o54", "g5", "A", "2026-07-18T01:08:50", "exif");
    obs("o1", "g1", "A", "2026-09-20T07:03:00", "manual");
    String[] keys = {"pal", "z1_f", "z2_f", "z3_f", "z1_t", "z2_t"};
    for (int i = 0; i < keys.length; i++) {
      value("o46", keys[i], 10 + i, "", Model.REVIEW);
      value("o54", keys[i], 10 + i, "", Model.REVIEW);
      value("o1", keys[i], 20 + i, "", Model.CONFIRMED);
    }
    List<Analysis.Duplicate> duplicates = Analysis.duplicates(new ArrayList<>(observations.values()), data);
    assertEquals(1, duplicates.size());
    assertEquals("o46", duplicates.get(0).a);
    assertEquals(6, duplicates.get(0).common);
    assertTrue(duplicates.get(0).differentGrade);
  }

  @Test public void noConfirmedPairMeansRefusal() {
    List<Model.Transition> transitions = Collections.singletonList(transition("t1", "A", 3, 7, "incomplete"));
    Analysis.Estimate estimate = Analysis.estimate(request(4, true), transitions, data);
    assertEquals("Нет подтверждённых переходов этой пары.", estimate.refusal);
    assertEquals(1, estimate.incomplete);
  }

  @Test public void fewerThanThreeComparableCasesSuppressNumbers() {
    List<Model.Transition> transitions = Arrays.asList(
      transition("t1", "A", 3, 7, "confirmed"), transition("t2", "A", 4, 8, "confirmed"), transition("t3", "B", 5, 9, "confirmed"));
    Analysis.Estimate estimate = Analysis.estimate(request(4, true), transitions, data);
    assertNotNull(estimate.refusal);
    assertTrue(estimate.params.isEmpty());
    assertEquals("другая линия (B)", estimate.episodes.get(2).excluded);
  }

  @Test public void threeConfirmedCasesGiveMedianAndRange() {
    List<Model.Transition> transitions = Arrays.asList(
      transition("t1", "A", 3, 7, "confirmed"), transition("t2", "A", 4, 8.5, "confirmed"), transition("t3", "A", 5, 8, "confirmed"));
    Analysis.Estimate estimate = Analysis.estimate(request(4, true), transitions, data);
    assertNull(estimate.refusal);
    Analysis.ParamEstimate pal = estimate.params.get(0);
    assertEquals(3, pal.count);
    assertEquals(4.0, pal.median, 1e-9);
    assertEquals(3.0, pal.min, 1e-9);
    assertEquals(4.5, pal.max, 1e-9);
    assertNull(pal.suppressed);
    assertEquals(7.0, pal.low, 1e-9);
    assertEquals(8.5, pal.high, 1e-9);
  }

  @Test public void unverifiedOrOutOfRangeCurrentValueSuppressesHint() {
    List<Model.Transition> transitions = Arrays.asList(
      transition("t1", "A", 3, 7, "confirmed"), transition("t2", "A", 4, 8.5, "confirmed"), transition("t3", "A", 5, 8, "confirmed"));
    assertEquals("текущее значение не подтверждено",
      Analysis.estimate(request(4, false), transitions, data).params.get(0).suppressed);
    assertTrue(Analysis.estimate(request(9, true), transitions, data).params.get(0).suppressed.contains("вне области"));
  }

  @Test public void contradictoryCasesAndNormViolationsSuppressHint() {
    List<Model.Transition> mixed = Arrays.asList(
      transition("t1", "A", 3, 7, "confirmed"), transition("t2", "A", 4, 3, "confirmed"), transition("t3", "A", 5, 8, "confirmed"));
    assertTrue(Analysis.estimate(request(4, true), mixed, data).params.get(0).suppressed.contains("противоречат"));

    List<Model.Transition> rising = Arrays.asList(
      transition("t4", "A", 3, 7, "confirmed"), transition("t5", "A", 4, 8.5, "confirmed"), transition("t6", "A", 5, 8, "confirmed"));
    Analysis.Request request = request(4, true);
    request.from = "g1";
    Model.Norm norm = new Model.Norm();
    norm.upper = 8.0;
    request.targetNorms.put("pal", norm);
    assertEquals("диапазон выходит за нормы техкарты целевой марки",
      Analysis.estimate(request, rising, data).params.get(0).suppressed);
  }

  @Test public void unconfirmedValuesDoNotFormLevels() {
    obs("x", "g1", "A", "2026-01-01T00:00:00", "screen");
    value("x", "pal", 5, "кг/ч", Model.REVIEW);
    value("x", "z1_f", 5, "кг/ч", Model.CONFIRMED);
    Map<String, Analysis.Level> levels = Analysis.levels(Collections.singletonList("x"), data);
    assertEquals(1, levels.size());
    assertNotNull(levels.get("z1_f"));
  }
}
