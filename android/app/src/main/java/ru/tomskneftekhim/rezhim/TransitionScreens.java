package ru.tomskneftekhim.rezhim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transitions between grades (ТЗ, раздел 3, сценарий 3). Neighbouring photos of different grades are
 * shown only as candidates; an episode becomes confirmed when the operator marks the real start and end
 * of the change and the steady observations before and after it.
 */
final class TransitionScreens {
  private TransitionScreens() {}

  static void list(MainActivity a) {
    Store s = a.store;
    List<Model.Transition> confirmed = new ArrayList<>(), incomplete = new ArrayList<>();
    for (Model.Transition transition : s.transitions) (transition.confirmed() ? confirmed : incomplete).add(transition);
    a.frame("Переходы", "подтверждённых " + confirmed.size() + " · неполных " + incomplete.size(), "transitions");
    a.note("Соседние по времени фото разных марок — только кандидаты: время EXIF не равно моменту смены марки, между снимками могли "
      + "быть другие режимы. Подтверждённый эпизод размечается вручную: начало и окончание смены, устойчивые замеры до и после, "
      + "промежуточные снимки и лабораторная проба.");
    a.buttons("Новый переход вручную", () -> open(a, blank(null)), null, null);
    a.section("Подтверждённые переходы");
    if (confirmed.isEmpty()) a.note("Подтверждённых переходов пока нет.");
    for (Model.Transition transition : confirmed) transitionCard(a, transition);
    if (!incomplete.isEmpty()) {
      a.section("Неполные эпизоды");
      a.note("Неполные эпизоды хранятся, но не используются для подсказки.");
      for (Model.Transition transition : incomplete) transitionCard(a, transition);
    }
    a.section("Кандидаты из хронологии");
    List<Analysis.Candidate> candidates = Analysis.candidates(s.observations, s.disputed);
    if (candidates.isEmpty()) a.note("В хронологии нет соседних снимков разных марок на одной линии.");
    for (Analysis.Candidate candidate : candidates) {
      LinearLayout card = a.card(s.gradeName(candidate.before.grade) + " → " + s.gradeName(candidate.after.grade) + " · линия " + candidate.before.line,
        MainActivity.date(candidate.before.time) + " → " + MainActivity.date(candidate.after.time) + " · разрыв "
          + Calc.fixed(candidate.gapHours, 1) + " ч\n⚠ " + android.text.TextUtils.join("\n⚠ ", candidate.warnings), null);
      a.badge(card, "Кандидат, не подтверждённый переход", MainActivity.REVIEW);
      LinearLayout row = new LinearLayout(a);
      row.addView(a.button("Сравнить снимки", () -> {
        a.setCompareSlot("a", candidate.before.id);
        a.setCompareSlot("b", candidate.after.id);
        a.home(() -> CompareScreen.show(a));
      }), new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(a.button("Разметить", () -> open(a, blank(candidate))), new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(row);
    }
  }

  static void transitionCard(MainActivity a, Model.Transition transition) {
    Store s = a.store;
    LinearLayout card = a.card(s.gradeName(transition.from) + " → " + s.gradeName(transition.to) + " · линия " + transition.line,
      (transition.start == null ? "начало неизвестно" : MainActivity.date(transition.start)) + " → "
        + (transition.end == null ? "окончание неизвестно" : MainActivity.date(transition.end))
        + "\nДо: " + transition.before.size() + " · во время: " + transition.during.size() + " · после: " + transition.after.size()
        + (transition.operator.isEmpty() ? "" : " · разметил " + transition.operator), () -> open(a, draftOf(transition)));
    a.badge(card, transition.confirmed() ? "Подтверждён" : "Неполный", transition.confirmed() ? MainActivity.CONFIRMED : MainActivity.REVIEW);
  }

  /** The draft is created once, so choosing observations and returning keeps what was entered. */
  static void open(MainActivity a, JSONObject draft) { a.go(() -> edit(a, draft)); }

  static JSONObject blank(Analysis.Candidate candidate) {
    JSONObject draft = new JSONObject();
    try {
      draft.put("id", Store.newId()).put("status", "incomplete").put("before", new JSONArray()).put("during", new JSONArray())
        .put("after", new JSONArray());
      if (candidate != null) {
        draft.put("from", candidate.before.grade).put("to", candidate.after.grade).put("line", candidate.before.line);
        draft.getJSONArray("before").put(candidate.before.id);
        draft.getJSONArray("after").put(candidate.after.id);
      }
    } catch (JSONException error) {
      throw new IllegalStateException(error);
    }
    return draft;
  }

  static JSONObject draftOf(Model.Transition t) {
    JSONObject draft = new JSONObject();
    try {
      draft.put("id", t.id).put("from", t.from).put("to", t.to).put("line", t.line).put("start", t.start).put("end", t.end)
        .put("setpoint_time", t.setpointTime).put("delay_min", t.delayMin).put("production", t.production).put("lab", t.lab)
        .put("operator", t.operator).put("notes", t.notes).put("status", t.status).put("before", new JSONArray(t.before))
        .put("during", new JSONArray(t.during)).put("after", new JSONArray(t.after));
    } catch (JSONException error) {
      throw new IllegalStateException(error);
    }
    return draft;
  }

  private static List<String> ids(JSONObject draft, String key) { return Store.strings(draft.optJSONArray(key)); }

  static void edit(MainActivity a, JSONObject draft) {
    Store s = a.store;
    a.frame("Разметка перехода", draft.optString("status").equals("confirmed") ? "подтверждённый эпизод" : "неполный эпизод", "transitions");
    List<String> gradeIds = new ArrayList<>();
    List<String> gradeNames = new ArrayList<>();
    gradeIds.add(null);
    gradeNames.add("не выбрана");
    for (Model.Grade grade : s.grades) { gradeIds.add(grade.id); gradeNames.add(grade.name); }
    Spinner from = a.select("Исходная марка", gradeNames.toArray(new String[0]), Math.max(0, gradeIds.indexOf(Store.str(draft, "from"))));
    Spinner to = a.select("Целевая марка", gradeNames.toArray(new String[0]), Math.max(0, gradeIds.indexOf(Store.str(draft, "to"))));
    Spinner line = a.select("Линия", ObsScreens.LINES, draft.isNull("line") ? 0 : ObsScreens.index(ObsScreens.LINES, draft.optString("line")));
    Map<String, EditText> fields = new HashMap<>();
    fields.put("start", a.input("Фактическое начало смены марки (ДД.ММ.ГГГГ ЧЧ:ММ)", ObsScreens.timeText(Store.str(draft, "start")), InputType.TYPE_CLASS_DATETIME));
    fields.put("end", a.input("Окончание перехода", ObsScreens.timeText(Store.str(draft, "end")), InputType.TYPE_CLASS_DATETIME));
    fields.put("setpoint_time", a.input("Момент изменения основных уставок", ObsScreens.timeText(Store.str(draft, "setpoint_time")), InputType.TYPE_CLASS_DATETIME));
    fields.put("delay_min", a.numberInput("Известная задержка процесса, мин", number(draft, "delay_min")));
    fields.put("production", a.numberInput("Фактическая производительность, т/ч", number(draft, "production")));
    fields.put("lab", a.input("Лабораторная проба: ПТР, плотность, время отбора", draft.optString("lab"), InputType.TYPE_CLASS_TEXT));
    fields.put("operator", a.input("Кто разметил", draft.optString("operator"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS));
    fields.put("notes", a.input("Примечания", draft.optString("notes"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
    View[] timelineHolder = new View[1];
    Runnable keep = () -> {
      try {
        draft.put("from", nullable(gradeIds.get(from.getSelectedItemPosition()))).put("to", nullable(gradeIds.get(to.getSelectedItemPosition())))
          .put("line", line.getSelectedItemPosition() == 0 ? JSONObject.NULL : ObsScreens.LINES[line.getSelectedItemPosition()]);
        for (Map.Entry<String, EditText> field : fields.entrySet()) draft.put("raw_" + field.getKey(), field.getValue().getText().toString().trim());
      } catch (JSONException error) {
        throw new IllegalStateException(error);
      }
    };
    for (EditText field : fields.values()) {
      field.addTextChangedListener(new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable text) {
          keep.run();
          if (timelineHolder[0] != null) timelineHolder[0].invalidate();
        }
      });
    }
    for (String key : fields.keySet()) {
      if (draft.has("raw_" + key)) fields.get(key).setText(draft.optString("raw_" + key));
    }

    a.section("Точки эпизода");
    TimelineView timeline = new TimelineView(a, s, draft);
    timelineHolder[0] = timeline;
    a.page.addView(timeline, new LinearLayout.LayoutParams(-1, a.dp(96)));
    pointsCard(a, draft, "before", "Устойчивый режим до", keep);
    pointsCard(a, draft, "during", "Промежуточные снимки", keep);
    pointsCard(a, draft, "after", "Устойчивый режим после", keep);

    a.buttons("Сохранить как неполный", () -> {
      keep.run();
      save(a, draft, false);
    }, "Подтвердить эпизод", () -> {
      keep.run();
      save(a, draft, true);
    });
    a.note("Подтверждение требует: две разные марки, линию, фактические начало и окончание, хотя бы один устойчивый замер "
      + "исходной марки до начала и один — целевой марки после окончания. Эти замеры получат признак «устойчивый режим», "
      + "промежуточные — «переход»; изменения попадут в историю.");
  }

  private static Object nullable(String value) { return value == null ? JSONObject.NULL : value; }

  private static String number(JSONObject draft, String key) {
    Double value = Store.num(draft, key);
    return value == null ? "" : Calc.fmt(value, 0).replace(',', '.');
  }

  private static void pointsCard(MainActivity a, JSONObject draft, String key, String title, Runnable keep) {
    Store s = a.store;
    List<String> chosen = ids(draft, key);
    StringBuilder text = new StringBuilder();
    for (String id : chosen) {
      Model.Obs obs = s.obsById.get(id);
      if (obs == null) { text.append("• замер ").append(id).append(" не найден\n"); continue; }
      text.append("• ").append(MainActivity.date(obs.time)).append(" · ").append(s.gradeName(obs.grade)).append(" · ")
        .append(MainActivity.timeOrigin(obs.timeOrigin)).append('\n');
    }
    LinearLayout card = a.card(title + " (" + chosen.size() + ")", chosen.isEmpty() ? "не выбрано" : text.toString().trim(), null);
    card.addView(a.button("Выбрать замеры", () -> {
      keep.run();
      choosePoints(a, draft, key, title);
    }));
  }

  private static void choosePoints(MainActivity a, JSONObject draft, String key, String title) {
    Store s = a.store;
    String line = draft.isNull("line") ? null : draft.optString("line", null);
    List<Model.Obs> options = new ArrayList<>();
    for (Model.Obs obs : s.observations) if (line == null || line.equals(obs.line)) options.add(obs);
    options.sort((x, y) -> String.valueOf(x.time).compareTo(String.valueOf(y.time)));
    List<String> chosen = ids(draft, key);
    String[] labels = new String[options.size()];
    boolean[] checked = new boolean[options.size()];
    for (int i = 0; i < options.size(); i++) {
      Model.Obs obs = options.get(i);
      labels[i] = MainActivity.date(obs.time) + " · " + s.gradeName(obs.grade) + " · линия " + (obs.line == null ? "?" : obs.line)
        + (obs.excluded ? " · исключён" : s.disputed.contains(obs.id) ? " · спорный" : "");
      checked[i] = chosen.contains(obs.id);
    }
    a.chooseMany(title, labels, checked, state -> {
      JSONArray result = new JSONArray();
      for (int i = 0; i < state.length; i++) if (state[i]) result.put(options.get(i).id);
      try { draft.put(key, result); } catch (JSONException error) { throw new IllegalStateException(error); }
      a.refresh();
    });
  }

  /** Problems that keep an episode from being confirmed; empty when it can be. */
  static List<String> problems(Store s, JSONObject draft, String start, String end) {
    List<String> problems = new ArrayList<>();
    String from = Store.str(draft, "from"), to = Store.str(draft, "to"), line = Store.str(draft, "line");
    if (from == null || to == null) problems.add("не выбраны обе марки");
    else if (from.equals(to)) problems.add("исходная и целевая марки совпадают");
    if (line == null) problems.add("не указана линия");
    if (start == null) problems.add("не указано фактическое начало смены");
    if (end == null) problems.add("не указано окончание перехода");
    if (start != null && end != null && end.compareTo(start) < 0) problems.add("окончание раньше начала");
    List<String> before = ids(draft, "before"), after = ids(draft, "after");
    if (before.isEmpty()) problems.add("нет устойчивого замера до перехода");
    if (after.isEmpty()) problems.add("нет устойчивого замера после перехода");
    for (String id : before) problems.addAll(point(s, id, from, line, "до", start, true));
    for (String id : after) problems.addAll(point(s, id, to, line, "после", end, false));
    for (String id : ids(draft, "during")) {
      Model.Obs obs = s.obsById.get(id);
      if (obs == null) problems.add("промежуточный замер " + id + " не найден");
      else if (obs.excluded) problems.add("промежуточный замер " + MainActivity.date(obs.time) + " исключён из расчётов");
    }
    return problems;
  }

  private static List<String> point(Store s, String id, String grade, String line, String role, String bound, boolean beforeBound) {
    List<String> problems = new ArrayList<>();
    Model.Obs obs = s.obsById.get(id);
    if (obs == null) { problems.add("замер " + role + " " + id + " не найден"); return problems; }
    String name = "замер " + role + " " + MainActivity.date(obs.time);
    if (grade != null && !grade.equals(obs.grade)) problems.add(name + ": другая марка (" + s.gradeName(obs.grade) + ")");
    if (line != null && !line.equals(obs.line)) problems.add(name + ": другая линия");
    if (!Analysis.timeKnown(obs)) problems.add(name + ": время неизвестно");
    else if (bound != null && (beforeBound ? obs.time.compareTo(bound) > 0 : obs.time.compareTo(bound) < 0)) {
      problems.add(name + (beforeBound ? ": позже начала смены" : ": раньше окончания перехода"));
    }
    if (obs.excluded) problems.add(name + ": исключён из расчётов");
    if (s.disputed.contains(obs.id)) problems.add(name + ": возможный дубль или спорная марка — сначала решите в «Сверке»");
    return problems;
  }

  private static void save(MainActivity a, JSONObject draft, boolean confirm) {
    Store s = a.store;
    try {
      String start = ObsScreens.parseTime(draft.optString("raw_start", ObsScreens.timeText(Store.str(draft, "start"))));
      String end = ObsScreens.parseTime(draft.optString("raw_end", ObsScreens.timeText(Store.str(draft, "end"))));
      String setpoint = ObsScreens.parseTime(draft.optString("raw_setpoint_time", ObsScreens.timeText(Store.str(draft, "setpoint_time"))));
      Double delay = parseOptional(draft, "delay_min"), production = parseOptional(draft, "production");
      List<String> problems = problems(s, draft, start, end);
      if (confirm && !problems.isEmpty()) {
        a.alert("Эпизод нельзя подтвердить", "• " + android.text.TextUtils.join("\n• ", problems)
          + "\n\nСохраните его как неполный — он будет храниться, но не войдёт в подсказку.");
        return;
      }
      JSONObject record = new JSONObject().put("id", draft.getString("id")).put("from", draft.opt("from")).put("to", draft.opt("to"))
        .put("line", draft.opt("line")).put("start", nullable(start)).put("end", nullable(end)).put("setpoint_time", nullable(setpoint))
        .put("delay_min", delay == null ? JSONObject.NULL : delay).put("production", production == null ? JSONObject.NULL : production)
        .put("lab", draft.optString("raw_lab", draft.optString("lab"))).put("operator", draft.optString("raw_operator", draft.optString("operator")))
        .put("notes", draft.optString("raw_notes", draft.optString("notes"))).put("status", confirm ? "confirmed" : "incomplete")
        .put("before", draft.optJSONArray("before")).put("during", draft.optJSONArray("during")).put("after", draft.optJSONArray("after"));
      String text = "Переход " + s.gradeName(Store.str(record, "from")) + " → " + s.gradeName(Store.str(record, "to")) + ", линия "
        + record.optString("line", "?") + ": " + (confirm ? "подтверждён" : "сохранён как неполный")
        + (problems.isEmpty() ? "" : " (не хватает: " + android.text.TextUtils.join("; ", problems) + ")") + ".";
      a.save(() -> {
        s.saveTransition(record, text);
        if (confirm) markSteady(s, record);
      }, a::back);
    } catch (IllegalArgumentException | java.time.DateTimeException | JSONException error) {
      a.alert("Не сохранено", error.getMessage());
    }
  }

  private static Double parseOptional(JSONObject draft, String key) {
    String raw = draft.optString("raw_" + key, number(draft, key));
    if (raw.trim().isEmpty()) return null;
    Double value = Calc.parse(raw);
    if (value == null) throw new IllegalArgumentException("Неверное число в поле «" + key + "»: " + raw);
    return value;
  }

  private static void markSteady(Store s, JSONObject record) throws Exception {
    String reason = "Разметка перехода " + s.gradeName(Store.str(record, "from")) + " → " + s.gradeName(Store.str(record, "to"));
    for (String key : new String[]{"before", "after", "during"}) {
      boolean steady = !"during".equals(key);
      for (String id : Store.strings(record.optJSONArray(key))) {
        Model.Obs obs = s.obsById.get(id);
        if (obs == null || Boolean.valueOf(steady).equals(obs.steady)) continue;
        Map<String, Object> change = new HashMap<>();
        change.put("steady", steady);
        s.patch(obs, change, reason);
      }
    }
  }

  /** «До → переход → после» on one time axis. */
  static final class TimelineView extends View {
    private final Store store;
    private final JSONObject draft;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    TimelineView(Context context, Store store, JSONObject draft) {
      super(context);
      this.store = store;
      this.draft = draft;
      setBackgroundColor(Color.WHITE);
      setContentDescription("Шкала эпизода: точки до, во время и после перехода");
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float density = getResources().getDisplayMetrics().density;
      paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
      List<String[]> points = new ArrayList<>();
      for (String key : new String[]{"before", "during", "after"}) {
        for (String id : Store.strings(draft.optJSONArray(key))) {
          Model.Obs obs = store.obsById.get(id);
          if (obs != null && Analysis.timeKnown(obs)) points.add(new String[]{key, obs.time});
        }
      }
      String start = safe(draft.optString("raw_start", ObsScreens.timeText(Store.str(draft, "start"))));
      String end = safe(draft.optString("raw_end", ObsScreens.timeText(Store.str(draft, "end"))));
      if (start != null) points.add(new String[]{"start", start});
      if (end != null) points.add(new String[]{"end", end});
      float left = 16 * density, right = getWidth() - 16 * density, axis = getHeight() / 2f;
      paint.setColor(MainActivity.BORDER);
      paint.setStrokeWidth(3 * density);
      canvas.drawLine(left, axis, right, axis, paint);
      if (points.isEmpty()) {
        paint.setColor(MainActivity.MUTED);
        canvas.drawText("Точки не выбраны", left, axis - 10 * density, paint);
        return;
      }
      String min = points.get(0)[1], max = points.get(0)[1];
      for (String[] point : points) {
        if (point[1].compareTo(min) < 0) min = point[1];
        if (point[1].compareTo(max) > 0) max = point[1];
      }
      double span = Math.max(1e-6, Analysis.hoursBetween(min, max));
      for (String[] point : points) {
        float x = (float) (left + (right - left) * Analysis.hoursBetween(min, point[1]) / span);
        if ("start".equals(point[0]) || "end".equals(point[0])) {
          paint.setColor(MainActivity.INK);
          paint.setStrokeWidth(2 * density);
          canvas.drawLine(x, axis - 22 * density, x, axis + 22 * density, paint);
          canvas.drawText("start".equals(point[0]) ? "начало" : "конец", x - 14 * density, axis + 36 * density, paint);
        } else {
          paint.setColor("before".equals(point[0]) ? MainActivity.CONFIRMED : "after".equals(point[0]) ? Color.rgb(40, 90, 170) : MainActivity.REVIEW);
          canvas.drawCircle(x, axis, 7 * density, paint);
        }
      }
      paint.setColor(MainActivity.MUTED);
      canvas.drawText(MainActivity.date(min), left, 14 * density, paint);
      String last = MainActivity.date(max);
      canvas.drawText(last, right - paint.measureText(last), 14 * density, paint);
    }

    private static String safe(String raw) {
      try {
        return ObsScreens.parseTime(raw);
      } catch (RuntimeException error) {
        return null;
      }
    }
  }
}
