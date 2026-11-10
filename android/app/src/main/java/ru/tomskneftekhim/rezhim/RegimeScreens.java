package ru.tomskneftekhim.rezhim;

import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * «Прошлые режимы марки»: every day a grade ran, with its main numbers, and «Переход: откуда → куда»:
 * the regime the line runs now (a snapshot or numbers typed by the operator) against a past regime
 * of the grade it is changing to.
 */
final class RegimeScreens {
  private RegimeScreens() {}

  /** Numbers an operator can type as the present regime. */
  static final String[] MANUAL = {"pal", "drive_current", "z1_f", "z2_f", "z3_f", "z1_sp", "z2_sp", "z3_sp", "mfr", "c3_flow", "rpm"};
  /** Flows that scale with the regime, so an earlier «after / before» ratio is a fair estimate. */
  static final String[] FORECAST = {"pal", "sum", "drive_current", "rpm"};

  // ---------------------------------------------------------------- past regimes of a grade

  private static String grade(MainActivity a, String wanted, String pref) {
    Store s = a.store;
    String id = wanted != null ? wanted : a.prefs().getString(pref, null);
    if (id != null && s.gradeById.get(id) == null) id = null;
    if (id == null && "plan_to".equals(pref)) return null;
    if (id == null) id = Regimes.currentGrade(s.observations);
    if (id == null && !s.grades.isEmpty()) id = s.grades.get(0).id;
    return id;
  }

  private static void chooseGrade(MainActivity a, String title, String pref) {
    Store s = a.store;
    String[] items = new String[s.grades.size()];
    for (int i = 0; i < items.length; i++) {
      items[i] = s.grades.get(i).name + " · " + MainActivity.count(Regimes.days(s.observations, s.grades.get(i).id).size(), "режим", "режима", "режимов");
    }
    a.choose(title, items, which -> {
      a.prefs().edit().putString(pref, s.grades.get(which).id).apply();
      if ("plan_to".equals(pref)) a.prefs().edit().putString("plan_target", "last").apply();
      if ("plan_from".equals(pref) && !"manual".equals(a.prefs().getString("plan_now", "last"))) a.prefs().edit().putString("plan_now", "last").apply();
      a.refresh();
    });
  }

  private static Map<String, Smeny.Side> side(Store s, Regimes.Day day) {
    return Smeny.side(day.items, true, false, false, s);
  }

  private static String title(Regimes.Day day) {
    return Smeny.day(day.date, true) + " · линия " + day.line;
  }

  private static String num(Store s, Map<String, Smeny.Side> side, String key) {
    Smeny.Side v = side.get(key);
    return v == null ? "—" : Smeny.num(v.v, Smeny.places(key, s.paramByKey));
  }

  /** The main numbers of a regime in three short lines. */
  static String summary(Store s, Map<String, Smeny.Side> side) {
    StringBuilder text = new StringBuilder();
    text.append("ПАЛ ").append(num(s, side, "pal")).append(" · ПТР ").append(num(s, side, "mfr")).append(" · нагрузка ").append(num(s, side, "drive_current"));
    if (side.containsKey("z1_f") || side.containsKey("z2_f") || side.containsKey("z3_f")) {
      text.append("\nИнициатор по зонам: ").append(num(s, side, "z1_f")).append(" / ").append(num(s, side, "z2_f")).append(" / ").append(num(s, side, "z3_f"));
      if (side.containsKey("sum")) text.append(" · всего ").append(num(s, side, "sum"));
      if (side.containsKey("sum") && side.get("sum").mixed) text.append(" (смесь растворов)");
    }
    if (side.containsKey("z1_sp") || side.containsKey("z2_sp") || side.containsKey("z3_sp")) {
      text.append("\nУставки Т: ").append(num(s, side, "z1_sp")).append(" / ").append(num(s, side, "z2_sp")).append(" / ").append(num(s, side, "z3_sp"));
    }
    return text.toString();
  }

