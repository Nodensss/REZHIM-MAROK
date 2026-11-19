package ru.tomskneftekhim.rezhim;

import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * «Смены марок»: a feed of grade changes in plain words («ПАЛ 3,52 → 7,46 кг/ч, больше в 2,1 раза»),
 * the path of a marked transition, and «Режим марки»: what a grade last ran and its usual range.
 */
final class SmenyScreen {
  private SmenyScreen() {}

  static final int UP = Color.rgb(196, 61, 55);
  static final int DOWN = Color.rgb(36, 104, 184);
  static final int[] ZONES = {Color.rgb(134, 182, 239), Color.rgb(42, 120, 214), Color.rgb(24, 79, 149)};

  /** Main lines of a change, in the order an operator reads them; «#…» are groups by zone. */
  static final String[] MAIN = {"pal", "sum", "#f", "#share", "#sp", "mfr", "mfr_raw", "mfr_lab", "c3_flow", "propylene", "drive_current", "rpm"};

  private static boolean mean(MainActivity a) { return a.prefs().getBoolean("smeny_mean", false); }
  private static boolean confirmedOnly(MainActivity a) { return a.prefs().getBoolean("smeny_confirmed", false); }
  private static String gradeFilter(MainActivity a) { return a.prefs().getString("smeny_grade", null); }

  // ---------------------------------------------------------------- feed

  static void show(MainActivity a) {
    Store s = a.store;
    List<Smeny.Pair> pairs = Smeny.pairs(s.observations, s.transitions, s);
    String grade = gradeFilter(a);
    List<Integer> shown = new ArrayList<>();
    for (int i = pairs.size() - 1; i >= 0; i--) {
      Smeny.Pair p = pairs.get(i);
      if (grade == null || grade.equals(p.from.grade) || grade.equals(p.toGrade)) shown.add(i);
    }
    a.frame("Смены марок", MainActivity.count(pairs.size(), "смена", "смены", "смен") + " марки · новые сверху", "transitions");
    a.note("Что менялось в режиме, когда линия переходила с одной марки на другую. Соседние снимки разных марок — это сравнение двух "
      + "режимов; размеченный переход показывает ещё и путь между ними. Нажмите карточку, чтобы открыть все цифры.");
    a.buttons("Переход: откуда → куда", () -> a.go(() -> RegimeScreens.plan(a)), "Прошлые режимы", () -> a.go(() -> RegimeScreens.history(a, null)));
    a.buttons(grade == null ? "Марка: все" : "Марка: " + s.gradeName(grade), () -> chooseGrade(a),
      mean(a) ? "Среднее за период" : "Ближайшие снимки", () -> {
        a.prefs().edit().putBoolean("smeny_mean", !mean(a)).apply();
        a.refresh();
      });
    a.buttons(confirmedOnly(a) ? "Только сверенные: да" : "Только сверенные: нет", () -> {
      a.prefs().edit().putBoolean("smeny_confirmed", !confirmedOnly(a)).apply();
      a.refresh();
    }, "Разметка переходов", () -> a.go(() -> TransitionScreens.list(a)));
    if (shown.isEmpty()) a.note(pairs.isEmpty() ? "Соседних снимков разных марок пока нет." : "С этой маркой смен не найдено.");
    String month = null;
    for (int index : shown) {
      Smeny.Pair p = pairs.get(index);
      String m = Smeny.month(p.when());
      if (!m.equals(month)) {
        month = m;
        a.section(m);
      }
      feedCard(a, p, index);
    }
  }

  private static void chooseGrade(MainActivity a) {
    Store s = a.store;
    String[] items = new String[s.grades.size() + 1];
    items[0] = "Все марки";
    for (int i = 0; i < s.grades.size(); i++) items[i + 1] = s.grades.get(i).name;
    a.choose("Показывать смены с маркой", items, which -> {
      a.prefs().edit().putString("smeny_grade", which == 0 ? null : s.grades.get(which - 1).id).apply();
      a.refresh();
    });
  }

