package ru.tomskneftekhim.rezhim;

import android.widget.LinearLayout;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Two chosen observations side by side (ТЗ, раздел 5): values with units, absolute and relative
 * differences, initiator shares and ratios. Without a confirmed transition linking them the screen
 * is «Сравнение снимков» and says that the difference is not a transition or a setpoint.
 */
final class CompareScreen {
  private CompareScreen() {}

  static final String[] TYPES = {Model.FACT, Model.SETPOINT, Model.OUTPUT, Model.CALC, Model.LAB};

  /** Key proportions of one observation. */
  static final class Proportions {
    Calc.Input pal, c3, z1, z2, z3, q, sumInput;
    Calc.Result sum, s1, s2, s3, palSum, palC3, palQ, sumQ, c3Q;

    boolean unverified() {
      for (Calc.Input input : new Calc.Input[]{pal, c3, z1, z2, z3, q}) {
        if (input != null && input.number != null && !input.confirmed) return true;
      }
      return false;
    }
  }

  static Proportions proportions(Store s, String obsId) {
    Proportions p = new Proportions();
    p.pal = s.input(obsId, "pal");
    p.c3 = s.input(obsId, "c3_flow");
    p.z1 = s.input(obsId, "z1_f");
    p.z2 = s.input(obsId, "z2_f");
    p.z3 = s.input(obsId, "z3_f");
    p.q = s.input(obsId, "production");
    if (p.q == null) p.q = s.input(obsId, "prod");
    p.sum = Calc.initiatorSum(p.z1, p.z2, p.z3);
    p.s1 = Calc.share(p.z1, p.sum);
    p.s2 = Calc.share(p.z2, p.sum);
    p.s3 = Calc.share(p.z3, p.sum);
    p.palSum = Calc.palToInitiators(p.pal, p.sum);
    p.palC3 = Calc.palPerPropylene(p.pal, p.c3);
    p.palQ = Calc.specific(p.pal, p.q);
    p.sumInput = p.sum.ok() ? new Calc.Input(p.sum.value, p.sum.unit, Model.FACT, !p.sum.unverified, "FΣ") : null;
    p.sumQ = Calc.specific(p.sumInput, p.q);
    p.c3Q = Calc.propylenePerOutput(p.c3, p.q);
    return p;
  }

  static Model.Transition linking(Store s, String a, String b) {
    for (Model.Transition transition : s.transitions) {
      if (transition.confirmed() && transition.before.contains(a) && transition.after.contains(b)) return transition;
    }
    return null;
  }

