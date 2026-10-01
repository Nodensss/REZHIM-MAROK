package ru.tomskneftekhim.rezhim;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Chronology candidates, duplicate detection and the search for analogous confirmed
 * transitions (ТЗ, разделы 3, 5 и 6, этапы А–Б). A neighbouring pair of photos is only
 * ever a candidate; numbers for a hint come from confirmed episodes and confirmed values.
 */
final class Analysis {
  private Analysis() {}

  static final int MIN_COMMON_VALUES = 5;
  static final int MIN_CASES = 3;
  static final double PRODUCTION_TOLERANCE = 0.15;
  static final String[] PRODUCTION_KEYS = {"production", "prod"};

  /** What the analysis reads from the database. */
  interface Data {
    Model.Obs obs(String id);
    List<Model.Val> values(String obsId);
  }

  static boolean timeKnown(Model.Obs obs) { return obs.time != null && obs.time.length() >= 16; }

  static LocalDateTime time(String raw) {
    String text = raw.length() == 16 ? raw + ":00" : raw.substring(0, 19);
    return LocalDateTime.parse(text);
  }

  static double hoursBetween(String a, String b) {
    return Duration.between(time(a), time(b)).getSeconds() / 3600.0;
  }

  static String originWarning(Model.Obs obs, String role) {
    switch (obs.timeOrigin == null ? "unknown" : obs.timeOrigin) {
      case "screen": return null;
      case "exif": return "время " + role + " взято из EXIF, это не момент смены марки";
      case "manual": return "время " + role + " введено вручную";
      default: return "происхождение времени " + role + " неизвестно";
    }
  }

  /** Neighbouring photos of different grades on one line. Never a confirmed transition by itself. */
  static final class Candidate {
    final Model.Obs before, after;
    final double gapHours;
    final List<String> warnings = new ArrayList<>();

    Candidate(Model.Obs before, Model.Obs after, double gapHours) {
      this.before = before;
      this.after = after;
      this.gapHours = gapHours;
    }
  }

  static List<Candidate> candidates(Collection<Model.Obs> all, Set<String> disputed) {
    Map<String, List<Model.Obs>> byLine = new TreeMap<>();
    for (Model.Obs obs : all) {
      if (obs.excluded || obs.grade == null || obs.line == null || !timeKnown(obs)) continue;
      byLine.computeIfAbsent(obs.line, key -> new ArrayList<>()).add(obs);
    }
    List<Candidate> result = new ArrayList<>();
    for (List<Model.Obs> line : byLine.values()) {
      line.sort(Comparator.comparing((Model.Obs obs) -> obs.time).thenComparing(obs -> obs.id));
      for (int i = 1; i < line.size(); i++) {
        Model.Obs before = line.get(i - 1), after = line.get(i);
        if (before.grade.equals(after.grade)) continue;
        Candidate candidate = new Candidate(before, after, hoursBetween(before.time, after.time));
        String warning = originWarning(before, "первого снимка");
        if (warning != null) candidate.warnings.add(warning);
        warning = originWarning(after, "второго снимка");
        if (warning != null) candidate.warnings.add(warning);
        if (candidate.gapHours > 24) {
          candidate.warnings.add("между снимками " + Calc.fixed(candidate.gapHours, 1) + " ч — могли быть другие марки и режимы");
        }
        if (candidate.gapHours < 0.25) {
          candidate.warnings.add("снимки почти одновременны — проверьте, не один ли это экран с разной маркой");
        }
        if (disputed.contains(before.id) || disputed.contains(after.id)) {
          candidate.warnings.add("один из замеров на сверке как возможный дубль или спорная марка");
        }
        result.add(candidate);
      }
    }
    return result;
  }

  /** Active numbers of an observation; a parameter with two different active numbers is left out. */
  static Map<String, Double> numbers(List<Model.Val> values) {
    Map<String, Double> result = new HashMap<>();
    Set<String> conflicting = new HashSet<>();
    for (Model.Val value : values) {
      if (value.extra || !value.active() || !value.hasNumber()) continue;
      Double known = result.get(value.param);
      if (known != null && !known.equals(value.number)) conflicting.add(value.param);
      result.put(value.param, value.number);
    }
    for (String key : conflicting) result.remove(key);
    return result;
  }

  /** Two observations sharing at least five parameters with identical numbers. */
  static final class Duplicate {
    final String a, b;
    final int common;
    final boolean differentGrade;

    Duplicate(String a, String b, int common, boolean differentGrade) {
      this.a = a;
      this.b = b;
      this.common = common;
      this.differentGrade = differentGrade;
    }

    String key() { return a + "|" + b; }
  }

