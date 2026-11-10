package ru.tomskneftekhim.rezhim;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Home, grades, observation lists, tech cards and sources: the archive as shipped plus phone additions. */
final class ArchiveScreens {
  private ArchiveScreens() {}

  static void home(MainActivity a) {
    Store s = a.store;
    int values = 0, extras = 0, photos = 0, userPhotos = 0;
    for (Model.Val value : s.values) if (value.extra) extras++; else values++;
    for (Model.Source source : s.sources) if (source.isImage()) { photos++; if (source.user) userPhotos++; }
    int confirmedTransitions = 0;
    for (Model.Transition transition : s.transitions) if (transition.confirmed()) confirmedTransitions++;
    List<Analysis.Candidate> candidates = Analysis.candidates(s.observations, s.disputed);
    a.frame("Режимы марок", MainActivity.grades(s.grades.size()) + " · " + MainActivity.observations(s.observations.size()) + " · " + MainActivity.values(values), "home");
    a.note("Вся база доступна без интернета: ревизия " + s.snapshot.optInt("revision") + " от " + MainActivity.date(s.snapshot.optString("built_at"))
      + (s.baseOrigin.equals("builtin") ? " (встроена в приложение)" : " (загружена из файла)")
      + ". Непроверенные значения видны с отметкой «требует сверки».");
    LinearLayout stats = a.card("Состояние данных", null, null);
    stats.addView(a.label("Подтверждено: " + s.count(Model.CONFIRMED), 14, MainActivity.CONFIRMED, true));
    stats.addView(a.label("Требует сверки: " + s.count(Model.REVIEW) + " (+ " + MainActivity.count(extras, "поле", "поля", "полей") + " вне словаря)", 14, MainActivity.REVIEW, true));
    stats.addView(a.label("Отклонено: " + s.count(Model.REJECTED), 14, MainActivity.REJECTED, true));
    stats.addView(a.label("Фото: " + photos + (userPhotos > 0 ? ", из них добавлено на телефоне " + userPhotos : ""), 14, MainActivity.INK, false));
    stats.addView(a.label("Переходы: подтверждённых " + confirmedTransitions + ", неполных " + (s.transitions.size() - confirmedTransitions)
      + ", кандидатов из хронологии " + candidates.size(), 14, MainActivity.INK, false));
    if (!s.duplicates.isEmpty() || !s.openConflicts().isEmpty()) {
      stats.addView(a.label("Спорные марки и дубли: " + s.duplicates.size() + " · конфликты импорта: " + s.openConflicts().size(),
        14, MainActivity.REJECTED, true));
    }
    a.buttons("Переход: откуда → куда", () -> a.go(() -> RegimeScreens.plan(a)), "Прошлые режимы", () -> a.go(() -> RegimeScreens.history(a, null)));
    a.buttons("Смены марок", () -> a.home(() -> SmenyScreen.show(a)), "Разметка переходов", () -> a.go(() -> TransitionScreens.list(a)));
    a.buttons("Добавить фото", () -> a.go(() -> PhotoScreens.add(a)), "Оценка сейчас", () -> EstimateScreen.open(a));
    a.buttons("Все замеры", () -> a.go(() -> observations(a, null)), "Техкарты", () -> a.go(() -> docs(a, null)));
    a.buttons("Источники и фото", () -> a.go(() -> sources(a, null)), "База и JSON", () -> a.go(() -> BaseScreen.show(a)));

    a.section("Последние добавления на телефоне");
    int shown = 0;
    for (int i = s.history.size() - 1; i >= 0 && shown < 8; i--) {
      Model.History entry = s.history.get(i);
      if (!entry.user) continue;
      shown++;
      Model.Obs obs = entry.obs == null ? null : s.obsById.get(entry.obs);
      a.card(MainActivity.date(entry.at), entry.text, obs == null ? null : () -> a.go(() -> ObsScreens.show(a, obs.id)));
    }
    if (shown == 0) a.note("На телефоне пока ничего не добавлено и не сверено. Ниже — последние замеры архива.");
    a.section("Последние замеры архива");
    List<Model.Obs> latest = s.observationsOf(null);
    for (int i = 0; i < Math.min(5, latest.size()); i++) obsCard(a, latest.get(i));
  }

  static void obsCard(MainActivity a, Model.Obs obs) {
    LinearLayout card = a.card(a.obsTitle(obs), a.obsLine(obs), () -> a.go(() -> ObsScreens.show(a, obs.id)));
    if (obs.excluded || a.store.disputed.contains(obs.id)) {
      a.badge(card, obs.excluded ? "Исключён из расчётов" : "Возможный дубль / спорная марка", MainActivity.REJECTED);
    }
  }