  static void history(MainActivity a, String wanted) {
    Store s = a.store;
    String gradeId = grade(a, wanted, "regime_grade");
    if (gradeId == null) {
      a.frame("Прошлые режимы", "марок пока нет", "grades");
      return;
    }
    a.prefs().edit().putString("regime_grade", gradeId).apply();
    String name = s.gradeName(gradeId);
    List<Regimes.Day> days = Regimes.days(s.observations, gradeId);
    a.frame("Режимы марки " + name, MainActivity.count(days.size(), "режим", "режима", "режимов") + " · новые сверху", "grades");
    a.buttons("Марка: " + name, () -> chooseGrade(a, "Чьи режимы показать", "regime_grade"), "Сравнить с нынешним", () -> {
      a.prefs().edit().putString("plan_to", gradeId).putString("plan_target", "last").apply();
      a.go(() -> plan(a));
    });
    if (days.isEmpty()) {
      a.note("Установившихся снимков этой марки с известным временем пока нет.");
      return;
    }
    a.note("Каждая карточка — день, когда линия шла на этой марке. Нажмите, чтобы открыть весь режим и сравнить его с нынешним.");
    String month = null;
    for (int i = days.size() - 1; i >= 0; i--) {
      Regimes.Day day = days.get(i);
      String m = Smeny.month(day.date);
      if (!m.equals(month)) {
        month = m;
        a.section(m);
      }
      String key = day.key();
      a.card(title(day), summary(s, side(s, day)) + "\n" + MainActivity.count(day.items.size(), "снимок", "снимка", "снимков"),
        () -> a.go(() -> day(a, gradeId, key)));
    }
    a.buttons("Обычные значения марки", () -> a.go(() -> SmenyScreen.mode(a, gradeId)), "Смены с маркой", () -> {
      a.prefs().edit().putString("smeny_grade", gradeId).apply();
      a.home(() -> SmenyScreen.show(a));
    });
  }

  /** One past regime in full: every number of the day, the grade's usual range and the norm. */
  static void day(MainActivity a, String gradeId, String key) {
    Store s = a.store;
    String name = s.gradeName(gradeId);
    List<Regimes.Day> days = Regimes.days(s.observations, gradeId);
    Regimes.Day day = Regimes.find(days, key);
    if (day == null) {
      a.frame("Режим марки " + name, "снимков этого дня больше нет", "grades");
      return;
    }
    a.frame(name + " · " + Smeny.day(day.date, true), "линия " + day.line + " · " + MainActivity.count(day.items.size(), "снимок", "снимка", "снимков"), "grades");
    a.buttons("Сравнить с нынешним", () -> {
      a.prefs().edit().putString("plan_to", gradeId).putString("plan_target", key).apply();
      a.go(() -> plan(a));
    }, "Сравнить с другим днём", () -> {
      List<Regimes.Day> others = new ArrayList<>();
      for (int i = days.size() - 1; i >= 0; i--) if (!days.get(i).key().equals(key)) others.add(days.get(i));
      if (others.isEmpty()) {
        a.alert("Сравнить не с чем", "У марки " + name + " в базе только этот режим.");
        return;
      }
      String[] items = new String[others.size()];
      for (int i = 0; i < items.length; i++) items[i] = title(others.get(i));
      a.choose("С каким режимом " + name + " сравнить", items, which -> {
        String other = others.get(which).key();
        a.go(() -> diff(a, gradeId, other, key));
      });
    });
    Map<String, Smeny.Side> side = side(s, day);
    List<Model.Obs> all = new ArrayList<>();
    for (Regimes.Day item : days) all.addAll(item.items);
    Model.Doc doc = SmenyScreen.docFor(s, gradeId, day.last().time);
    a.note("Для каждой цифры взят самый поздний снимок дня, где она есть. «Обычно» — разброс по всем " + MainActivity.observations(all.size())
      + " марки." + (doc == null ? "" : " Нормы — по техкарте " + doc.version + "."));
    LinearLayout box = SmenyScreen.box(a);
    Set<String> keys = new LinkedHashSet<>();
    for (String k : SmenyScreen.MODE_KEYS) if (side.containsKey(k)) keys.add(k);
    List<String> rest = new ArrayList<>(side.keySet());
    rest.sort((x, y) -> SmenyScreen.order(s, x) - SmenyScreen.order(s, y));
    keys.addAll(rest);
    for (String k : keys) {
      if (k.startsWith("share")) continue;
      Smeny.Side v = side.get(k);
      int places = places(s, k);
      LinearLayout row = a.vertical();
      row.setPadding(0, a.dp(9), 0, a.dp(9));
      row.addView(a.label(title(s, k), 15, MainActivity.INK, true));
      Model.Norm n = SmenyScreen.norm(s, doc, k);
      boolean outside = n != null && (n.lower != null && v.v < n.lower || n.upper != null && v.v > n.upper);
      row.addView(a.label(Smeny.num(v.v, places) + (v.mixed ? " (смесь растворов)" : ""), 18, outside ? MainActivity.REJECTED : MainActivity.INK, true));
      double[] range = SmenyScreen.range(all, k, s);
      if (range != null && range[2] > 1) row.addView(a.label("обычно: " + span(range, places), 13, MainActivity.MUTED, false));
      if (n != null) row.addView(a.label("норма: " + SmenyScreen.range(s, doc, k) + (outside ? " — вне нормы" : ""), 13, outside ? MainActivity.REJECTED : MainActivity.MUTED, outside));
      SmenyScreen.divider(a, box);
      box.addView(row);
    }
    if (side.containsKey("share1")) {
      a.note("Доли инициатора по зонам: " + Smeny.num(side.get("share1").v, 0) + " / " + Smeny.num(side.get("share2").v, 0) + " / "
        + Smeny.num(side.get("share3").v, 0) + " %.");
    }
    a.section("Снимки этого дня");
    for (int i = day.items.size() - 1; i >= 0; i--) ArchiveScreens.obsCard(a, day.items.get(i));
  }