  static List<Duplicate> duplicates(List<Model.Obs> all, Data data) {
    List<Model.Obs> sorted = new ArrayList<>(all);
    sorted.sort(Comparator.comparing(obs -> obs.id));
    Map<String, Map<String, Double>> numbers = new HashMap<>();
    for (Model.Obs obs : sorted) numbers.put(obs.id, numbers(data.values(obs.id)));
    List<Duplicate> result = new ArrayList<>();
    for (int i = 0; i < sorted.size(); i++) {
      for (int j = i + 1; j < sorted.size(); j++) {
        Map<String, Double> left = numbers.get(sorted.get(i).id), right = numbers.get(sorted.get(j).id);
        int common = 0;
        boolean equal = true;
        for (Map.Entry<String, Double> entry : left.entrySet()) {
          Double other = right.get(entry.getKey());
          if (other == null) continue;
          common++;
          if (!other.equals(entry.getValue())) { equal = false; break; }
        }
        if (equal && common >= MIN_COMMON_VALUES) {
          Model.Obs a = sorted.get(i), b = sorted.get(j);
          boolean differentGrade = a.grade == null ? b.grade != null : !a.grade.equals(b.grade);
          result.add(new Duplicate(a.id, b.id, common, differentGrade));
        }
      }
    }
    return result;
  }

  /** Median of confirmed numbers of one parameter across several observations. */
  static final class Level {
    final double value;
    final int count;
    final String unit, type;

    Level(double value, int count, String unit, String type) {
      this.value = value;
      this.count = count;
      this.unit = unit;
      this.type = type;
    }
  }

