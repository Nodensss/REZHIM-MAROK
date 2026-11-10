package ru.tomskneftekhim.rezhim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Grade changes in plain words: what a line ran before and after a change of grade, how the main
 * numbers moved, and the path of a marked transition. Neighbouring photos of different grades are
 * only a comparison of two regimes; a transition the operator marked adds its start, end and the
 * points measured on the way. No Android types, so it runs in JVM unit tests.
 */
final class Smeny {
  private Smeny() {}

  /** Consecutive observations of one grade on one line, transition points left out. */
  static final class Run {
    final String line, grade;
    final List<Model.Obs> items = new ArrayList<>();

    Run(String line, String grade) { this.line = line; this.grade = grade; }

    Model.Obs first() { return items.get(0); }
    Model.Obs last() { return items.get(items.size() - 1); }
  }

  /** A change of grade between two runs, or one still in progress (no run of the new grade yet). */
  static final class Pair {
    Run from, to;
    String toGrade;
    final List<Model.Obs> mid = new ArrayList<>();
    boolean ongoing;
    Model.Transition marked;
    double gapHours;
    int duplicate;

    String line() { return from.line; }
    Model.Obs firstAfter() { return ongoing ? mid.get(0) : to.first(); }
    /** When the change happened: the operator's start if marked, else the first snapshot after it. */
    String when() { return marked != null && marked.start != null ? marked.start : firstAfter().time; }
    Model.Obs lastSeen() { return ongoing ? mid.get(mid.size() - 1) : to.first(); }
    boolean finished() { return marked != null && marked.end != null; }
  }

  /** One number of a side: a single photo («ближайшие снимки») or the mean of a run. */
  static final class Side {
    final double v;
    final boolean confirmed, mixed;
    final int n;

    Side(double v, boolean confirmed, boolean mixed, int n) { this.v = v; this.confirmed = confirmed; this.mixed = mixed; this.n = n; }
  }

  /** How a number moved, in words: «▲ больше в 2,1 раза», «▼ ниже на 13 °C». */
  static final class Change {
    final int direction;
    final String text;
    final double score;

    Change(int direction, String text, double score) { this.direction = direction; this.text = text; this.score = score; }
  }

  static final String[] SUM_KEYS = {"z1_f", "z2_f", "z3_f"};
  static final String[] FALLBACK_KEYS = {"initiator1", "initiator2", "initiator3"};

  // ---------------------------------------------------------------- runs and pairs

  static boolean usable(Model.Obs obs) {
    return !obs.excluded && obs.grade != null && obs.line != null && Analysis.timeKnown(obs);
  }

  static boolean transitionPoint(Model.Obs obs) { return Boolean.FALSE.equals(obs.steady); }

  static List<Run> runs(Collection<Model.Obs> all) {
    List<Run> runs = new ArrayList<>();
    for (List<Model.Obs> line : byLine(all).values()) {
      Run current = null;
      for (Model.Obs obs : line) {
        if (transitionPoint(obs)) continue;
        if (current == null || !current.grade.equals(obs.grade)) {
          current = new Run(obs.line, obs.grade);
          runs.add(current);
        }
        current.items.add(obs);
      }
    }
    return runs;
  }

