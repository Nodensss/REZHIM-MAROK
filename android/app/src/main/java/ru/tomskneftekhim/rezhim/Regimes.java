package ru.tomskneftekhim.rezhim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Past regimes of a grade, one per day and line, and the estimate «what the numbers become on the
 * new grade» from earlier changes between the same two grades. No Android types.
 */
final class Regimes {
  private Regimes() {}

  /** Steady snapshots of one grade taken on one line during one day. */
  static final class Day {
    final String line, date;
    final List<Model.Obs> items = new ArrayList<>();

    Day(String line, String date) { this.line = line; this.date = date; }

    String key() { return line + "|" + date; }
    Model.Obs last() { return items.get(items.size() - 1); }
  }

  /** Regimes of a grade, oldest first; transition points and excluded snapshots are left out. */
  static List<Day> days(Collection<Model.Obs> all, String gradeId) {
    List<Model.Obs> own = new ArrayList<>();
    for (Model.Obs obs : all) {
      if (Smeny.usable(obs) && !Smeny.transitionPoint(obs) && gradeId.equals(obs.grade)) own.add(obs);
    }
    own.sort((x, y) -> x.time.compareTo(y.time));
    Map<String, Day> days = new LinkedHashMap<>();
    for (Model.Obs obs : own) {
      String date = obs.time.substring(0, 10);
      days.computeIfAbsent(obs.line + "|" + date, key -> new Day(obs.line, date)).items.add(obs);
    }
    List<Day> result = new ArrayList<>(days.values());
    result.sort((x, y) -> x.last().time.compareTo(y.last().time));
    return result;
  }

  static Day find(List<Day> days, String key) {
    for (Day day : days) if (day.key().equals(key)) return day;
    return null;
  }

  /** The grade of the latest steady snapshot: what the line runs now. */
  static String currentGrade(Collection<Model.Obs> all) {
    Model.Obs latest = null;
    for (Model.Obs obs : all) {
      if (!Smeny.usable(obs) || Smeny.transitionPoint(obs)) continue;
      if (latest == null || obs.time.compareTo(latest.time) > 0) latest = obs;
    }
    return latest == null ? null : latest.grade;
  }

  /** Numbers typed by the operator, with the initiator sum and shares derived as for a snapshot. */
  static Map<String, Smeny.Side> manual(Map<String, Double> numbers) {
    Map<String, Smeny.Side> side = new LinkedHashMap<>();
    for (Map.Entry<String, Double> entry : numbers.entrySet()) {
      if (entry.getValue() != null) side.put(entry.getKey(), new Smeny.Side(entry.getValue(), true, false, 1));
    }
    Smeny.derive(side);
    return side;
  }

  /**
   * {median ratio «after / before», number of changes} of a parameter over the earlier finished
   * changes from one grade to another; null when there were none with this parameter on both sides.
   */
  static double[] ratio(List<Smeny.Pair> pairs, String from, String to, String key, Analysis.Data data) {
    List<Double> ratios = new ArrayList<>();
    for (Smeny.Pair pair : pairs) {
      if (pair.ongoing || !from.equals(pair.from.grade) || !to.equals(pair.toGrade)) continue;
      Smeny.Side before = Smeny.side(pair.from.items, true, false, false, data).get(key);
      Smeny.Side after = Smeny.side(pair.to.items, false, false, false, data).get(key);
      if (before == null || after == null || before.mixed || after.mixed || before.v <= 0 || after.v <= 0) continue;
      ratios.add(after.v / before.v);
    }
    if (ratios.isEmpty()) return null;
    Collections.sort(ratios);
    int n = ratios.size();
    double median = n % 2 == 1 ? ratios.get(n / 2) : (ratios.get(n / 2 - 1) + ratios.get(n / 2)) / 2;
    return new double[]{median, n};
  }
}