  static void show(MainActivity a) {
    Store s = a.store;
    Model.Obs left = slot(a, "a"), right = slot(a, "b");
    Model.Transition link = left == null || right == null ? null : linking(s, left.id, right.id);
    String title = link != null ? "До и после перехода" : "Сравнение снимков";
    a.frame(title, left == null || right == null ? "Выберите два замера" : s.gradeName(left.grade) + " → " + s.gradeName(right.grade), "compare");
    slotCard(a, "А", left);
    slotCard(a, "Б", right);
    a.buttons("Выбрать А", () -> a.go(() -> pick(a, "a", null)), "Выбрать Б", () -> a.go(() -> pick(a, "b", null)));
    if (left != null && right != null) {
      a.buttons("Поменять местами", () -> {
        a.setCompareSlot("a", right.id);
        a.setCompareSlot("b", left.id);
        a.refresh();
      }, null, null);
    }
    if (left == null || right == null) {
      a.note("Выберите замер А и замер Б. Можно сравнивать снимки одной марки или разных марок. "
        + "Непроверенные значения участвуют в предварительном сравнении с явной меткой.");
      return;
    }
    if (link == null) {
      a.warn("Это разница между двумя отдельными снимками. Подтверждённого перехода между ними нет, "
        + "поэтому разница не является ни технологическим переходом, ни рекомендуемой уставкой.");
    } else {
      a.note("Снимки входят в подтверждённый переход " + s.gradeName(link.from) + " → " + s.gradeName(link.to)
        + ": А — устойчивый режим до, Б — после.");
    }
    List<String> context = new ArrayList<>();
    if (Analysis.timeKnown(left) && Analysis.timeKnown(right)) {
      context.add("Между снимками " + Calc.fixed(Math.abs(Analysis.hoursBetween(left.time, right.time)), 1) + " ч; время А "
        + MainActivity.timeOrigin(left.timeOrigin) + ", Б " + MainActivity.timeOrigin(right.timeOrigin) + ".");
    } else {
      context.add("Время одного из снимков неизвестно.");
    }
    if (left.line != null && !left.line.equals(right.line)) context.add("Разные линии: " + left.line + " и " + right.line + ".");
    for (Model.Obs obs : new Model.Obs[]{left, right}) {
      if (obs.excluded) context.add("Замер " + date(obs) + " исключён из расчётов: " + obs.excludedReason);
      if (s.disputed.contains(obs.id)) context.add("Замер " + date(obs) + " — возможный дубль или спорная марка.");
    }
    Proportions pa = proportions(s, left.id), pb = proportions(s, right.id);
    boolean unverified = pa.unverified() || pb.unverified();
    if (unverified) context.add("Часть чисел не подтверждена: сравнение предварительное, «по непроверенным данным».");
    a.warn(android.text.TextUtils.join("\n", context));

    a.section("Пропорции");
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[]{"Показатель", "А", "Б", "Δ", "%Δ"});
    List<String> refused = new ArrayList<>();
    inputRow(rows, "ПАЛ, кг/ч", pa.pal, pb.pal);
    inputRow(rows, "Инициатор зона 1, кг/ч", pa.z1, pb.z1);
    inputRow(rows, "Инициатор зона 2, кг/ч", pa.z2, pb.z2);
    inputRow(rows, "Инициатор зона 3, кг/ч", pa.z3, pb.z3);
    resultRow(rows, refused, "FΣ, кг/ч", pa.sum, pb.sum, 2, false);
    resultRow(rows, refused, "Доля зоны 1, %", pa.s1, pb.s1, 1, true);
    resultRow(rows, refused, "Доля зоны 2, %", pa.s2, pb.s2, 1, true);
    resultRow(rows, refused, "Доля зоны 3, %", pa.s3, pb.s3, 1, true);
    resultRow(rows, refused, "ПАЛ / FΣ", pa.palSum, pb.palSum, 4, false);
    inputRow(rows, "Пропилен C3, м³/ч", pa.c3, pb.c3);
    resultRow(rows, refused, "ПАЛ / пропилен, кг/м³", pa.palC3, pb.palC3, 4, false);
    inputRow(rows, "Выпуск Q, т/ч", pa.q, pb.q);
    resultRow(rows, refused, "ПАЛ / Q, кг/т", pa.palQ, pb.palQ, 3, false);
    resultRow(rows, refused, "FΣ / Q, кг/т", pa.sumQ, pb.sumQ, 3, false);
    resultRow(rows, refused, "Пропилен / Q, м³/т", pa.c3Q, pb.c3Q, 3, false);
    a.table(rows, null, null);
    a.note("ПАЛ / FΣ — отношение наблюдаемых расходов, а не рецепт продукта. ПАЛ / пропилен при ПАЛ в кг/ч и пропилене в м³/ч "
      + "даёт кг/м³, это не массовая доля. Для доли зон Δ — в процентных пунктах. «?» — непроверенное число.");
    if (!refused.isEmpty()) a.note("Не рассчитано:\n" + android.text.TextUtils.join("\n", refused));