  /** Every change of grade on every line, oldest first; a transition still running comes last on its line. */
  static List<Pair> pairs(Collection<Model.Obs> all, Collection<Model.Transition> transitions, Analysis.Data data) {
    List<Pair> pairs = new ArrayList<>();
    List<Run> runs = runs(all);
    for (Map.Entry<String, List<Model.Obs>> entry : byLine(all).entrySet()) {
      List<Run> line = new ArrayList<>();
      for (Run run : runs) if (run.line.equals(entry.getKey())) line.add(run);
      List<Model.Obs> points = new ArrayList<>();
      for (Model.Obs obs : entry.getValue()) if (transitionPoint(obs)) points.add(obs);
      for (int i = 1; i < line.size(); i++) {
        Pair pair = new Pair();
        pair.from = line.get(i - 1);
        pair.to = line.get(i);
        pair.toGrade = pair.to.grade;
        for (Model.Obs obs : points) {
          if (obs.time.compareTo(pair.from.last().time) > 0 && obs.time.compareTo(pair.to.first().time) < 0) pair.mid.add(obs);
        }
        pairs.add(pair);
      }
      if (line.isEmpty()) continue;
      Run last = line.get(line.size() - 1);
      List<Model.Obs> tail = new ArrayList<>();
      for (Model.Obs obs : points) if (obs.time.compareTo(last.last().time) > 0) tail.add(obs);
      if (!tail.isEmpty() && !tail.get(tail.size() - 1).grade.equals(last.grade)) {
        Pair pair = new Pair();
        pair.from = last;
        pair.toGrade = tail.get(tail.size() - 1).grade;
        pair.mid.addAll(tail);
        pair.ongoing = true;
        pairs.add(pair);
      }
    }
    for (Pair pair : pairs) {
      pair.gapHours = Analysis.hoursBetween(pair.from.last().time, pair.lastSeen().time);
      pair.duplicate = pair.ongoing ? 0 : duplicate(pair.from.last(), pair.to.first(), data);
      pair.marked = marked(pair, transitions);
    }
    pairs.sort(Comparator.comparing((Pair p) -> p.when()).thenComparing(p -> p.line()));
    return pairs;
  }

  /** The operator's marking of this change: same line and grades, started between the two runs. */
  static Model.Transition marked(Pair pair, Collection<Model.Transition> transitions) {
    if (transitions == null) return null;
    String after = pair.from.last().time.substring(0, Math.min(16, pair.from.last().time.length()));
    for (Model.Transition t : transitions) {
      if (t.start == null || !pair.line().equals(t.line) || !pair.from.grade.equals(t.from) || !pair.toGrade.equals(t.to)) continue;
      if (Analysis.hoursBetween(after, t.start) < -1) continue;
      if (!pair.ongoing && t.start.compareTo(pair.to.first().time) > 0) continue;
      return t;
    }
    return null;
  }

  /** Neighbouring photos of different grades with all of five or more shared numbers equal: likely one screen recorded twice. */
  static int duplicate(Model.Obs a, Model.Obs b, Analysis.Data data) {
    Map<String, Double> left = numbers(a, data), right = numbers(b, data);
    int shared = 0;
    for (Map.Entry<String, Double> entry : left.entrySet()) {
      Double other = right.get(entry.getKey());
      if (other == null) continue;
      if (!other.equals(entry.getValue())) return 0;
      shared++;
    }
    return shared >= 5 ? shared : 0;
  }

  private static Map<String, Double> numbers(Model.Obs obs, Analysis.Data data) {
    Map<String, Double> result = new HashMap<>();
    for (Model.Val value : data.values(obs.id)) if (counts(value)) result.putIfAbsent(value.param, value.number);
    return result;
  }

  private static Map<String, List<Model.Obs>> byLine(Collection<Model.Obs> all) {
    Map<String, List<Model.Obs>> byLine = new TreeMap<>();
    for (Model.Obs obs : all) if (usable(obs)) byLine.computeIfAbsent(obs.line, key -> new ArrayList<>()).add(obs);
    for (List<Model.Obs> line : byLine.values()) line.sort(Comparator.comparing((Model.Obs obs) -> obs.time).thenComparing(obs -> obs.id));
    return byLine;
  }

  // ---------------------------------------------------------------- values of a side

  static boolean counts(Model.Val value) { return !value.extra && value.active() && value.hasNumber() && value.param != null; }