  private static String span(double[] range, int places) {
    return (range[0] == range[1] ? Smeny.num(range[0], places) : Smeny.num(range[0], places) + " – " + Smeny.num(range[1], places))
      + " · " + MainActivity.observations((int) range[2]);
  }

  private static int places(Store s, String key) {
    return key.equals("palSum") ? 3 : key.equals("palC3") ? 4 : Smeny.places(key, s.paramByKey);
  }

  private static String title(Store s, String key) {
    String unit = key.equals("palSum") || key.equals("palC3") ? "кг" : Smeny.unit(key, s.paramByKey);
    Model.Param param = s.paramByKey.get(key);
    String name = key.equals("sum") ? "Инициатор, всего" : key.equals("palSum") ? "ПАЛ на 1 кг инициатора"
      : key.equals("palC3") ? "ПАЛ на 1 м³ пропилена" : param == null ? key : param.name;
    return name + (unit.isEmpty() ? "" : ", " + unit);
  }

  /** Two regimes of one grade side by side. */
  static void diff(MainActivity a, String gradeId, String earlier, String later) {
    Store s = a.store;
    List<Regimes.Day> days = Regimes.days(s.observations, gradeId);
    Regimes.Day x = Regimes.find(days, earlier), y = Regimes.find(days, later);
    String name = s.gradeName(gradeId);
    if (x == null || y == null) {
      a.frame("Два режима " + name, "снимков больше нет", "grades");
      return;
    }
    if (x.last().time.compareTo(y.last().time) > 0) {
      Regimes.Day swap = x;
      x = y;
      y = swap;
    }
    a.frame("Два режима " + name, Smeny.day(x.date, true) + " → " + Smeny.day(y.date, true), "grades");
    SmenyScreen.Sides sides = new SmenyScreen.Sides();
    sides.a = side(s, x);
    sides.b = side(s, y);
    String headline = Smeny.headline(sides.a, sides.b, s.paramByKey);
    a.card("Главное", Smeny.common(sides.a, sides.b) == 0 ? "В эти дни сняты разные экраны пульта, общих цифр нет."
      : headline == null ? "Крупных отличий нет: общие цифры разошлись меньше чем на 10 %." : headline + ".", null);
    compare(a, sides, Smeny.day(x.date, false), Smeny.day(y.date, false), false, null);
  }