  static void feedCard(MainActivity a, Smeny.Pair p, int index) {
    Store s = a.store;
    Sides sides = sides(a, p);
    String summary;
    if (Smeny.common(sides.a, sides.b) == 0) summary = "Сравнить нечего: до и после сняты разные экраны пульта.";
    else if (sides.target != null) {
      String[] split = Smeny.towardTarget(sides.b, sides.target, s.paramByKey);
      summary = split[0].isEmpty() ? "Общие цифры уже как на прежнем режиме " + s.gradeName(p.toGrade) + "." : "Ещё отличаются: " + split[0] + ".";
    } else {
      String headline = Smeny.headline(sides.a, sides.b, s.paramByKey);
      summary = headline == null ? "Крупных изменений нет: общие цифры сдвинулись меньше чем на 10 %." : "Главное: " + headline + ".";
    }
    LinearLayout card = a.card(s.gradeName(p.from.grade) + " → " + s.gradeName(p.toGrade) + " · линия " + p.line(),
      when(p) + "\n" + summary, () -> a.go(() -> pair(a, index)));
    if (p.ongoing && !p.finished()) a.badge(card, "Идёт переход", MainActivity.REVIEW);
    else if (p.marked != null) {
      a.badge(card, "Переход размечен" + (p.finished() ? ": " + Smeny.gap(Analysis.hoursBetween(p.marked.start, p.marked.end)) : ""), MainActivity.CONFIRMED);
    } else if (p.gapHours > 72) a.badge(card, "Между снимками " + Smeny.gap(p.gapHours) + " — могли быть другие марки", MainActivity.MUTED);
    if (p.duplicate > 0) a.badge(card, "Похоже на дубль снимка: совпадают все " + p.duplicate + " общих цифр", MainActivity.REJECTED);
  }

  private static String when(Smeny.Pair p) {
    if (p.ongoing) return "Было: " + Smeny.day(p.from.last().time, false) + " · сейчас: " + Smeny.day(p.lastSeen().time, false);
    return Smeny.day(p.from.last().time, true) + " → " + Smeny.day(p.to.first().time, true) + " · между снимками " + Smeny.gap(p.gapHours);
  }

  // ---------------------------------------------------------------- one change

  static final class Sides {
    Map<String, Smeny.Side> a, b, target;
    Smeny.Run previous;
  }

  private static Sides sides(MainActivity a, Smeny.Pair p) {
    Store s = a.store;
    Sides sides = new Sides();
    boolean mean = mean(a), confirmed = confirmedOnly(a);
    sides.a = Smeny.side(p.from.items, true, mean, confirmed, s);
    if (p.ongoing) {
      sides.b = Smeny.side(p.mid, true, false, confirmed, s);
      sides.previous = Smeny.previousRun(Smeny.runs(s.observations), p);
      if (sides.previous != null) sides.target = Smeny.side(sides.previous.items, true, mean, confirmed, s);
    } else {
      sides.b = Smeny.side(p.to.items, false, mean, confirmed, s);
    }
    return sides;
  }