  /**
   * Numbers of one side. «Ближайшие снимки»: for each parameter the photo nearest to the change
   * (walking back from the end of the run before it, forward from the start of the run after it).
   * «Среднее»: the mean over the run. Derived: the initiator sum, zone shares and ratios.
   */
  static Map<String, Side> side(List<Model.Obs> items, boolean fromEnd, boolean mean, boolean confirmedOnly, Analysis.Data data) {
    List<Model.Obs> order = new ArrayList<>(items);
    if (fromEnd) java.util.Collections.reverse(order);
    Map<String, List<double[]>> found = new LinkedHashMap<>();
    for (Model.Obs obs : order) {
      Map<String, Model.Val> best = new HashMap<>();
      for (Model.Val value : data.values(obs.id)) {
        if (!counts(value) || confirmedOnly && !value.confirmed()) continue;
        Model.Val old = best.get(value.param);
        if (old == null || value.confirmed() && !old.confirmed()) best.put(value.param, value);
      }
      for (Model.Val value : best.values()) {
        List<double[]> list = found.computeIfAbsent(value.param, key -> new ArrayList<>());
        if (!mean && !list.isEmpty()) continue;
        boolean initiator = value.param.matches("z\\d_f|initiator\\d");
        list.add(new double[]{value.number, value.confirmed() ? 1 : 0, initiator && obs.initiatorMixed ? 1 : 0});
      }
    }
    Map<String, Side> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<double[]>> entry : found.entrySet()) {
      double sum = 0;
      boolean confirmed = true, mixed = false;
      for (double[] item : entry.getValue()) {
        sum += item[0];
        confirmed &= item[1] > 0;
        mixed |= item[2] > 0;
      }
      result.put(entry.getKey(), new Side(sum / entry.getValue().size(), confirmed, mixed, entry.getValue().size()));
    }
    derive(result);
    return result;
  }

  static void derive(Map<String, Side> side) {
    Side[] flows = new Side[3];
    for (int i = 0; i < 3; i++) {
      flows[i] = side.get(SUM_KEYS[i]);
      if (flows[i] == null) flows[i] = side.get(FALLBACK_KEYS[i]);
      if (flows[i] != null) side.putIfAbsent(SUM_KEYS[i], flows[i]);
    }
    if (flows[0] != null && flows[1] != null && flows[2] != null) {
      double sum = flows[0].v + flows[1].v + flows[2].v;
      boolean confirmed = flows[0].confirmed && flows[1].confirmed && flows[2].confirmed;
      boolean mixed = flows[0].mixed || flows[1].mixed || flows[2].mixed;
      side.put("sum", new Side(sum, confirmed, mixed, 1));
      if (sum > 0) {
        for (int i = 0; i < 3; i++) side.put("share" + (i + 1), new Side(100 * flows[i].v / sum, confirmed, mixed, 1));
        Side pal = side.get("pal");
        if (pal != null) side.put("palSum", new Side(pal.v / sum, confirmed && pal.confirmed, mixed, 1));
      }
    }
    Side pal = side.get("pal"), c3 = side.get("c3_flow");
    if (pal != null && c3 != null && c3.v > 0) side.put("palC3", new Side(pal.v / c3.v, pal.confirmed && c3.confirmed, false, 1));
  }

  /** The previous run of the new grade on the same line: what this grade ran last time, as a target. */
  static Run previousRun(List<Run> runs, Pair pair) {
    Run found = null;
    for (Run run : runs) {
      if (run.line.equals(pair.line()) && run.grade.equals(pair.toGrade) && run.last().time.compareTo(pair.from.first().time) < 0) found = run;
    }
    return found;
  }

  // ---------------------------------------------------------------- words and numbers

  /** A change in plain words; temperatures and percentages in absolute terms, flows in times or percent. */
  static Change change(double a, double b, String unit, int places) {
    double d = b - a;
    boolean absolute = "°C".equals(unit) || "%".equals(unit), temperature = "°C".equals(unit);
    String more = temperature ? "выше" : "больше", less = temperature ? "ниже" : "меньше";
    if (Math.abs(d) < 1e-9) return new Change(0, "без изменений", 0);
    int direction = d > 0 ? 1 : -1;
    String arrow = d > 0 ? "▲" : "▼", word = d > 0 ? more : less;
    if (absolute) {
      return new Change(direction, arrow + " " + word + " на " + num(Math.abs(d), places) + (temperature ? " °C" : ""),
        temperature ? Math.abs(d) * 2 : Math.abs(d));
    }
    if (b == 0) return new Change(direction, arrow + " до нуля", 100);
    if (a == 0) return new Change(direction, arrow + " " + word + ", было 0", 100);
    double percent = 100 * d / Math.abs(a), ratio = b / a;
    if (Math.abs(percent) < 1) return new Change(0, "почти без изменений", 0);
    if (ratio >= 1.5) return new Change(direction, arrow + " " + word + " в " + num(ratio, 1) + " раза", Math.abs(percent));
    if (ratio > 0 && ratio <= 1 / 1.5) return new Change(direction, arrow + " " + word + " в " + num(1 / ratio, 1) + " раза", Math.abs(percent));
    return new Change(direction, arrow + " " + word + " на " + num(Math.abs(percent), 0) + " %", Math.abs(percent));
  }

  /** Up to {@code places} decimals (one for values of 100 and more), trailing zeros dropped, decimal comma. */
  static String num(double value, int places) {
    int scale = Math.abs(value) >= 100 ? Math.min(places, 1) : places;
    BigDecimal rounded = BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
    if (rounded.scale() < 0) rounded = rounded.setScale(0, RoundingMode.UNNECESSARY);
    String text = rounded.toPlainString().replace('.', ',');
    return "-0".equals(text) ? "0" : text;
  }

  static String plural(int n, String one, String few, String many) {
    int tens = Math.abs(n) % 100, units = Math.abs(n) % 10;
    return tens > 10 && tens < 20 ? many : units == 1 ? one : units > 1 && units < 5 ? few : many;
  }

  static String gap(double hours) {
    if (hours < 1) return Math.max(1, Math.round(hours * 60)) + " мин";
    if (hours < 6) {
      long minutes = Math.round(hours * 60), h = minutes / 60, m = minutes % 60;
      return m == 0 ? h + " " + plural((int) h, "час", "часа", "часов") : h + " ч " + m + " мин";
    }
    if (hours < 48) {
      long h = Math.round(hours);
      return h + " " + plural((int) h, "час", "часа", "часов");
    }
    long days = Math.round(hours / 24);
    return days + " " + plural((int) days, "день", "дня", "дней");
  }

  static final String[] MONTHS = {"Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"};
  static final String[] MONTHS_OF = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};

  /** «Октябрь 2026» for a plant time. */
  static String month(String time) { return MONTHS[Integer.parseInt(time.substring(5, 7)) - 1] + " " + time.substring(0, 4); }

  /** «22 октября 2026, 02:50» (without the year when {@code year} is false). */
  static String day(String time, boolean year) {
    String text = Integer.parseInt(time.substring(8, 10)) + " " + MONTHS_OF[Integer.parseInt(time.substring(5, 7)) - 1] + (year ? " " + time.substring(0, 4) : "");
    return time.length() >= 16 ? text + ", " + time.substring(11, 16) : text;
  }

  /** Short names for the «Главное» sentence. */
  static String shortName(String key, Map<String, Model.Param> params) {
    switch (key) {
      case "pal": return "ПАЛ";
      case "pal_sp": return "уставка ПАЛ";
      case "sum": return "инициатор всего";
      case "mfr": return "ПТР";
      case "mfr_raw": return "исходный ПТР";
      case "c3_flow": case "propylene": return "пропилен";
      case "drive_current": return "нагрузка привода";
      case "rpm": return "обороты А-404";
      case "vgvd": return "давление ВГВД";
      default:
        if (key.matches("z\\d_f")) return "инициатор в " + key.charAt(1) + "-ю зону";
        if (key.matches("z\\d_sp")) return "уставка Т " + key.charAt(1) + "-й зоны";
        if (key.matches("z\\d_t")) return "Тмакс " + key.charAt(1) + "-й зоны";
        Model.Param param = params.get(key);
        return param == null ? key : param.name;
    }
  }

  static String unit(String key, Map<String, Model.Param> params) {
    if ("sum".equals(key)) key = "z1_f";
    Model.Param param = params.get(key);
    return param == null || param.unit == null ? "" : param.unit;
  }

  static int places(String key, Map<String, Model.Param> params) {
    if ("sum".equals(key)) return 2;
    Model.Param param = params.get(key);
    return param == null ? 2 : param.precision;
  }

  static final String[] HEADLINE_KEYS = {"pal", "sum", "mfr", "c3_flow", "propylene", "drive_current", "rpm",
    "z1_sp", "z2_sp", "z3_sp", "z1_f", "z2_f", "z3_f"};

  /** «Главное»: up to three biggest changes, in words; initiator flows of a solution mix are left out. */
  static String headline(Map<String, Side> a, Map<String, Side> b, Map<String, Model.Param> params) {
    List<Object[]> found = new ArrayList<>();
    for (String key : HEADLINE_KEYS) {
      Side x = a.get(key), y = b.get(key);
      if (x == null || y == null || x.mixed || y.mixed) continue;
      Change c = change(x.v, y.v, unit(key, params), places(key, params));
      if (c.score >= 10) found.add(new Object[]{key, c});
    }
    if (found.isEmpty()) return null;
    found.sort((p, q) -> Double.compare(((Change) q[1]).score, ((Change) p[1]).score));
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < Math.min(3, found.size()); i++) {
      Change c = (Change) found.get(i)[1];
      parts.add(shortName((String) found.get(i)[0], params) + " — " + c.text.replaceFirst("^[▲▼] ", ""));
    }
    return String.join("; ", parts);
  }

  /** Common numbers of two sides, derived ones left out. */
  static int common(Map<String, Side> a, Map<String, Side> b) {
    int n = 0;
    for (String key : a.keySet()) if (b.containsKey(key) && !key.startsWith("share") && !key.equals("palSum") && !key.equals("palC3")) n++;
    return n;
  }

  /** «Сейчас против прежнего режима»: what is already at the target grade's last level and what is not yet. */
  static String[] towardTarget(Map<String, Side> now, Map<String, Side> target, Map<String, Model.Param> params) {
    List<String> far = new ArrayList<>(), near = new ArrayList<>();
    List<Object[]> gaps = new ArrayList<>();
    String[] keys = {"pal", "sum", "mfr", "mfr_raw", "c3_flow", "z1_sp", "z2_sp", "z3_sp", "z1_f", "z2_f", "z3_f", "drive_current", "rpm"};
    for (String key : keys) {
      Side x = now.get(key), y = target.get(key);
      if (x == null || y == null || x.mixed && (key.equals("sum") || key.matches("z\\d_f"))) continue;
      Change c = change(x.v, y.v, unit(key, params), places(key, params));
      if (c.score < 10) near.add(shortName(key, params));
      else gaps.add(new Object[]{key, c.score, x.v, y.v});
    }
    gaps.sort((p, q) -> Double.compare((double) q[1], (double) p[1]));
    for (int i = 0; i < Math.min(4, gaps.size()); i++) {
      String key = (String) gaps.get(i)[0];
      int p = places(key, params);
      far.add(shortName(key, params) + " " + num((double) gaps.get(i)[2], p) + " (было " + num((double) gaps.get(i)[3], p) + ")");
    }
    return new String[]{String.join("; ", far), String.join(", ", near)};
  }

  /** «Больше инициатора стало уходить во 2-ю зону: 37 % → 42 %» or that the split hardly changed. */
  static String shares(Map<String, Side> a, Map<String, Side> b) {
    if (!a.containsKey("share1") || !b.containsKey("share1")) return null;
    int best = 0;
    double bestDelta = -1e9;
    for (int i = 1; i <= 3; i++) {
      double d = b.get("share" + i).v - a.get("share" + i).v;
      if (d > bestDelta) { bestDelta = d; best = i; }
    }
    if (bestDelta < 3) return "Распределение инициатора по зонам почти не изменилось.";
    return "Больше инициатора стало уходить в " + best + "-ю зону: " + num(a.get("share" + best).v, 0) + " % → " + num(b.get("share" + best).v, 0) + " %.";
  }
}
