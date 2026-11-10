package ru.tomskneftekhim.rezhim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class RegimesTest {
  private final Map<String, Model.Obs> observations = new LinkedHashMap<>();
  private final Map<String, List<Model.Val>> values = new HashMap<>();
  private final Analysis.Data data = new Analysis.Data() {
    @Override public Model.Obs obs(String id) { return observations.get(id); }
    @Override public List<Model.Val> values(String obsId) {
      List<Model.Val> found = values.get(obsId);
      return found == null ? Collections.emptyList() : found;
    }
  };

  private void obs(String id, String grade, String time, Boolean steady, double pal) {
    Model.Obs obs = new Model.Obs();
    obs.id = id;
    obs.grade = grade;
    obs.line = "A";
    obs.time = time;
    obs.steady = steady;
    observations.put(id, obs);
    Model.Val value = new Model.Val();
    value.id = id + "pal";
    value.obs = id;
    value.param = "pal";
    value.number = pal;
    values.computeIfAbsent(id, key -> new ArrayList<>()).add(value);
  }

  private void history() {
    obs("a1", "g158", "2026-10-08T07:00:00", null, 4.0);
    obs("a2", "g158", "2026-10-08T19:00:00", null, 5.0);
    obs("b1", "g20220", "2026-10-10T05:00:00", null, 20.0);
    obs("c1", "g158", "2026-10-12T05:00:00", null, 5.0);
    obs("d1", "g20220", "2026-10-21T05:00:00", null, 21.0);
    obs("p1", "g158", "2026-10-22T03:55:00", false, 9.0);
    obs("e1", "g158", "2026-11-07T03:51:00", true, 7.0);
  }

  @Test public void regimesAreDaysWithoutTransitionPoints() {
    history();
    List<Regimes.Day> days = Regimes.days(observations.values(), "g158");
    assertEquals(3, days.size());
    assertEquals("A|2026-10-08", days.get(0).key());
    assertEquals(2, days.get(0).items.size());
    assertEquals("A|2026-11-07", days.get(2).key());
    assertEquals(5.0, Smeny.side(days.get(0).items, true, false, false, data).get("pal").v, 1e-9);
    assertNull(Regimes.find(days, "A|2026-10-22"));
    assertEquals("g158", Regimes.currentGrade(observations.values()));
  }

  @Test public void ratioIsTheMedianOverEarlierChanges() {
    history();
    List<Smeny.Pair> pairs = Smeny.pairs(observations.values(), List.of(), data);
    double[] ratio = Regimes.ratio(pairs, "g20220", "g158", "pal", data);
    assertEquals(2, (int) ratio[1]);
    assertEquals((5.0 / 20.0 + 7.0 / 21.0) / 2, ratio[0], 1e-9);
    assertNull(Regimes.ratio(pairs, "g20220", "g158", "mfr", data));
    assertNull(Regimes.ratio(pairs, "g158", "g80210", "pal", data));
  }

  @Test public void typedNumbersGetTheSumAndShares() {
    Map<String, Double> typed = new LinkedHashMap<>();
    typed.put("pal", 20.85);
    typed.put("z1_f", 17.0);
    typed.put("z2_f", 23.0);
    typed.put("z3_f", 10.0);
    Map<String, Smeny.Side> side = Regimes.manual(typed);
    assertEquals(50.0, side.get("sum").v, 1e-9);
    assertEquals(34.0, side.get("share1").v, 1e-9);
    assertFalse(side.get("sum").mixed);
  }
}