  static void pair(MainActivity a, int index) {
    Store s = a.store;
    List<Smeny.Pair> pairs = Smeny.pairs(s.observations, s.transitions, s);
    if (index >= pairs.size()) { a.back(); return; }
    Smeny.Pair p = pairs.get(index);
    Sides sides = sides(a, p);
    String from = s.gradeName(p.from.grade), to = s.gradeName(p.toGrade);
    String state = p.ongoing && !p.finished() ? "идёт переход" : p.marked != null ? "переход размечен" : "смена по соседним снимкам";
    a.frame(from + " → " + to, Smeny.month(p.when()) + " · линия " + p.line() + " · " + state, "transitions");

    StringBuilder intro = new StringBuilder();
    if (p.marked != null) {
      intro.append("Переход размечен: начат ").append(Smeny.day(p.marked.start, true));
      if (p.marked.end != null) {
        intro.append(", режим ").append(to).append(" устоявшийся с ").append(Smeny.day(p.marked.end, false))
          .append(" — переход занял ").append(Smeny.gap(Analysis.hoursBetween(p.marked.start, p.marked.end)));
      }
      intro.append(".\n");
      // Where the times came from: the operator's words, a trend, a journal.
      if (p.marked.notes != null && !p.marked.notes.isEmpty()) intro.append(p.marked.notes).append("\n");
    }
    intro.append("Было: ").append(from).append(", ").append(mean(a) ? "среднее по " + MainActivity.observations(p.from.items.size()) + " до "
      : "последний снимок ").append(Smeny.day(p.from.last().time, true)).append(".\n");
    if (p.ongoing) {
      intro.append("Сейчас: последняя точка перехода ").append(Smeny.day(p.lastSeen().time, true)).append(".");
      if (p.finished()) intro.append(" Снимков устоявшегося режима после окончания перехода пока нет.");
      if (sides.previous != null) {
        intro.append("\nСеро под цифрами — прежний режим ").append(to).append(" на этой линии, снимки по ")
          .append(Smeny.day(sides.previous.last().time, true)).append(".");
      }
    } else {
      intro.append("Стало: ").append(to).append(", ").append(mean(a) ? "среднее по " + MainActivity.observations(p.to.items.size()) + " с "
        : "первый снимок ").append(Smeny.day(p.to.first().time, true)).append(". Между снимками ").append(Smeny.gap(p.gapHours)).append(".");
    }
    a.note(intro.toString());

    if (p.gapHours > 72 && p.marked == null) a.warn("Между снимками " + Smeny.gap(p.gapHours) + " — за это время могли быть и другие марки. Это сравнение двух режимов, а не картина перехода.");
    if (p.duplicate > 0) a.warn("Похоже на ошибку в базе: у обеих марок совпадают все " + p.duplicate + " общих цифр. Возможно, один и тот же снимок записан на две марки.");
    boolean mixed = sides.b.containsKey("sum") && sides.b.get("sum").mixed;
    if (mixed) a.warn("Расходы инициатора здесь — смесь старого раствора и нового (со слов оператора, раствор заменён не полностью). Для сравнения они не годятся.");
    int review = 0, total = 0;
    for (String key : sides.a.keySet()) {
      if (!sides.b.containsKey(key)) continue;
      total += 2;
      if (!sides.a.get(key).confirmed) review++;
      if (!sides.b.get(key).confirmed) review++;
    }
    boolean marks = review > 0 && review < total;
    if (total > 0) a.note(review == total ? "Цифры ещё не сверены с фото." : review > 0 ? "Цифры со знаком «?» ещё не сверены с фото." : "Все цифры сверены с фото.");

    if (Smeny.common(sides.a, sides.b) == 0) {
      a.card("Сравнить нечего", "До и после смены сняты разные экраны пульта, общих цифр нет." + (mean(a) ? "" : " Попробуйте «Среднее за период» в ленте."), null);
    } else if (sides.target != null) {
      String[] split = Smeny.towardTarget(sides.b, sides.target, s.paramByKey);
      a.card("Сейчас против прежнего режима " + to, (split[0].isEmpty() ? "Все общие цифры уже близки к прежним." : "Ещё отличаются: " + split[0] + ".")
        + (split[1].isEmpty() ? "" : "\nУже на прежнем уровне: " + split[1] + "."), null);
    } else {
      String headline = Smeny.headline(sides.a, sides.b, s.paramByKey);
      a.card("Главное", headline == null ? "Крупных изменений нет: общие цифры сдвинулись меньше чем на 10 %." : headline + ".", null);
    }

    LinearLayout box = box(a);
    List<String> used = new ArrayList<>();
    for (String key : MAIN) {
      switch (key) {
        case "#f": zoneRow(a, box, "Инициатор по зонам" + (mixed ? " · смесь растворов" : ""), "f", sides, marks); break;
        case "#sp": zoneRow(a, box, "Уставки температуры зон", "sp", sides, marks); break;
        case "#share": sharesRow(a, box, sides, from, p.ongoing ? "сейчас" : to); break;
        case "sum": line(a, box, key, "Инициатор, всего" + (mixed ? " · смесь растворов" : ""), sides, marks, true); break;
        default: line(a, box, key, null, sides, marks, true);
      }
      used.add(key);
    }
    for (String zone : new String[]{"1", "2", "3"}) for (String kind : new String[]{"f", "sp"}) used.add("z" + zone + "_" + kind);

    LinearLayout rest = null;
    List<String> keys = new ArrayList<>(sides.a.keySet());
    keys.sort((x, y) -> order(s, x) - order(s, y));
    for (String key : keys) {
      if (used.contains(key) || !sides.b.containsKey(key) || key.startsWith("share")) continue;
      if (rest == null) {
        a.section("Остальные показатели");
        rest = box(a);
      }
      String name = key.equals("palSum") ? "ПАЛ на 1 кг инициатора" : key.equals("palC3") ? "ПАЛ на 1 м³ пропилена" : null;
      line(a, rest, key, name, sides, marks, false);
    }

    path(a, p);
    norms(a, p, from, to);

    a.section("Действия");
    Model.Obs before = p.from.last(), after = p.lastSeen();
    a.buttons("Снимок «было»", () -> a.go(() -> ObsScreens.show(a, before.id)), p.ongoing ? "Точка «сейчас»" : "Снимок «стало»",
      () -> a.go(() -> ObsScreens.show(a, after.id)));
    a.buttons("Сравнить снимки", () -> {
      a.setCompareSlot("a", before.id);
      a.setCompareSlot("b", after.id);
      a.home(() -> CompareScreen.show(a));
    }, p.marked != null ? "Разметка перехода" : "Разметить переход", () -> TransitionScreens.open(a,
      p.marked != null ? TransitionScreens.draftOf(p.marked) : draft(p)));
  }