  static void grades(MainActivity a, String query) {
    Store s = a.store;
    a.frame("Марки", MainActivity.grades(s.grades.size()) + " в базе", "grades");
    EditText search = a.input("Поиск по названию или псевдониму", query, InputType.TYPE_CLASS_TEXT);
    LinearLayout list = a.vertical();
    a.page.addView(list);
    Runnable fill = () -> {
      list.removeAllViews();
      LinearLayout outer = a.page;
      a.page = list;
      String needle = search.getText().toString().trim().toLowerCase(Locale.ROOT);
      int found = 0;
      for (Model.Grade grade : s.grades) {
        String haystack = (grade.name + " " + grade.aliases).toLowerCase(Locale.ROOT);
        if (!needle.isEmpty() && !haystack.contains(needle)) continue;
        found++;
        int values = 0;
        List<Model.Obs> observations = s.observationsOf(grade.id);
        for (Model.Obs obs : observations) for (Model.Val value : s.values(obs.id)) if (!value.extra) values++;
        a.card(grade.name, MainActivity.observations(observations.size()) + " · " + MainActivity.docs(s.docsOf(grade.id).size()) + " · " + MainActivity.values(values)
          + (grade.aliases.isEmpty() ? "" : "\nПсевдонимы: " + grade.aliases), () -> a.go(() -> grade(a, grade.id)));
      }
      List<Model.Obs> unknown = new ArrayList<>();
      for (Model.Obs obs : s.observations) if (obs.grade == null) unknown.add(obs);
      if (!unknown.isEmpty() && needle.isEmpty()) {
        a.card("Марка неизвестна", MainActivity.observations(unknown.size()) + " без марки", () -> a.go(() -> observations(a, "")));
      }
      if (found == 0) a.note("Ничего не найдено.");
      a.page = outer;
    };
    search.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable text) { fill.run(); }
    });
    fill.run();
  }

  static void grade(MainActivity a, String gradeId) {
    Store s = a.store;
    List<Model.Obs> list = s.observationsOf(gradeId);
    List<Model.Doc> docs = s.docsOf(gradeId);
    int values = 0;
    for (Model.Obs obs : list) for (Model.Val value : s.values(obs.id)) if (!value.extra) values++;
    a.frame("Марка " + s.gradeName(gradeId), MainActivity.observations(list.size()) + " · " + MainActivity.docs(docs.size()) + " · " + MainActivity.values(values), "grades");
    a.buttons("Прошлые режимы", () -> a.go(() -> RegimeScreens.history(a, gradeId)), "Сравнить с нынешним", () -> {
      a.prefs().edit().putString("plan_to", gradeId).putString("plan_target", "last").apply();
      a.go(() -> RegimeScreens.plan(a));
    });
    a.buttons("Режим марки", () -> a.go(() -> SmenyScreen.mode(a, gradeId)), "Смены с маркой", () -> {
      a.prefs().edit().putString("smeny_grade", gradeId).apply();
      a.home(() -> SmenyScreen.show(a));
    });
    a.buttons("Все замеры марки", () -> a.go(() -> observations(a, gradeId)), "Фото марки", () -> a.go(() -> sources(a, gradeId)));
    a.section("Пропорции по снимкам");
    a.note("Каждая строка — отдельный снимок. ПАЛ и зоны — факт, кг/ч; доли зон — % от суммы; ПАЛ/FΣ — отношение наблюдаемых расходов; "
      + "ПАЛ/C3 — кг/м³ при известной единице пропилена. Значок «?» — по непроверенным данным. Нажмите строку, чтобы открыть замер.");
    proportionsTable(a, list);
    a.section("Техкарты и рецептуры");
    if (docs.isEmpty()) a.note("Техкарт этой марки в базе нет.");
    for (Model.Doc doc : docs) docCard(a, doc);
    a.section("Замеры");
    if (list.isEmpty()) a.note("Замеров этой марки пока нет.");
    for (Model.Obs obs : list) obsCard(a, obs);
  }

  static void proportionsTable(MainActivity a, List<Model.Obs> list) {
    List<String[]> rows = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    List<Integer> colors = new ArrayList<>();
    rows.add(new String[]{"Снимок", "Лин.", "ПАЛ", "F1", "F2", "F3", "FΣ", "Доли 1/2/3, %", "ПАЛ/FΣ", "C3, м³/ч", "ПАЛ/C3", "Q, т/ч"});
    actions.add(null);
    colors.add(null);
    int withData = 0;
    for (Model.Obs obs : list) {
      CompareScreen.Proportions p = CompareScreen.proportions(a.store, obs.id);
      if (p.pal == null && p.z1 == null && p.c3 == null) continue;
      withData++;
      String shares = p.s1.ok() ? cell(p.s1, 1) + " / " + cell(p.s2, 1) + " / " + cell(p.s3, 1) : "—";
      rows.add(new String[]{MainActivity.date(obs.time), obs.line == null ? "?" : obs.line, input(p.pal), input(p.z1), input(p.z2),
        input(p.z3), cell(p.sum, 2), shares, cell(p.palSum, 3), input(p.c3), cellSignificant(p.palC3), input(p.q)});
      actions.add(() -> a.go(() -> ObsScreens.show(a, obs.id)));
      colors.add(p.unverified() ? MainActivity.REVIEW : MainActivity.INK);
    }
    if (withData == 0) a.note("Ни в одном замере нет ПАЛ, зонных расходов инициатора или пропилена.");
    else a.table(rows, actions, colors);
  }

  static String input(Calc.Input input) {
    if (input == null || input.number == null) return "—";
    return Calc.fmt(input.number, 2) + (input.confirmed ? "" : "?");
  }

  static String cell(Calc.Result result, int places) {
    if (!result.ok()) return "—";
    return Calc.fixed(result.value, places) + (result.unverified ? "?" : "");
  }

  static String cellSignificant(Calc.Result result) {
    if (!result.ok()) return "—";
    return Calc.significant(result.value, 4) + (result.unverified ? "?" : "");
  }

  static void observations(MainActivity a, String gradeId) {
    Store s = a.store;
    List<Model.Obs> list = new ArrayList<>();
    if ("".equals(gradeId)) {
      for (Model.Obs obs : s.observationsOf(null)) if (obs.grade == null) list.add(obs);
    } else {
      list = s.observationsOf(gradeId);
    }
    String title = gradeId == null ? "Все замеры" : "".equals(gradeId) ? "Замеры без марки" : "Замеры · " + s.gradeName(gradeId);
    a.frame(title, MainActivity.observations(list.size()) + ", новые сверху", gradeId == null ? "home" : "grades");
    if (list.isEmpty()) a.note("Замеров нет.");
    for (Model.Obs obs : list) obsCard(a, obs);
  }

  static void docCard(MainActivity a, Model.Doc doc) {
    int norms = 0, recipes = 0;
    for (Model.Norm norm : a.store.norms) if (doc.id.equals(norm.doc)) norms++;
    for (Model.Recipe recipe : a.store.recipes) if (doc.id.equals(recipe.doc)) recipes++;
    String validity = "с " + MainActivity.date(doc.validFrom) + (doc.validTo == null ? "" : " по " + MainActivity.date(doc.validTo));
    a.card(a.store.gradeName(doc.grade) + " · версия " + doc.version, validity + " · " + norms + " норм · " + recipes + " строк рецептуры",
      () -> a.go(() -> doc(a, doc.id)));
  }

  static void docs(MainActivity a, String gradeId) {
    List<Model.Doc> list = a.store.docsOf(gradeId);
    a.frame("Техкарты", list.size() + " версий документов", "home");
    for (Model.Doc doc : list) docCard(a, doc);
  }

  static void doc(MainActivity a, String docId) {
    Store s = a.store;
    Model.Doc doc = s.docById.get(docId);
    String validity = "с " + MainActivity.date(doc.validFrom) + (doc.validTo == null ? " (действует)" : " по " + MainActivity.date(doc.validTo));
    a.frame("Техкарта " + doc.version, s.gradeName(doc.grade) + " · " + validity, "grades");
    JSONObject details = null;
    try { details = doc.detailsJson == null ? null : new JSONObject(doc.detailsJson); } catch (Exception ignored) { }
    if (details != null && details.optString("title").length() > 0) a.note(details.optString("title"));
    if (details != null && details.optJSONArray("spec") != null) {
      a.section("Показатели продукта");
      List<String[]> rows = new ArrayList<>();
      rows.add(new String[]{"Показатель", "Требование"});
      JSONArray spec = details.optJSONArray("spec");
      for (int i = 0; i < spec.length(); i++) {
        JSONArray pair = spec.optJSONArray(i);
        if (pair != null) rows.add(new String[]{pair.optString(0), pair.optString(1)});
      }
      a.table(rows, null, null);
    }
    a.section("Нормы режима");
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[]{"Параметр", "От", "Цель", "До", "Ед."});
    for (Model.Norm norm : s.norms) {
      if (!docId.equals(norm.doc)) continue;
      Model.Param param = s.paramByKey.get(norm.param);
      rows.add(new String[]{param == null ? norm.param : param.name, Calc.fmt(norm.lower, 0), Calc.fmt(norm.target, 0),
        Calc.fmt(norm.upper, 0), param == null || param.unit.isEmpty() ? "?" : param.unit});
    }
    if (rows.size() == 1) a.note("Числовые нормы для этой версии не внесены.");
    else a.table(rows, null, null);
    a.section("Рецептура");
    String zones = null;
    List<String[]> recipe = null;
    for (Model.Recipe item : s.recipes) {
      if (!docId.equals(item.doc)) continue;
      if (!item.zones.equals(zones)) {
        if (recipe != null) a.table(recipe, null, null);
        zones = item.zones;
        a.page.addView(a.label(zones + (item.version.isEmpty() ? "" : " · " + item.version), 14, MainActivity.INK, true));
        recipe = new ArrayList<>();
        recipe.add(new String[]{"Компонент", "%", "кг", "Мест"});
      }
      recipe.add(new String[]{item.component, Calc.fmt(item.percent, 0), Calc.fmt(item.mass, 0), Calc.fmt(item.count, 0)});
    }
    if (recipe != null) a.table(recipe, null, null); else a.note("Состав рецептуры для этой версии не внесён.");
    if (!doc.notes.isEmpty()) { a.section("Примечания"); a.note(doc.notes); }
    if (details != null && details.optJSONArray("notes") != null) {
      JSONArray notes = details.optJSONArray("notes");
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < notes.length(); i++) text.append("• ").append(notes.optString(i)).append('\n');
      a.note(text.toString().trim());
    }
    Model.Source source = s.sourceById.get(doc.source);
    if (source != null) {
      a.section("Источник");
      a.card(source.name, source.kind + " · SHA-256 " + source.sha256.substring(0, 16) + "…", () -> openSource(a, source));
    }
  }

  static void openSource(MainActivity a, Model.Source source) {
    if (source.isImage()) a.go(() -> PhotoScreens.view(a, source.id, null, null));
    else a.go(() -> textSource(a, source));
  }

  static void sources(MainActivity a, String gradeId) {
    Store s = a.store;
    List<Model.Source> list = new ArrayList<>();
    for (Model.Source source : s.sources) {
      if (gradeId == null) { list.add(source); continue; }
      for (Model.Obs obs : s.observationsOf(gradeId)) if (obs.sources.contains(source.id)) { list.add(source); break; }
    }
    int photos = 0;
    for (Model.Source source : list) if (source.isImage()) photos++;
    a.frame(gradeId == null ? "Источники" : "Фото · " + s.gradeName(gradeId), list.size() + " файлов, фото: " + photos, "home");
    a.note("Оригиналы открываются без сети. При открытии фото проверяется SHA-256; полная проверка — в разделе «База и JSON».");
    for (Model.Source source : list) {
      int linked = 0;
      for (Model.Obs obs : s.observations) if (obs.sources.contains(source.id)) linked++;
      String details = kind(source) + " · " + String.format(Locale.ROOT, "%.1f", source.size / 1048576.0).replace('.', ',') + " МБ"
        + (source.exifTime == null ? "" : " · EXIF " + MainActivity.date(source.exifTime))
        + " · замеров: " + linked + (source.user ? " · добавлено на телефоне" : "")
        + (s.available(source) ? "" : " · ФАЙЛ ОТСУТСТВУЕТ");
      a.card(source.name, details, () -> openSource(a, source));
    }
  }

  static String kind(Model.Source source) {
    switch (source.kind == null ? "" : source.kind) {
      case "photo": return "фото" + (source.screen == null || source.screen.isEmpty() || "unknown".equals(source.screen) ? "" : ", экран " + source.screen);
      case "ai_json": return "JSON распознавания";
      case "migration": return "JSON миграции";
      case "prototype": return "прототип HTML";
      case "correction": return "JSON корректировки";
      default: return source.kind;
    }
  }

  static void textSource(MainActivity a, Model.Source source) {
    a.frame(source.name, kind(source) + " · SHA-256 " + source.sha256.substring(0, 16) + "…", "home");
    TextView view = a.note("Чтение файла…");
    a.runInBackground(() -> {
      String text;
      try (InputStream in = a.store.open(source)) {
        text = Store.readText(in);
        if (text.length() > 200_000) text = text.substring(0, 200_000) + "\n… (показаны первые 200 000 знаков)";
      } catch (Exception error) {
        text = "Не удалось прочитать: " + error.getMessage();
      }
      String shown = text;
      a.runLater(() -> {
        view.setText(shown);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(12);
      });
    });
  }
}
