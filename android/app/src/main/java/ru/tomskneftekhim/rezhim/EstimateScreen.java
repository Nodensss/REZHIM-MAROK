package ru.tomskneftekhim.rezhim;

import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * «Оценка сейчас» (ТЗ, разделы 3 и 6, этапы А–Б): current readings, the target grade and the
 * confirmed episodes of that pair. Either «в похожих подтверждённых случаях наблюдали…» with the
 * cases it rests on, or a plain reason why no number is given. Never a command to the equipment.
 */
final class EstimateScreen {
  private EstimateScreen() {}

  static final String[][] MANUAL = {
    {"pal", "ПАЛ, кг/ч", Calc.KG_H}, {"c3_flow", "Пропилен, м³/ч", Calc.M3_H}, {"z1_f", "Инициатор зона 1, кг/ч", Calc.KG_H},
    {"z2_f", "Инициатор зона 2, кг/ч", Calc.KG_H}, {"z3_f", "Инициатор зона 3, кг/ч", Calc.KG_H}, {"production", "Фактический выпуск Q, т/ч", Calc.T_H}};

  static final class State {
    int line = 1, from, to, source;
    String obsId;
    final Map<String, String> manual = new LinkedHashMap<>();
    boolean calculated;
  }

  /** The form state lives across visits to the observation list and back. */
  static void open(MainActivity a) {
    State state = new State();
    a.go(() -> show(a, state));
  }