    for (String type : TYPES) {
      List<String[]> table = new ArrayList<>();
      table.add(new String[]{MainActivity.type(type), "А", "Б", "Δ", "%Δ"});
      for (String key : params(s, type, left.id, right.id)) table.add(paramRow(a, key, left.id, right.id));
      if (table.size() > 1) {
        a.section(MainActivity.type(type));
        a.table(table, null, null);
      }
    }
    a.note("«?» — требует сверки, «!» — в замере два разных числа этого параметра (разница не считается), "
      + "«ед.?» — единица не установлена, разница не считается. Отклонённые числа в сравнение не входят.");
    a.buttons("Открыть замер А", () -> a.go(() -> ObsScreens.show(a, left.id)), "Открыть замер Б", () -> a.go(() -> ObsScreens.show(a, right.id)));
  }

  private static String date(Model.Obs obs) { return MainActivity.date(obs.time); }

  private static Model.Obs slot(MainActivity a, String slot) {
    String id = a.compareSlot(slot);
    return id == null ? null : a.store.obsById.get(id);
  }

  private static void slotCard(MainActivity a, String name, Model.Obs obs) {
    if (obs == null) {
      a.card(name + ": не выбран", "Нажмите «Выбрать " + name + "».", null);
      return;
    }
    LinearLayout card = a.card(name + ": " + a.obsTitle(obs), a.obsLine(obs), () -> a.go(() -> ObsScreens.show(a, obs.id)));
    if (obs.excluded || a.store.disputed.contains(obs.id)) a.badge(card, "Спорный замер", MainActivity.REJECTED);
  }

  private static void inputRow(List<String[]> rows, String name, Calc.Input left, Calc.Input right) {
    Double l = left == null ? null : left.number, r = right == null ? null : right.number;
    int places = Math.max(l == null ? 0 : Calc.decimals(l), r == null ? 0 : Calc.decimals(r));
    rows.add(new String[]{name, ArchiveScreens.input(left), ArchiveScreens.input(right),
      Calc.signed(Calc.delta(l, r), places), Calc.signed(Calc.percent(l, r), 1)});
  }

  private static void resultRow(List<String[]> rows, List<String> refused, String name, Calc.Result left, Calc.Result right,
                                int places, boolean points) {
    Double l = left.ok() ? left.value : null, r = right.ok() ? right.value : null;
    String leftText = places == 4 ? ArchiveScreens.cellSignificant(left) : ArchiveScreens.cell(left, places);
    String rightText = places == 4 ? ArchiveScreens.cellSignificant(right) : ArchiveScreens.cell(right, places);
    String delta = places == 4 ? (Calc.delta(l, r) == null ? "—" : (Calc.delta(l, r) > 0 ? "+" : "") + Calc.significant(Calc.delta(l, r), 3))
      : Calc.signed(Calc.delta(l, r), places) + (points && l != null && r != null ? " п.п." : "");
    rows.add(new String[]{name, leftText, rightText, delta, points ? "" : Calc.signed(Calc.percent(l, r), 1)});
    if (!left.ok() && !right.ok() && left.reason != null && left.reason.equals(right.reason)) {
      refused.add(name + ": " + left.reason);
    } else {
      if (!left.ok()) refused.add(name + ", А: " + left.reason);
      if (!right.ok()) refused.add(name + ", Б: " + right.reason);
    }
  }

  private static Set<String> params(Store s, String type, String left, String right) {
    Set<String> keys = new TreeSet<>((x, y) -> {
      Model.Param px = s.paramByKey.get(x), py = s.paramByKey.get(y);
      int compare = (px == null ? "" : px.group).compareTo(py == null ? "" : py.group);
      return compare != 0 ? compare : (px == null ? x : px.name).compareTo(py == null ? y : py.name);
    });
    for (String id : new String[]{left, right}) {
      for (Model.Val value : s.values(id)) {
        if (value.extra || !value.active() || value.param == null) continue;
        if (type.equals(value.type)) keys.add(value.param);
      }
    }
    return keys;
  }

  private static String[] paramRow(MainActivity a, String key, String left, String right) {
    Store s = a.store;
    Model.Param param = s.paramByKey.get(key);
    String unit = param == null || param.unit.isEmpty() ? "ед.?" : param.unit;
    Cell l = cell(s, left, key), r = cell(s, right, key);
    String delta = "—", percent = "—";
    if (l.number != null && r.number != null) {
      if (param == null || param.unit.isEmpty()) {
        delta = "ед.?";
        percent = "";
      } else {
        int places = Math.max(Calc.decimals(l.number), Calc.decimals(r.number));
        delta = Calc.signed(r.number - l.number, places);
        percent = Calc.signed(Calc.percent(l.number, r.number), 1);
      }
    }
    return new String[]{(param == null ? key : param.name) + ", " + unit, l.text, r.text, delta, percent};
  }

  private static final class Cell {
    Double number;
    String text = "—";
  }

  private static Cell cell(Store s, String obsId, String key) {
    Cell cell = new Cell();
    if (s.conflicts(obsId).contains(key)) {
      cell.text = "!";
      return cell;
    }
    Model.Val value = s.single(obsId, key);
    if (value == null) return cell;
    if (!value.hasNumber()) {
      cell.text = "missing".equals(value.state) ? "пропуск" : "ошибка";
      return cell;
    }
    Model.Param param = s.paramByKey.get(key);
    cell.number = value.number;
    cell.text = Calc.fmt(value.number, param == null ? 0 : param.precision) + (value.confirmed() ? "" : "?");
    return cell;
  }

  /** Chooses the observation for slot А or Б. */
  static void pick(MainActivity a, String slot, String gradeFilter) {
    Store s = a.store;
    a.frame("Замер " + ("a".equals(slot) ? "А" : "Б"), "Выберите снимок для сравнения", "compare");
    List<String> labels = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    labels.add("Все марки");
    ids.add(null);
    int selected = 0;
    for (Model.Grade grade : s.grades) {
      labels.add(grade.name);
      ids.add(grade.id);
      if (grade.id.equals(gradeFilter)) selected = ids.size() - 1;
    }
    Spinner filter = a.select("Марка", labels.toArray(new String[0]), selected);
    int initial = selected;
    filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
        if (position != initial) a.home(() -> pick(a, slot, ids.get(position)));
      }
      @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    });
    Map<String, List<Model.Obs>> byLine = new LinkedHashMap<>();
    for (Model.Obs obs : s.observationsOf(gradeFilter)) {
      String line = obs.line == null ? "Линия неизвестна" : "Линия " + obs.line;
      byLine.computeIfAbsent(line, k -> new ArrayList<>()).add(obs);
    }
    for (Map.Entry<String, List<Model.Obs>> entry : byLine.entrySet()) {
      a.section(entry.getKey());
      for (Model.Obs obs : entry.getValue()) {
        a.card(a.obsTitle(obs), a.obsLine(obs), () -> {
          a.setCompareSlot(slot, obs.id);
          a.home(() -> show(a));
        });
      }
    }
  }
}