  // ---------------------------------------------------------------- comparison rows

  private static void compare(MainActivity a, SmenyScreen.Sides sides, String from, String to, boolean oneSided, Function<String, List<String>> more) {
    Store s = a.store;
    LinearLayout box = SmenyScreen.box(a);
    Set<String> used = new LinkedHashSet<>();
    for (String key : SmenyScreen.MAIN) {
      switch (key) {
        case "#f": zones(a, box, "Инициатор по зонам", "f", sides, oneSided, more); break;
        case "#sp": zones(a, box, "Уставки температуры зон", "sp", sides, oneSided, more); break;
        case "#share": SmenyScreen.sharesRow(a, box, sides, from, to); break;
        default: row(a, box, key, sides, oneSided, more);
      }
      used.add(key);
    }
    for (String zone : new String[]{"1", "2", "3"}) for (String kind : new String[]{"f", "sp"}) used.add("z" + zone + "_" + kind);
    if (box.getChildCount() == 0) a.page.removeView(box);

    List<String> keys = new ArrayList<>(sides.b.keySet());
    keys.sort((x, y) -> SmenyScreen.order(s, x) - SmenyScreen.order(s, y));
    LinearLayout rest = null;
    for (String key : keys) {
      if (used.contains(key) || key.startsWith("share") || !oneSided && !sides.a.containsKey(key)) continue;
      if (oneSided && !sides.a.containsKey(key) && (key.equals("palSum") || key.equals("palC3"))) continue;
      if (rest == null) {
        a.section("Остальные показатели");
        rest = SmenyScreen.box(a);
      }
      row(a, rest, key, sides, oneSided, more);
    }
  }

  private static void extras(MainActivity a, LinearLayout row, String key, Function<String, List<String>> more) {
    if (more == null) return;
    for (String text : more.apply(key)) row.addView(a.label(text, 13, MainActivity.MUTED, false));
  }

  private static void row(MainActivity a, LinearLayout box, String key, SmenyScreen.Sides sides, boolean oneSided, Function<String, List<String>> more) {
    Store s = a.store;
    Smeny.Side x = sides.a.get(key), y = sides.b.get(key);
    if (y == null || x == null && !oneSided) return;
    int places = places(s, key);
    String unit = key.equals("palSum") || key.equals("palC3") ? "кг" : Smeny.unit(key, s.paramByKey);
    LinearLayout row = a.vertical();
    row.setPadding(0, a.dp(9), 0, a.dp(9));
    row.addView(a.label(title(s, key), 15, MainActivity.INK, true));
    row.addView(a.label((x == null ? "—" : Smeny.num(x.v, places)) + "  →  " + Smeny.num(y.v, places) + (y.mixed ? " (смесь растворов)" : ""), 18, MainActivity.INK, false));
    if (x != null) {
      Smeny.Change c = Smeny.change(x.v, y.v, unit, places);
      row.addView(a.label(c.text, 14, c.direction > 0 ? SmenyScreen.UP : c.direction < 0 ? SmenyScreen.DOWN : MainActivity.MUTED, true));
    }
    extras(a, row, key, more);
    SmenyScreen.divider(a, box);
    box.addView(row);
  }