  static int order(Store s, String key) {
    int i = 0;
    for (Model.Param param : s.params) {
      if (param.key.equals(key)) return i;
      i++;
    }
    return i + (key.startsWith("pal") ? 1 : 2);
  }

  /** A new marking prefilled with this change: grades, line, the photo before and the photo after. */
  private static JSONObject draft(Smeny.Pair p) {
    JSONObject draft = TransitionScreens.blank(null);
    try {
      draft.put("from", p.from.grade).put("to", p.toGrade).put("line", p.line());
      draft.getJSONArray("before").put(p.from.last().id);
      JSONArray during = draft.getJSONArray("during");
      for (Model.Obs obs : p.mid) during.put(obs.id);
      if (!p.ongoing) draft.getJSONArray("after").put(p.to.first().id);
    } catch (JSONException error) {
      throw new IllegalStateException(error);
    }
    return draft;
  }

  // ---------------------------------------------------------------- rows

  static LinearLayout box(MainActivity a) {
    LinearLayout box = a.vertical();
    box.setBackground(a.box(Color.WHITE, MainActivity.BORDER));
    box.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(4));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = a.dp(9);
    a.page.addView(box, params);
    return box;
  }

  private static String value(Smeny.Side side, int places, boolean marks) {
    return Smeny.num(side.v, places) + (marks && !side.confirmed ? "?" : "");
  }

  private static void line(MainActivity a, LinearLayout box, String key, String name, Smeny.Side x, Smeny.Side y, Smeny.Side t,
                           Store s, boolean marks, String targetGrade) {
    String unit = Smeny.unit(key, s.paramByKey);
    int places = key.equals("palSum") ? 3 : key.equals("palC3") ? 4 : Smeny.places(key, s.paramByKey);
    if (key.equals("palSum") || key.equals("palC3")) unit = "кг";
    Model.Param param = s.paramByKey.get(key);
    String title = name != null ? name : param == null ? key : param.name;
    Smeny.Change c = Smeny.change(x.v, y.v, unit, places);
    LinearLayout row = a.vertical();
    row.setPadding(0, a.dp(9), 0, a.dp(9));
    row.addView(a.label(title + (unit.isEmpty() ? "" : ", " + unit), 15, MainActivity.INK, true));
    row.addView(a.label(value(x, places, marks) + "  →  " + value(y, places, marks), 17, MainActivity.INK, false));
    row.addView(a.label(c.text, 14, c.direction > 0 ? UP : c.direction < 0 ? DOWN : MainActivity.MUTED, true));
    if (t != null) row.addView(a.label("на " + targetGrade + " было " + Smeny.num(t.v, places), 13, MainActivity.MUTED, false));
    divider(a, box);
    box.addView(row);
  }

  private static void line(MainActivity a, LinearLayout box, String key, String name, Sides sides, boolean marks, boolean target) {
    Smeny.Side x = sides.a.get(key), y = sides.b.get(key);
    if (x == null || y == null) return;
    Smeny.Side t = target && sides.target != null ? sides.target.get(key) : null;
    line(a, box, key, name, x, y, t, a.store, marks, sides.target == null ? "" : a.store.gradeName(sides.previous.grade));
  }

  static void zoneRow(MainActivity a, LinearLayout box, String title, String suffix, Sides sides, boolean marks) {
    Store s = a.store;
    StringBuilder text = new StringBuilder();
    for (int zone = 1; zone <= 3; zone++) {
      String key = "z" + zone + "_" + suffix;
      Smeny.Side x = sides.a.get(key), y = sides.b.get(key);
      if (x == null || y == null) continue;
      int places = Smeny.places(key, s.paramByKey);
      Smeny.Change c = Smeny.change(x.v, y.v, Smeny.unit(key, s.paramByKey), places);
      if (text.length() > 0) text.append('\n');
      text.append(zone).append("-я:  ").append(value(x, places, marks)).append("  →  ").append(value(y, places, marks))
        .append("  ").append(c.direction > 0 ? "▲" : c.direction < 0 ? "▼" : "=");
      Smeny.Side t = sides.target == null ? null : sides.target.get(key);
      if (t != null) text.append("   (было ").append(Smeny.num(t.v, places)).append(")");
    }
    if (text.length() == 0) return;
    String unit = Smeny.unit("z1_" + suffix, s.paramByKey);
    LinearLayout row = a.vertical();
    row.setPadding(0, a.dp(9), 0, a.dp(9));
    row.addView(a.label(title + (unit.isEmpty() ? "" : ", " + unit), 15, MainActivity.INK, true));
    row.addView(a.label(text.toString(), 16, MainActivity.INK, false));
    divider(a, box);
    box.addView(row);
  }

  static void sharesRow(MainActivity a, LinearLayout box, Sides sides, String from, String to) {
    if (!sides.a.containsKey("share1") || !sides.b.containsKey("share1")) return;
    LinearLayout row = a.vertical();
    row.setPadding(0, a.dp(9), 0, a.dp(9));
    row.addView(a.label("Как инициатор делится по зонам, % от суммы", 15, MainActivity.INK, true));
    row.addView(a.label(Smeny.shares(sides.a, sides.b), 14, MainActivity.MUTED, false));
    bar(a, row, from, sides.a);
    bar(a, row, to, sides.b);
    row.addView(a.label("■ 1-я зона   ■ 2-я   ■ 3-я — оттенки от светлого к тёмному", 12, MainActivity.MUTED, false));
    divider(a, box);
    box.addView(row);
  }

  private static void bar(MainActivity a, LinearLayout parent, String who, Map<String, Smeny.Side> side) {
    LinearLayout line = new LinearLayout(a);
    line.setOrientation(LinearLayout.HORIZONTAL);
    line.setGravity(Gravity.CENTER_VERTICAL);
    TextView name = a.label(who, 13, MainActivity.INK, true);
    line.addView(name, new LinearLayout.LayoutParams(a.dp(86), -2));
    for (int zone = 1; zone <= 3; zone++) {
      double share = side.get("share" + zone).v;
      TextView part = a.label(share >= 10 ? Smeny.num(share, 0) + " %" : "", 12, zone == 1 ? Color.rgb(13, 54, 107) : Color.WHITE, true);
      part.setGravity(Gravity.CENTER);
      part.setBackgroundColor(ZONES[zone - 1]);
      part.setContentDescription("Зона " + zone + ": " + Smeny.num(share, 1) + " %");
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, a.dp(26), (float) share);
      params.rightMargin = a.dp(2);
      line.addView(part, params);
    }
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = a.dp(6);
    parent.addView(line, params);
  }

  static void divider(MainActivity a, LinearLayout box) {
    if (box.getChildCount() == 0) return;
    android.view.View line = new android.view.View(a);
    line.setBackgroundColor(MainActivity.BORDER);
    box.addView(line, new LinearLayout.LayoutParams(-1, 1));
  }

  // ---------------------------------------------------------------- path and norms

  static final String[][] PATH = {{"pal", "ПАЛ"}, {"mfr", "ПТР"}, {"mfr_raw", "ПТР исх."}, {"mfr_lab", "ПТР по журналу"},
    {"z1_sp", "Т 1-й"}, {"z2_sp", "Т 2-й"}, {"z3_sp", "Т 3-й"}, {"c3_flow", "пропилен"}, {"drive_current", "нагрузка"}};

  private static void path(MainActivity a, Smeny.Pair p) {
    if (p.mid.isEmpty() && p.marked == null) return;
    Store s = a.store;
    a.section("Ход перехода · " + MainActivity.count(p.mid.size(), "точка", "точки", "точек"));
    if (p.marked != null) a.card(Smeny.day(p.marked.start, false), "Начало перехода", null);
    for (Model.Obs obs : p.mid) {
      List<String> parts = new ArrayList<>();
      Map<String, Smeny.Side> side = Smeny.side(java.util.Collections.singletonList(obs), false, false, false, s);
      for (String[] item : PATH) {
        Smeny.Side v = side.get(item[0]);
        if (v != null) parts.add(item[1] + " " + Smeny.num(v.v, Smeny.places(item[0], s.paramByKey)));
      }
      a.card(Smeny.day(obs.time, false), parts.isEmpty() ? "Цифр из списка нет — откройте точку" : String.join(" · ", parts),
        () -> a.go(() -> ObsScreens.show(a, obs.id)));
    }
    if (p.marked != null && p.marked.end != null) a.card(Smeny.day(p.marked.end, false), "Режим устоявшийся, переход завершён", null);
  }

  private static void norms(MainActivity a, Smeny.Pair p, String from, String to) {
    Store s = a.store;
    Model.Doc before = docFor(s, p.from.grade, p.from.last().time), after = docFor(s, p.toGrade, p.lastSeen().time);
    if (before == null && after == null) return;
    a.section("Нормы техкарт");
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[]{"Параметр", from + (before == null ? "" : " · " + before.version), to + (after == null ? "" : " · " + after.version)});
    List<String> keys = new ArrayList<>();
    for (Model.Norm norm : s.norms) {
      boolean ours = before != null && before.id.equals(norm.doc) || after != null && after.id.equals(norm.doc);
      if (ours && !keys.contains(norm.param)) keys.add(norm.param);
    }
    for (String key : keys) {
      Model.Param param = s.paramByKey.get(key);
      rows.add(new String[]{(param == null ? key : param.name) + (param == null || param.unit.isEmpty() ? "" : ", " + param.unit),
        range(s, before, key), range(s, after, key)});
    }
    a.table(rows, null, null);
  }

  static Model.Doc docFor(Store s, String grade, String time) {
    String day = time == null ? null : time.substring(0, 10);
    Model.Doc newest = null;
    for (Model.Doc doc : s.docsOf(grade)) {
      if (newest == null) newest = doc;
      if (day != null && doc.validFrom != null && doc.validFrom.compareTo(day) <= 0 && (doc.validTo == null || day.compareTo(doc.validTo) <= 0)) return doc;
    }
    return newest;
  }

  static Model.Norm norm(Store s, Model.Doc doc, String key) {
    if (doc == null) return null;
    for (Model.Norm norm : s.norms) if (doc.id.equals(norm.doc) && key.equals(norm.param)) return norm;
    return null;
  }

  static String range(Store s, Model.Doc doc, String key) {
    Model.Norm n = norm(s, doc, key);
    if (n == null) return "—";
    if (n.lower != null && n.upper != null) return Smeny.num(n.lower, 3) + "–" + Smeny.num(n.upper, 3) + (n.target == null ? "" : " (цель " + Smeny.num(n.target, 3) + ")");
    if (n.lower != null) return "не менее " + Smeny.num(n.lower, 3);
    if (n.upper != null) return "не более " + Smeny.num(n.upper, 3);
    return n.target != null ? "цель " + Smeny.num(n.target, 3) : "без границ";
  }

  // ---------------------------------------------------------------- grade regime

  static final String[] MODE_KEYS = {"pal", "pal_sp", "z1_f", "z2_f", "z3_f", "sum", "z1_sp", "z2_sp", "z3_sp", "z1_t", "z2_t", "z3_t",
    "mfr", "mfr_raw", "mfr_lab", "c3_flow", "propylene", "drive_current", "rpm", "vgvd", "fnd"};

  /** What a grade ran last time, its usual range over all its observations, and the norms of its current tech card. */
  static void mode(MainActivity a, String gradeId) {
    Store s = a.store;
    String name = s.gradeName(gradeId);
    List<Smeny.Run> runs = new ArrayList<>();
    for (Smeny.Run run : Smeny.runs(s.observations)) if (run.grade.equals(gradeId)) runs.add(run);
    runs.sort((x, y) -> x.last().time.compareTo(y.last().time));
    a.frame("Режим марки " + name, runs.isEmpty() ? "замеров пока нет" : "последний режим и обычные значения", "grades");
    if (runs.isEmpty()) {
      a.note("Замеров этой марки с известным временем и линией пока нет.");
      return;
    }
    Smeny.Run last = runs.get(runs.size() - 1);
    Map<String, Smeny.Side> latest = Smeny.side(last.items, true, false, false, s);
    List<Model.Obs> all = new ArrayList<>();
    for (Smeny.Run run : runs) all.addAll(run.items);
    Model.Doc doc = docFor(s, gradeId, last.last().time);
    a.note("Последний режим: линия " + last.line + ", снимки " + (last.items.size() == 1 ? Smeny.day(last.first().time, true)
      : "с " + Smeny.day(last.first().time, true) + " по " + Smeny.day(last.last().time, true)) + ". Для каждой цифры взят самый поздний снимок, где она есть.\n"
      + "«Обычно» — разброс по " + MainActivity.observations(all.size()) + " марки без точек перехода."
      + (doc == null ? "" : " Нормы — по техкарте " + doc.version + "."));
    LinearLayout box = box(a);
    for (String key : MODE_KEYS) {
      Smeny.Side now = latest.get(key);
      double[] range = range(all, key, s);
      if (now == null && range == null) continue;
      int places = Smeny.places(key, s.paramByKey);
      String unit = Smeny.unit(key, s.paramByKey);
      Model.Param param = s.paramByKey.get(key);
      String title = key.equals("sum") ? "Инициатор, всего" : param == null ? key : param.name;
      LinearLayout row = a.vertical();
      row.setPadding(0, a.dp(9), 0, a.dp(9));
      row.addView(a.label(title + (unit.isEmpty() ? "" : ", " + unit), 15, MainActivity.INK, true));
      Model.Norm n = norm(s, doc, key);
      boolean outside = now != null && n != null && (n.lower != null && now.v < n.lower || n.upper != null && now.v > n.upper);
      row.addView(a.label(now == null ? "в последнем режиме не снималось" : "последний: " + Smeny.num(now.v, places) + (now.mixed ? " (смесь растворов)" : ""),
        17, outside ? MainActivity.REJECTED : MainActivity.INK, now != null));
      if (range != null) {
        row.addView(a.label("обычно: " + (range[0] == range[1] ? Smeny.num(range[0], places) : Smeny.num(range[0], places) + " – " + Smeny.num(range[1], places))
          + " · " + MainActivity.observations((int) range[2]), 13, MainActivity.MUTED, false));
      }
      if (n != null) row.addView(a.label("норма: " + range(s, doc, key) + (outside ? " — последний вне нормы" : ""), 13, outside ? MainActivity.REJECTED : MainActivity.MUTED, outside));
      divider(a, box);
      box.addView(row);
    }
    String shares = latest.containsKey("share1") ? "Доли инициатора по зонам в последнем режиме: " + Smeny.num(latest.get("share1").v, 0) + " / "
      + Smeny.num(latest.get("share2").v, 0) + " / " + Smeny.num(latest.get("share3").v, 0) + " %." : null;
    if (shares != null) a.note(shares);

    List<Smeny.Pair> pairs = Smeny.pairs(s.observations, s.transitions, s);
    a.section("Смены на эту марку и с неё");
    int found = 0;
    for (int i = pairs.size() - 1; i >= 0; i--) {
      Smeny.Pair p = pairs.get(i);
      if (!gradeId.equals(p.from.grade) && !gradeId.equals(p.toGrade)) continue;
      found++;
      feedCard(a, p, i);
    }
    if (found == 0) a.note("Смен с этой маркой в базе пока нет.");
    a.buttons("Прошлые режимы", () -> a.go(() -> RegimeScreens.history(a, gradeId)), "Сравнить с нынешним", () -> {
      a.prefs().edit().putString("plan_to", gradeId).putString("plan_target", "last").apply();
      a.go(() -> RegimeScreens.plan(a));
    });
    a.buttons("Все замеры марки", () -> a.go(() -> ArchiveScreens.observations(a, gradeId)), "Техкарты марки", () -> a.go(() -> ArchiveScreens.docs(a, gradeId)));
  }

  /** {min, max, observations} of a parameter over a grade's observations; mixed-solution initiator flows left out. */
  static double[] range(List<Model.Obs> all, String key, Analysis.Data data) {
    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
    int n = 0;
    for (Model.Obs obs : all) {
      Smeny.Side v = Smeny.side(java.util.Collections.singletonList(obs), false, false, false, data).get(key);
      if (v == null || v.mixed) continue;
      min = Math.min(min, v.v);
      max = Math.max(max, v.v);
      n++;
    }
    return n == 0 ? null : new double[]{min, max, n};
  }
}