  static Map<String, Level> levels(List<String> obsIds, Data data) {
    Map<String, List<Double>> numbers = new LinkedHashMap<>();
    Map<String, Model.Val> sample = new HashMap<>();
    Set<String> mixedUnits = new HashSet<>();
    for (String id : obsIds) {
      for (Model.Val value : data.values(id)) {
        if (value.extra || !value.confirmed() || !value.hasNumber()) continue;
        Model.Val first = sample.get(value.param);
        if (first != null && !Calc.unit(first.unit).equals(Calc.unit(value.unit))) mixedUnits.add(value.param);
        sample.putIfAbsent(value.param, value);
        numbers.computeIfAbsent(value.param, key -> new ArrayList<>()).add(value.number);
      }
    }
    Map<String, Level> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<Double>> entry : numbers.entrySet()) {
      if (mixedUnits.contains(entry.getKey())) continue;
      Model.Val first = sample.get(entry.getKey());
      result.put(entry.getKey(), new Level(Calc.median(entry.getValue()), entry.getValue().size(), first.unit, first.type));
    }
    return result;
  }

  static Level production(Map<String, Level> levels) {
    for (String key : PRODUCTION_KEYS) {
      Level level = levels.get(key);
      if (level != null && Calc.sameUnit(level.unit, Calc.T_H)) return level;
    }
    return null;
  }

  static Calc.Input currentProduction(Map<String, Calc.Input> current) {
    for (String key : PRODUCTION_KEYS) {
      Calc.Input input = current.get(key);
      if (input != null && input.number != null && Calc.sameUnit(input.unit, Calc.T_H)) return input;
    }
    return null;
  }

  static final class Request {
    String line, from, to, recipe12 = "", recipe3 = "";
    final Map<String, Calc.Input> current = new HashMap<>();
    final Map<String, Model.Norm> targetNorms = new HashMap<>();
  }

  static final class Episode {
    final Model.Transition transition;
    Map<String, Level> before = new HashMap<>(), after = new HashMap<>();
    String excluded;
    final List<String> notes = new ArrayList<>();

    Episode(Model.Transition transition) { this.transition = transition; }
  }

  static final class ParamEstimate {
    String param, unit, type, suppressed;
    int count;
    double median, min, max;
    Double current, low, high;
  }

  static final class Estimate {
    String refusal;
    int incomplete;
    final List<String> notes = new ArrayList<>();
    final List<Episode> episodes = new ArrayList<>();
    final List<ParamEstimate> params = new ArrayList<>();
  }

  /** «В похожих подтверждённых случаях наблюдали…» or an explicit reason why there is no number. */
  static Estimate estimate(Request request, Collection<Model.Transition> transitions, Data data) {
    Estimate estimate = new Estimate();
    List<Model.Transition> pair = new ArrayList<>();
    for (Model.Transition transition : transitions) {
      if (!request.from.equals(transition.from) || !request.to.equals(transition.to)) continue;
      if (transition.confirmed()) pair.add(transition); else estimate.incomplete++;
    }
    if (estimate.incomplete > 0) {
      estimate.notes.add("Неполных эпизодов этой пары: " + estimate.incomplete + ". Они не используются для подсказки.");
    }
    if (pair.isEmpty()) {
      estimate.refusal = "Нет подтверждённых переходов этой пары.";
      return estimate;
    }
    Calc.Input currentProduction = currentProduction(request.current);
    for (Model.Transition transition : pair) {
      Episode episode = new Episode(transition);
      episode.before = levels(transition.before, data);
      episode.after = levels(transition.after, data);
      Level before = production(episode.before);
      if (!request.line.equals(transition.line)) {
        episode.excluded = "другая линия (" + transition.line + ")";
      } else if (usesExcluded(transition, data)) {
        episode.excluded = "эпизод опирается на замер, исключённый из расчётов";
      } else if (currentProduction != null && before != null
        && Math.abs(currentProduction.number - before.value) > PRODUCTION_TOLERANCE * before.value) {
        episode.excluded = "выпуск до перехода " + Calc.fmt(before.value, 1) + " т/ч, сейчас " + Calc.fmt(currentProduction.number, 1) + " т/ч";
      } else if (currentProduction == null || before == null) {
        episode.notes.add("фактический выпуск неизвестен — сопоставимость по нагрузке не проверена");
      }
      if (episode.excluded == null && !request.recipe12.isEmpty()) {
        for (String id : transition.before) {
          Model.Obs obs = data.obs(id);
          if (obs != null && !request.recipe12.equals(obs.recipe12)) {
            episode.notes.add("другая версия рецептуры зон 1–2: " + (obs.recipe12.isEmpty() ? "не указана" : obs.recipe12));
            break;
          }
        }
      }
      estimate.episodes.add(episode);
    }
    List<Episode> comparable = new ArrayList<>();
    for (Episode episode : estimate.episodes) if (episode.excluded == null) comparable.add(episode);
    if (comparable.size() < MIN_CASES) {
      estimate.refusal = "Сопоставимых подтверждённых случаев: " + comparable.size() + ", нужно не менее " + MIN_CASES
        + ". Численная подсказка не выдаётся; ниже — точные значения имеющихся эпизодов.";
      return estimate;
    }
    Map<String, List<Episode>> byParam = new TreeMap<>();
    for (Episode episode : comparable) {
      for (String key : episode.before.keySet()) {
        Level after = episode.after.get(key);
        if (after != null && Calc.unit(after.unit).equals(Calc.unit(episode.before.get(key).unit))) {
          byParam.computeIfAbsent(key, k -> new ArrayList<>()).add(episode);
        }
      }
    }
    for (Map.Entry<String, List<Episode>> entry : byParam.entrySet()) {
      if (entry.getValue().size() < MIN_CASES) continue;
      String key = entry.getKey();
      List<Double> deltas = new ArrayList<>(), befores = new ArrayList<>();
      ParamEstimate estimateOf = new ParamEstimate();
      estimateOf.param = key;
      for (Episode episode : entry.getValue()) {
        Level before = episode.before.get(key);
        deltas.add(episode.after.get(key).value - before.value);
        befores.add(before.value);
        estimateOf.unit = before.unit;
        estimateOf.type = before.type;
      }
      estimateOf.count = deltas.size();
      estimateOf.median = Calc.median(deltas);
      estimateOf.min = deltas.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
      estimateOf.max = deltas.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
      Calc.Input current = request.current.get(key);
      if (current != null && current.number != null) estimateOf.current = current.number;
      estimateOf.suppressed = suppression(estimateOf, current, befores, request.targetNorms.get(key));
      if (estimateOf.suppressed == null && estimateOf.current != null) {
        estimateOf.low = estimateOf.current + estimateOf.min;
        estimateOf.high = estimateOf.current + estimateOf.max;
      }
      estimate.params.add(estimateOf);
    }
    if (estimate.params.isEmpty()) {
      estimate.refusal = "Ни один параметр не подтверждён хотя бы в " + MIN_CASES + " сопоставимых эпизодах.";
    }
    return estimate;
  }

  private static boolean usesExcluded(Model.Transition transition, Data data) {
    List<String> all = new ArrayList<>(transition.before);
    all.addAll(transition.after);
    for (String id : all) {
      Model.Obs obs = data.obs(id);
      if (obs == null || obs.excluded) return true;
    }
    return false;
  }

  private static String suppression(ParamEstimate estimate, Calc.Input current, List<Double> befores, Model.Norm norm) {
    if (Calc.unit(estimate.unit).isEmpty()) return "единица параметра не установлена";
    if (estimate.min < 0 && estimate.max > 0) return "случаи противоречат друг другу: изменение в разные стороны";
    if (current == null || current.number == null) return null;
    if (!current.confirmed) return "текущее значение не подтверждено";
    if (!Calc.sameUnit(current.unit, estimate.unit)) return "единица текущего значения отличается от исторической";
    double low = befores.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
    double high = befores.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
    if (current.number < low || current.number > high) {
      return "текущее значение вне области исторических данных (" + Calc.fmt(low, 0) + "…" + Calc.fmt(high, 0) + ")";
    }
    if (norm != null) {
      if (norm.lower != null && current.number + estimate.min < norm.lower
        || norm.upper != null && current.number + estimate.max > norm.upper) {
        return "диапазон выходит за нормы техкарты целевой марки";
      }
    }
    return null;
  }
}