  private static void zones(MainActivity a, LinearLayout box, String title, String suffix, SmenyScreen.Sides sides, boolean oneSided,
                            Function<String, List<String>> more) {
    Store s = a.store;
    StringBuilder text = new StringBuilder();
    LinearLayout row = a.vertical();
    List<String> notes = new ArrayList<>();
    for (int zone = 1; zone <= 3; zone++) {
      String key = "z" + zone + "_" + suffix;
      Smeny.Side x = sides.a.get(key), y = sides.b.get(key);
      if (y == null || x == null && !oneSided) continue;
      int places = Smeny.places(key, s.paramByKey);
      if (text.length() > 0) text.append('\n');
      text.append(zone).append("-я:  ").append(x == null ? "—" : Smeny.num(x.v, places)).append("  →  ").append(Smeny.num(y.v, places));
      if (x != null) {
        Smeny.Change c = Smeny.change(x.v, y.v, Smeny.unit(key, s.paramByKey), places);
        text.append("  ").append(c.direction > 0 ? "▲" : c.direction < 0 ? "▼" : "=");
      }
      if (more != null) for (String note : more.apply(key)) notes.add(zone + "-я: " + note);
    }
    if (text.length() == 0) return;
    String unit = Smeny.unit("z1_" + suffix, s.paramByKey);
    row.setPadding(0, a.dp(9), 0, a.dp(9));
    row.addView(a.label(title + (unit.isEmpty() ? "" : ", " + unit), 15, MainActivity.INK, true));
    row.addView(a.label(text.toString(), 17, MainActivity.INK, false));
    for (String note : notes) row.addView(a.label(note, 13, MainActivity.MUTED, false));
    SmenyScreen.divider(a, box);
    box.addView(row);
  }

  // ---------------------------------------------------------------- from → to

  private static Map<String, Double> typed(MainActivity a) {
    Map<String, Double> numbers = new LinkedHashMap<>();
    String raw = a.prefs().getString("plan_manual", null);
    if (raw == null) return numbers;
    try {
      JSONObject json = new JSONObject(raw);
      for (Iterator<String> it = json.keys(); it.hasNext(); ) {
        String key = it.next();
        numbers.put(key, json.getDouble(key));
      }
    } catch (JSONException ignored) {
      numbers.clear();
    }
    return numbers;
  }