  static void show(MainActivity a, State state) {
    Store s = a.store;
    a.frame("Оценка сейчас", "Справка по подтверждённым случаям", "home");
    a.warn("Это справочная подсказка оператору и технологу, а не команда оборудованию. Приложение не управляет СУТП и не меняет "
      + "уставки. Изменение режима выполняется человеком по действующим производственным процедурам.");
    String[] names = new String[s.grades.size() + 1];
    names[0] = "не выбрана";
    for (int i = 0; i < s.grades.size(); i++) names[i + 1] = s.grades.get(i).name;
    Spinner line = a.select("Линия", new String[]{"A", "B"}, state.line - 1);
    Spinner from = a.select("Текущая марка", names, state.from);
    Spinner to = a.select("Целевая марка", names, state.to);
    Spinner source = a.select("Текущие значения", new String[]{"Из замера (фото или ручной ввод)", "Ввести сейчас вручную"}, state.source);
    List<EditText> fields = new ArrayList<>();
    int shownSource = state.source;
    Runnable keep = () -> {
      state.line = line.getSelectedItemPosition() + 1;
      state.from = from.getSelectedItemPosition();
      state.to = to.getSelectedItemPosition();
      state.source = source.getSelectedItemPosition();
      for (int i = 0; i < fields.size(); i++) state.manual.put(MANUAL[i][0], fields.get(i).getText().toString());
    };
    source.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
        if (position == shownSource) return;
        keep.run();
        a.refresh();
      }
      @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    });
    if (state.source == 0) {
      Model.Obs obs = state.obsId == null ? null : s.obsById.get(state.obsId);
      a.buttons(obs == null ? "Выбрать замер" : "Замер: " + MainActivity.date(obs.time) + " · " + s.gradeName(obs.grade), () -> {
        keep.run();
        a.go(() -> pickObs(a, state));
      }, "Добавить фото", () -> {
        keep.run();
        a.go(() -> PhotoScreens.add(a));
      });
    } else {
      a.note("Значения, введённые здесь, считаются показаниями оператора на текущий момент и в архив не сохраняются.");
      for (String[] field : MANUAL) fields.add(a.numberInput(field[1], state.manual.getOrDefault(field[0], "")));
    }
    a.buttons("Рассчитать", () -> {
      keep.run();
      state.calculated = true;
      a.refresh();
    }, null, null);
    if (state.calculated) result(a, state);
  }

  private static void pickObs(MainActivity a, State state) {
    Store s = a.store;
    a.frame("Текущий замер", "Выберите замер с текущими значениями", "home");
    for (Model.Obs obs : s.observationsOf(null)) {
      a.card(a.obsTitle(obs), a.obsLine(obs), () -> {
        state.obsId = obs.id;
        if (obs.grade != null) state.from = s.grades.indexOf(s.gradeById.get(obs.grade)) + 1;
        if ("B".equals(obs.line)) state.line = 2; else if ("A".equals(obs.line)) state.line = 1;
        a.back();
      });
    }
  }

  private static void result(MainActivity a, State state) {
    Store s = a.store;
    a.section("Результат");
    if (state.from == 0 || state.to == 0) { a.warn("Выберите текущую и целевую марки."); return; }
    Model.Grade fromGrade = s.grades.get(state.from - 1), toGrade = s.grades.get(state.to - 1);
    if (fromGrade.id.equals(toGrade.id)) { a.warn("Текущая и целевая марки совпадают — перехода нет."); return; }
    Analysis.Request request = new Analysis.Request();
    request.line = state.line == 2 ? "B" : "A";
    request.from = fromGrade.id;
    request.to = toGrade.id;
    List<String> currentNotes = new ArrayList<>();
    if (state.source == 0) {
      Model.Obs obs = state.obsId == null ? null : s.obsById.get(state.obsId);
      if (obs == null) { a.warn("Выберите замер с текущими значениями или введите их вручную."); return; }
      if (obs.excluded || s.disputed.contains(obs.id)) currentNotes.add("Выбранный замер спорный или исключён — подсказка по нему подавляется.");
      if (obs.line != null && !obs.line.equals(request.line)) currentNotes.add("Замер относится к линии " + obs.line + ", а выбрана линия " + request.line + ".");
      if (obs.grade == null || !obs.grade.equals(fromGrade.id)) currentNotes.add("Марка замера («" + s.gradeName(obs.grade) + "») не совпадает с выбранной текущей.");
      boolean unusable = !currentNotes.isEmpty();
      for (Model.Val value : s.values(obs.id)) {
        if (value.extra || !value.active() || value.param == null) continue;
        Calc.Input input = s.input(obs.id, value.param);
        if (input == null) continue;
        request.current.put(value.param, unusable ? new Calc.Input(input.number, input.unit, input.type, false, input.label) : input);
      }
      request.recipe12 = obs.recipe12;
      request.recipe3 = obs.recipe3;
    } else {
      for (String[] field : MANUAL) {
        Double number = Calc.parse(state.manual.getOrDefault(field[0], ""));
        if (number != null) request.current.put(field[0], new Calc.Input(number, field[2], Model.FACT, true, field[1]));
      }
      if (request.current.isEmpty()) { a.warn("Введите хотя бы одно текущее значение."); return; }
    }
    Model.Doc targetDoc = s.docOn(toGrade.id, null);
    request.targetNorms.putAll(s.normsOf(targetDoc));
    for (String note : currentNotes) a.warn(note);

    Analysis.Estimate estimate = Analysis.estimate(request, s.transitions, s);
    for (String note : estimate.notes) a.note(note);
    if (estimate.refusal != null) {
      a.warn("Расчёт невозможен: " + estimate.refusal);
      int candidates = 0;
      for (Analysis.Candidate candidate : Analysis.candidates(s.observations, s.disputed)) {
        if (fromGrade.id.equals(candidate.before.grade) && toGrade.id.equals(candidate.after.grade)) candidates++;
      }
      if (estimate.episodes.isEmpty()) {
        a.note("Подтверждённых переходов " + fromGrade.name + " → " + toGrade.name + " нет. Кандидатов из хронологии этой пары: " + candidates
          + ". Разметьте переход вручную по имеющимся фото — после подтверждения он станет аналогом.");
        a.buttons("Открыть переходы", () -> a.home(() -> TransitionScreens.list(a)), null, null);
      }
    }
    if (!estimate.episodes.isEmpty()) {
      a.section("Подтверждённые эпизоды этой пары");
      for (Analysis.Episode episode : estimate.episodes) episodeCard(a, episode);
    }
    if (estimate.refusal == null) {
      int cases = estimate.episodes.size();
      for (Analysis.Episode episode : estimate.episodes) if (episode.excluded != null) cases--;
      a.section("В похожих подтверждённых случаях наблюдали");
      a.note("Сопоставимых случаев: " + cases + ". Δ — изменение между устойчивым уровнем до и после перехода в каждом случае; "
        + "«ожидаемо» = текущее значение + диапазон Δ. Нормы техкарты целевой марки"
        + (targetDoc == null ? " не найдены" : " " + targetDoc.version) + " используются как ограничения.");
      List<String[]> rows = new ArrayList<>();
      rows.add(new String[]{"Параметр", "n", "Медиана Δ", "Диапазон Δ", "Сейчас", "Ожидаемо", "Подсказка"});
      for (Analysis.ParamEstimate item : estimate.params) {
        Model.Param param = s.paramByKey.get(item.param);
        String name = (param == null ? item.param : param.name) + (item.unit == null || item.unit.isEmpty() ? "" : ", " + item.unit);
        rows.add(new String[]{name, String.valueOf(item.count), Calc.signed(item.median, 2),
          Calc.signed(item.min, 2) + "…" + Calc.signed(item.max, 2), Calc.fmt(item.current, 0),
          item.low == null ? "—" : Calc.fixed(item.low, 2) + "…" + Calc.fixed(item.high, 2),
          item.suppressed == null ? (item.current == null ? "только Δ" : "есть") : "нет: " + item.suppressed});
      }
      a.table(rows, null, null);
    }
  }

  private static void episodeCard(MainActivity a, Analysis.Episode episode) {
    Store s = a.store;
    Model.Transition t = episode.transition;
    StringBuilder text = new StringBuilder();
    text.append("Линия ").append(t.line).append(" · ").append(MainActivity.date(t.start)).append(" → ").append(MainActivity.date(t.end));
    for (String key : new String[]{"pal", "c3_flow", "z1_f", "z2_f", "z3_f", "production"}) {
      Analysis.Level before = episode.before.get(key), after = episode.after.get(key);
      if (before == null && after == null) continue;
      Model.Param param = s.paramByKey.get(key);
      text.append("\n").append(param == null ? key : param.name).append(": ")
        .append(before == null ? "—" : Calc.fmt(before.value, 0)).append(" → ").append(after == null ? "—" : Calc.fmt(after.value, 0));
      if (before != null && after != null) {
        int places = Math.max(Calc.decimals(before.value), Calc.decimals(after.value));
        text.append(" (Δ ").append(Calc.signed(after.value - before.value, places)).append(")");
      }
    }
    for (String note : episode.notes) text.append("\n• ").append(note);
    LinearLayout card = a.card(s.gradeName(t.from) + " → " + s.gradeName(t.to), text.toString(),
      () -> TransitionScreens.open(a, TransitionScreens.draftOf(t)));
    if (episode.excluded != null) a.badge(card, "Не сопоставим: " + episode.excluded, MainActivity.REVIEW);
  }
}