  /** «С какой марки и с каким режимом → на какую»: the present regime against a past regime of the new grade. */
  static void plan(MainActivity a) {
    Store s = a.store;
    String from = grade(a, null, "plan_from"), to = grade(a, null, "plan_to");
    if (from == null) {
      a.frame("Переход", "марок пока нет", "transitions");
      return;
    }
    String fromName = s.gradeName(from), toName = to == null ? null : s.gradeName(to);
    boolean same = from.equals(to);
    a.frame(to == null ? "Переход с " + fromName : same ? fromName + ": сейчас и раньше" : fromName + " → " + toName,
      "нынешний режим против прошлого режима марки", "transitions");
    a.buttons("С марки: " + fromName, () -> chooseGrade(a, "С какой марки переходите", "plan_from"),
      "На марку: " + (to == null ? "выбрать" : toName), () -> chooseGrade(a, "На какую марку переходите", "plan_to"));

    // The present regime: typed numbers, a chosen day, or the grade's latest day.
    List<Regimes.Day> fromDays = Regimes.days(s.observations, from);
    String nowChoice = a.prefs().getString("plan_now", "last");
    Map<String, Smeny.Side> now;
    String nowText;
    Map<String, Double> typed = typed(a);
    Regimes.Day nowDay = "manual".equals(nowChoice) || "last".equals(nowChoice) ? null : Regimes.find(fromDays, nowChoice);
    if (nowDay == null && !fromDays.isEmpty()) nowDay = fromDays.get(fromDays.size() - 1);
    if ("manual".equals(nowChoice) && !typed.isEmpty()) {
      now = Regimes.manual(typed);
      nowText = "Свои цифры, введены вручную:\n" + summary(s, now);
    } else if (nowDay != null) {
      now = side(s, nowDay);
      nowText = "Снимки " + fromName + " от " + Smeny.day(nowDay.last().time, true) + ":\n" + summary(s, now);
    } else {
      now = new LinkedHashMap<>();
      nowText = "Снимков марки " + fromName + " в базе нет. Введите нынешние цифры вручную.";
    }
    a.card("Нынешний режим", nowText, null);
    a.buttons("Ввести свои цифры", () -> a.go(() -> manual(a)), "Взять из снимков", () -> {
      if (fromDays.isEmpty()) {
        a.alert("Снимков нет", "У марки " + fromName + " в базе нет установившихся снимков.");
        return;
      }
      String[] items = new String[fromDays.size()];
      for (int i = 0; i < items.length; i++) items[i] = title(fromDays.get(items.length - 1 - i));
      a.choose("Какой режим " + fromName + " считать нынешним", items, which -> {
        a.prefs().edit().putString("plan_now", fromDays.get(items.length - 1 - which).key()).apply();
        a.refresh();
      });
    });
    if (to == null) {
      a.note("Выберите марку, на которую переходите, — покажу, какой режим на ней был и что придётся изменить.");
      return;
    }

    // The regime to compare with: the new grade's latest day, a chosen day, or the mean of all its days.
    List<Regimes.Day> toDays = Regimes.days(s.observations, to);
    if (same && nowDay != null && !"manual".equals(nowChoice)) {
      List<Regimes.Day> others = new ArrayList<>();
      for (Regimes.Day day : toDays) if (!day.key().equals(nowDay.key())) others.add(day);
      toDays = others;
    }
    if (toDays.isEmpty()) {
      a.note(same ? "Других режимов марки " + toName + " в базе нет." : "Установившихся снимков марки " + toName + " в базе пока нет — сравнить не с чем.");
      return;
    }
    List<Model.Obs> all = new ArrayList<>();
    for (Regimes.Day day : toDays) all.addAll(day.items);
    String targetChoice = a.prefs().getString("plan_target", "last");
    Regimes.Day targetDay = Regimes.find(toDays, targetChoice);
    boolean mean = "mean".equals(targetChoice);
    if (targetDay == null && !mean) targetDay = toDays.get(toDays.size() - 1);
    Map<String, Smeny.Side> target = mean ? Smeny.side(all, true, true, false, s) : side(s, targetDay);
    String targetLabel = mean ? "в среднем" : Smeny.day(targetDay.date, false);
    a.card("С чем сравниваем", (mean ? "Среднее по всем режимам " + toName + " (" + MainActivity.observations(all.size()) + ")"
      : "Режим " + toName + " от " + Smeny.day(targetDay.last().time, true)) + ":\n" + summary(s, target), null);
    List<Regimes.Day> options = toDays;
    a.buttons("Другой режим " + toName, () -> {
      String[] items = new String[options.size() + 1];
      items[0] = "Среднее по всем режимам";
      for (int i = 0; i < options.size(); i++) items[i + 1] = title(options.get(options.size() - 1 - i));
      a.choose("С каким режимом " + toName + " сравнить", items, which -> {
        a.prefs().edit().putString("plan_target", which == 0 ? "mean" : options.get(options.size() - which).key()).apply();
        a.refresh();
      });
    }, "Все режимы " + toName, () -> a.go(() -> history(a, to)));

    SmenyScreen.Sides sides = new SmenyScreen.Sides();
    sides.a = now;
    sides.b = target;
    if (!now.isEmpty()) {
      String headline = Smeny.headline(now, target, s.paramByKey);
      a.card(same ? "Главное отличие" : "Что придётся изменить", Smeny.common(now, target) == 0 ? "Общих цифр нет: сняты разные экраны пульта."
        : headline == null ? "Крупных отличий нет: общие цифры расходятся меньше чем на 10 %." : headline + ".", null);
    }
    List<Smeny.Pair> pairs = Smeny.pairs(s.observations, s.transitions, s);
    Model.Doc doc = SmenyScreen.docFor(s, to, null);
    Map<String, double[]> ratios = new LinkedHashMap<>();
    if (!same) {
      for (String key : FORECAST) {
        double[] ratio = Regimes.ratio(pairs, from, to, key, s);
        if (ratio != null && now.containsKey(key)) ratios.put(key, ratio);
      }
    }
    a.note("Слева — сейчас, справа — режим " + toName + " (" + targetLabel + "). «Обычно» — разброс по всем режимам " + toName + "."
      + (ratios.isEmpty() ? "" : " «По прошлым сменам» — оценка от нынешней цифры: во сколько раз она менялась при прежних сменах "
      + fromName + " → " + toName + ".") + (doc == null ? "" : " Нормы — по техкарте " + toName + " " + doc.version + "."));
    compare(a, sides, "сейчас", toName, true, key -> {
      List<String> lines = new ArrayList<>();
      int places = places(s, key);
      double[] ratio = ratios.get(key);
      if (ratio != null) {
        lines.add("по " + MainActivity.count((int) ratio[1], "прошлой смене", "прошлым сменам", "прошлым сменам") + " выйдет ≈ "
          + Smeny.num(now.get(key).v * ratio[0], places));
      }
      double[] range = SmenyScreen.range(all, key, s);
      if (range != null && range[2] > 1) lines.add("обычно: " + span(range, places));
      if (SmenyScreen.norm(s, doc, key) != null) lines.add("норма: " + SmenyScreen.range(s, doc, key));
      return lines;
    });

    if (!same) {
      a.section("Как шли такие смены раньше");
      int found = 0;
      for (int i = pairs.size() - 1; i >= 0; i--) {
        Smeny.Pair p = pairs.get(i);
        if (!from.equals(p.from.grade) || !to.equals(p.toGrade)) continue;
        found++;
        SmenyScreen.feedCard(a, p, i);
      }
      if (found == 0) a.note("Смен " + fromName + " → " + toName + " в базе ещё не было.");
    }
  }

  /** The present regime typed by the operator; kept on the phone for the comparison only. */
  static void manual(MainActivity a) {
    Store s = a.store;
    String from = grade(a, null, "plan_from");
    a.frame("Нынешний режим", "свои цифры с пульта", "transitions");
    Map<String, Double> typed = typed(a);
    Map<String, Smeny.Side> base = new LinkedHashMap<>();
    List<Regimes.Day> days = from == null ? new ArrayList<>() : Regimes.days(s.observations, from);
    if (!typed.isEmpty()) base = Regimes.manual(typed);
    else if (!days.isEmpty()) base = side(s, days.get(days.size() - 1));
    a.note("Впишите, что сейчас на пульте. " + (typed.isEmpty() && !days.isEmpty() ? "Поля заполнены из последнего снимка " + s.gradeName(from)
      + " — поправьте то, что изменилось. " : "") + "Пустое поле в сравнение не попадёт. Эти цифры нужны только для сравнения и в базу не записываются.");
    Map<String, EditText> fields = new LinkedHashMap<>();
    for (String key : MANUAL) {
      if (!s.paramByKey.containsKey(key)) continue;
      Smeny.Side v = base.get(key);
      fields.put(key, a.numberInput(title(s, key), v == null ? "" : Smeny.num(v.v, Smeny.places(key, s.paramByKey))));
    }
    a.buttons("Сохранить и сравнить", () -> {
      JSONObject json = new JSONObject();
      for (Map.Entry<String, EditText> entry : fields.entrySet()) {
        String text = entry.getValue().getText().toString().trim().replace(',', '.');
        if (text.isEmpty()) continue;
        try {
          json.put(entry.getKey(), Double.parseDouble(text));
        } catch (NumberFormatException | JSONException error) {
          a.alert("Не число", "«" + title(s, entry.getKey()) + "»: не получилось прочитать «" + text + "».");
          return;
        }
      }
      if (json.length() == 0) {
        a.alert("Пусто", "Впишите хотя бы одну цифру.");
        return;
      }
      a.prefs().edit().putString("plan_manual", json.toString()).putString("plan_now", "manual").apply();
      a.back();
    }, "Вернуть снимок", () -> {
      a.prefs().edit().remove("plan_manual").putString("plan_now", "last").apply();
      a.back();
    });
  }
}
