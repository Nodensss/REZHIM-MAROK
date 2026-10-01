package ru.tomskneftekhim.rezhim;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** One observation: attributes with their origin, values by type, photos, review actions and edits. */
final class ObsScreens {
  private ObsScreens() {}

  static final String[] LINES = {"Линия неизвестна", "A", "B"};
  static final String[] EQUIPMENT = {"unknown", "running", "stopped"};
  static final String[] SUUTP = {"unknown", "on", "off"};
  static final String[] TIME_ORIGINS = {"unknown", "screen", "exif", "manual"};
  static final String[] GRADE_ORIGINS = {"user", "folder", "recognition", "unknown"};

  static void show(MainActivity a, String obsId) {
    Store s = a.store;
    Model.Obs obs = s.obsById.get(obsId);
    if (obs == null) {
      a.frame("Замер не найден", obsId, "grades");
      return;
    }
    List<Model.Val> values = s.values(obsId);
    int confirmed = 0, review = 0, rejected = 0;
    for (Model.Val value : values) {
      if (value.extra) continue;
      if (value.confirmed()) confirmed++; else if (Model.REVIEW.equals(value.status)) review++; else rejected++;
    }
    a.frame(a.obsTitle(obs), MainActivity.date(obs.time) + " · " + confirmed + " подтв. · " + review + " на сверке · " + rejected + " откл.", "grades");
    if (obs.excluded) a.warn("Замер исключён из расчётов и поиска аналогов: " + obs.excludedReason);
    if (s.disputed.contains(obsId)) {
      for (Analysis.Duplicate duplicate : s.duplicates) {
        if (!duplicate.a.equals(obsId) && !duplicate.b.equals(obsId)) continue;
        Model.Obs other = s.obsById.get(duplicate.a.equals(obsId) ? duplicate.b : duplicate.a);
        a.warn("Возможный дубль: " + duplicate.common + " показателей совпадают с замером " + a.obsTitle(other) + ", "
          + MainActivity.date(other.time) + (duplicate.differentGrade ? ". Марки разные — атрибуция спорная, замер направлен на сверку." : "."));
      }
    }
    List<String> conflicts = s.conflicts(obsId);
    if (!conflicts.isEmpty()) {
      List<String> names = new ArrayList<>();
      for (String key : conflicts) names.add(s.paramByKey.containsKey(key) ? s.paramByKey.get(key).name : key);
      a.warn("Разные числа одного параметра, оба на сверке: " + android.text.TextUtils.join(", ", names) + ".");
    }
    a.note(attributes(a, obs));
    a.buttons("Фото замера (" + photoCount(s, obs) + ")", () -> a.go(() -> photos(a, obsId)), "Изменить реквизиты", () -> a.go(() -> edit(a, obsId)));
    a.buttons("Добавить значение", () -> {
      Draft draft = new Draft();
      a.go(() -> addValue(a, obsId, draft));
    }, "История замера", () -> a.go(() -> history(a, obsId)));
    a.buttons("Сравнить как А", () -> { a.setCompareSlot("a", obsId); a.home(() -> CompareScreen.show(a)); },
      "Сравнить как Б", () -> { a.setCompareSlot("b", obsId); a.home(() -> CompareScreen.show(a)); });

    Map<String, Model.Norm> norms = s.normsOf(s.effectiveDoc(obs));
    Map<String, List<Model.Val>> byType = new LinkedHashMap<>();
    for (String type : CompareScreen.TYPES) byType.put(type, new ArrayList<>());
    byType.put("extra", new ArrayList<>());
    List<Model.Val> rejectedList = new ArrayList<>();
    for (Model.Val value : values) {
      if (!value.active()) rejectedList.add(value);
      else if (value.extra) byType.get("extra").add(value);
      else byType.computeIfAbsent(value.type, key -> new ArrayList<>()).add(value);
    }
    for (Map.Entry<String, List<Model.Val>> entry : byType.entrySet()) {
      if (entry.getValue().isEmpty()) continue;
      a.section("extra".equals(entry.getKey()) ? "Поля вне словаря" : MainActivity.type(entry.getKey()));
      for (Model.Val value : entry.getValue()) valueCard(a, value, norms.get(value.param));
    }
    if (!rejectedList.isEmpty()) {
      a.section("Отклонённые и исправленные");
      for (Model.Val value : rejectedList) valueCard(a, value, null);
    }
    if (values.isEmpty()) a.note("В замере пока нет значений. Нажмите «Добавить значение».");
    if (!norms.isEmpty()) {
      a.section("Нормы техкарты (справочно)");
      List<String[]> rows = new ArrayList<>();
      rows.add(new String[]{"Параметр", "От", "Цель", "До"});
      for (Model.Norm norm : norms.values()) {
        Model.Param param = s.paramByKey.get(norm.param);
        rows.add(new String[]{(param == null ? norm.param : param.name) + (param == null || param.unit.isEmpty() ? "" : ", " + param.unit),
          Calc.fmt(norm.lower, 0), Calc.fmt(norm.target, 0), Calc.fmt(norm.upper, 0)});
      }
      a.table(rows, null, null);
      a.note("Нормы — ограничения техкарты, а не доказательство того, что такой режим реально работал.");
    }
  }

  static int photoCount(Store s, Model.Obs obs) {
    int count = 0;
    for (String id : obs.sources) {
      Model.Source source = s.sourceById.get(id);
      if (source != null && source.isImage()) count++;
    }
    return count;
  }

  static String attributes(MainActivity a, Model.Obs obs) {
    Store s = a.store;
    StringBuilder text = new StringBuilder();
    text.append("Марка: ").append(s.gradeName(obs.grade)).append(" (").append(MainActivity.gradeOrigin(obs.gradeOrigin)).append(")");
    if (!obs.gradeNote.isEmpty()) text.append("\n   ").append(obs.gradeNote);
    text.append("\nЛиния: ").append(obs.line == null ? "неизвестна" : obs.line);
    text.append("\nВремя: ").append(MainActivity.date(obs.time)).append(" — ").append(MainActivity.timeOrigin(obs.timeOrigin));
    if (!obs.timeNote.isEmpty()) text.append("\n   ").append(obs.timeNote);
    text.append("\nА-103: ").append(MainActivity.equipment(obs.a103)).append(" · АБ-124: ").append(MainActivity.equipment(obs.ab124))
      .append(" · СУУТП: ").append(MainActivity.equipment(obs.suutp));
    text.append("\nРежим: ").append(MainActivity.steady(obs.steady));
    Model.Doc doc = obs.doc == null ? null : s.docById.get(obs.doc);
    Model.Doc byDate = doc == null ? s.docOn(obs.grade, obs.time) : null;
    text.append("\nТехкарта: ").append(doc != null ? doc.version : byDate != null ? "не указана (по дате действует " + byDate.version + ")" : "не указана");
    text.append("\nРецептуры: зоны 1–2 — ").append(obs.recipe12.isEmpty() ? "не указана" : obs.recipe12)
      .append("; зона 3 — ").append(obs.recipe3.isEmpty() ? "не указана" : obs.recipe3);
    if (!obs.pumps.isEmpty()) {
      List<String> pumps = new ArrayList<>();
      for (int[] pump : obs.pumps) pumps.add("зона " + pump[0] + " — насос " + pump[1]);
      text.append("\nНасосы: ").append(android.text.TextUtils.join(", ", pumps));
    }
    if (obs.screen != null && !obs.screen.isEmpty()) text.append("\nЭкран: ").append(obs.screen);
    if (obs.phase != null) text.append("\nФаза: ").append(obs.phase);
    for (String note : obs.notes) text.append("\n• ").append(note);
    if (obs.user) text.append("\nЗамер создан на телефоне ").append(MainActivity.date(obs.createdAt)).append('.');
    return text.toString();
  }

  static void valueCard(MainActivity a, Model.Val value, Model.Norm norm) {
    Store s = a.store;
    Model.Param param = value.param == null ? null : s.paramByKey.get(value.param);
    String tag = param != null && !param.tag.isEmpty() ? " · " + param.tag : value.tag.isEmpty() ? "" : " · " + value.tag;
    String details = a.reading(value) + tag + "\n" + MainActivity.method(value.method)
      + (value.photos.isEmpty() ? " · без фото" : " · фото: " + value.photos.size()) + (value.user ? " · добавлено на телефоне" : "");
    if (norm != null && value.hasNumber()) {
      boolean below = norm.lower != null && value.number < norm.lower, above = norm.upper != null && value.number > norm.upper;
      if (below || above) details += "\nВне нормы техкарты: " + Calc.fmt(norm.lower, 0) + "…" + Calc.fmt(norm.upper, 0);
    }
    LinearLayout card = a.card(s.paramName(value), details, () -> a.go(() -> value(a, value.id)));
    a.badge(card, MainActivity.status(value.status), MainActivity.statusColor(value.status));
  }

  static void value(MainActivity a, String valueId) {
    Store s = a.store;
    Model.Val value = s.valueById.get(valueId);
    Model.Obs obs = s.obsById.get(value.obs);
    Model.Param param = value.param == null ? null : s.paramByKey.get(value.param);
    a.frame(s.paramName(value), a.obsTitle(obs) + " · " + MainActivity.date(obs.time), "review");
    LinearLayout card = a.card(a.reading(value), MainActivity.type(value.type) + " · " + MainActivity.method(value.method), null);
    a.badge(card, MainActivity.status(value.status) + (value.statusReason.isEmpty() ? "" : ": " + value.statusReason), MainActivity.statusColor(value.status));
    StringBuilder text = new StringBuilder();
    if (param != null) text.append("Ключ: ").append(param.key).append(param.tag.isEmpty() ? "" : " · тег " + param.tag).append(" · группа «").append(param.group).append("»\n");
    else text.append("Поле вне словаря").append(value.tag.isEmpty() ? "" : " · тег " + value.tag).append('\n');
    text.append("Единица: ").append(value.unit.isEmpty() ? "не установлена — число не участвует в расчётах" : value.unit);
    if (!value.unitNote.isEmpty()) text.append("\n").append(value.unitNote);
    if (value.raw != null) text.append("\nИсходный текст распознавания: «").append(value.raw).append("»");
    if (value.replaces != null) text.append("\nЗаменяет значение ").append(describe(s, value.replaces));
    for (Model.Val other : s.values(value.obs)) {
      if (valueId.equals(other.replaces)) text.append("\nЗаменено на ").append(a.reading(other)).append(" (").append(MainActivity.status(other.status)).append(")");
    }
    Model.Norm norm = s.normsOf(s.effectiveDoc(obs)).get(value.param);
    if (norm != null) {
      text.append("\nНорма техкарты: ").append(Calc.fmt(norm.lower, 0)).append("…").append(Calc.fmt(norm.upper, 0))
        .append(norm.target == null ? "" : ", цель " + Calc.fmt(norm.target, 0)).append(" (для справки)");
    }
    a.note(text.toString());
    List<String> photos = new ArrayList<>(value.photos);
    if (photos.isEmpty()) {
      for (String id : obs.sources) {
        Model.Source source = s.sourceById.get(id);
        if (source != null && source.isImage()) photos.add(id);
      }
    }
    a.section(value.photos.isEmpty() ? "Фото замера" : "Фото этого значения");
    if (photos.isEmpty()) a.note("Фото не привязано. Для старых записей прототипа исходного фото в поставке нет.");
    for (String id : photos) {
      Model.Source source = s.sourceById.get(id);
      if (source == null) continue;
      LinearLayout photo = a.card(source.name, (value.region != null && value.photos.contains(id) ? "Откроется на отмеченной области · " : "")
        + (source.exifTime == null ? "" : "EXIF " + MainActivity.date(source.exifTime)), () ->
        a.go(() -> PhotoScreens.view(a, id, value.photos.contains(id) ? value.region : null, null)));
      PhotoScreens.thumbnail(a, photo, source);
    }
    a.section("Сверка");
    Runnable reject = () -> decide(a, value, Model.REJECTED, "Число не совпадает с исходником");
    Runnable reopen = () -> decide(a, value, Model.REVIEW, "Нужна повторная проверка");
    if (Model.REJECTED.equals(value.status)) {
      a.buttons("Вернуть на сверку", reopen, null, null);
    } else if (value.confirmed()) {
      a.buttons("Отклонить", reject, "Вернуть на сверку", reopen);
    } else if (photos.isEmpty()) {
      a.note("Подтвердить можно только сверив число с исходным фото. Прикрепите фото к замеру или оставьте значение на сверке.");
      a.buttons("Отклонить", reject, null, null);
    } else {
      a.buttons("Подтвердить", () -> decide(a, value, Model.CONFIRMED, "Сверено с фото " + s.sourceById.get(photos.get(0)).name),
        "Отклонить", reject);
    }
    a.buttons("Исправить число", () -> a.go(() -> correct(a, valueId)), "Отметить область", () -> markRegion(a, value, photos));
    a.section("История");
    List<Model.History> history = s.historyOfValue(value);
    if (history.isEmpty()) a.note("Записей в истории нет.");
    for (Model.History entry : history) a.card(MainActivity.date(entry.at) + (entry.user ? " · телефон" : " · ПК"), entry.text, null);
  }

  private static String describe(Store s, String valueId) {
    Model.Val value = s.valueById.get(valueId);
    return value == null ? valueId : Store.describe(value.number, value.state) + " (" + MainActivity.status(value.status) + ")";
  }

  static void decide(MainActivity a, Model.Val value, String status, String reason) {
    a.prompt(MainActivity.status(status), a.store.paramName(value) + ": " + a.reading(value) + ". Причина решения попадёт в историю.", reason,
      text -> {
        if (text.isEmpty()) { a.alert("Нужна причина", "Укажите, на основании чего принято решение."); return; }
        a.save(() -> a.store.decide(value, status, text), a::refresh);
      });
  }

  static void markRegion(MainActivity a, Model.Val value, List<String> photos) {
    if (photos.isEmpty()) {
      a.alert("Нет фото", "Сначала прикрепите фото к замеру.");
      return;
    }
    String[] names = new String[photos.size()];
    for (int i = 0; i < names.length; i++) names[i] = a.store.sourceById.get(photos.get(i)).name;
    a.choose("Фото для области", names, which -> a.go(() -> PhotoScreens.view(a, photos.get(which), value.region, region -> {
      a.back();
      a.save(() -> a.store.setRegion(value, photos.get(which), region), a::refresh);
    })));
  }

  static void correct(MainActivity a, String valueId) {
    Store s = a.store;
    Model.Val value = s.valueById.get(valueId);
    a.frame("Исправить число", s.paramName(value) + ": сейчас " + a.reading(value), "review");
    a.note("Старое число не удаляется: оно станет «отклонено», а исправленное появится отдельной записью со ссылкой на прежнее.");
    EditText number = a.numberInput("Число с исходника", value.number == null ? "" : Calc.fmt(value.number, 0).replace(',', '.'));
    Spinner state = a.select("Что на исходнике", new String[]{"Число", "Пропуск (поле пустое)", "Ошибка исходника"}, 0);
    boolean hasPhoto = !value.photos.isEmpty() || photoCount(s, s.obsById.get(value.obs)) > 0;
    Spinner status = a.select("Статус исправленного", new String[]{"Требует сверки", "Подтвердить — сверено с фото"}, hasPhoto ? 1 : 0);
    EditText reason = a.input("Причина исправления", "Повторное чтение фото", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    a.buttons("Сохранить", () -> {
      String chosen = state.getSelectedItemPosition() == 0 ? "number" : state.getSelectedItemPosition() == 1 ? "missing" : "error";
      Double parsed = Calc.parse(number.getText().toString());
      if ("number".equals(chosen) && parsed == null) { a.alert("Неверное число", "Введите число; запятая и точка допустимы."); return; }
      if (reason.getText().toString().trim().isEmpty()) { a.alert("Нужна причина", "Укажите причину исправления."); return; }
      if (status.getSelectedItemPosition() == 1 && !hasPhoto) { a.alert("Нет фото", "Без исходного фото исправленное число остаётся на сверке."); return; }
      a.save(() -> s.correct(value, "number".equals(chosen) ? parsed : null, chosen, status.getSelectedItemPosition() == 1,
        reason.getText().toString().trim()), a::back);
    }, "Отмена", a::back);
  }

  static void photos(MainActivity a, String obsId) {
    Store s = a.store;
    Model.Obs obs = s.obsById.get(obsId);
    a.frame("Фото замера", a.obsTitle(obs) + " · " + MainActivity.date(obs.time), "grades");
    a.buttons("Прикрепить фото", () -> PhotoScreens.pickFor(a, obsId), null, null);
    int shown = 0;
    for (String id : obs.sources) {
      Model.Source source = s.sourceById.get(id);
      if (source == null) continue;
      shown++;
      LinearLayout card = a.card(source.name, ArchiveScreens.kind(source) + (source.exifTime == null ? "" : " · EXIF " + MainActivity.date(source.exifTime))
        + (s.available(source) ? "" : " · ФАЙЛ ОТСУТСТВУЕТ"), () -> ArchiveScreens.openSource(a, source));
      if (source.isImage()) PhotoScreens.thumbnail(a, card, source);
    }
    if (shown == 0) a.note("К замеру не привязано ни одного файла. Записи прототипа перенесены без фото.");
  }

  static void history(MainActivity a, String obsId) {
    Store s = a.store;
    Model.Obs obs = s.obsById.get(obsId);
    a.frame("История замера", a.obsTitle(obs) + " · " + MainActivity.date(obs.time), "grades");
    List<Model.History> entries = s.historyOfObs(obsId);
    Collections.reverse(entries);
    if (entries.isEmpty()) a.note("Записей нет.");
    for (Model.History entry : entries) a.card(MainActivity.date(entry.at) + (entry.user ? " · телефон" : " · ПК"), entry.text, null);
  }

  // ---------------------------------------------------------------- attributes

  static String parseTime(String raw) {
    String text = raw == null ? "" : raw.trim();
    if (text.isEmpty()) return null;
    java.util.regex.Matcher ru = java.util.regex.Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})[ T]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matcher(text);
    java.util.regex.Matcher iso = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?").matcher(text);
    String result;
    if (ru.matches()) {
      result = String.format(Locale.ROOT, "%s-%02d-%02dT%02d:%s:%s", ru.group(3), Integer.parseInt(ru.group(2)), Integer.parseInt(ru.group(1)),
        Integer.parseInt(ru.group(4)), ru.group(5), ru.group(6) == null ? "00" : ru.group(6));
    } else if (iso.matches()) {
      result = iso.group(1) + "-" + iso.group(2) + "-" + iso.group(3) + "T" + iso.group(4) + ":" + iso.group(5) + ":" + (iso.group(6) == null ? "00" : iso.group(6));
    } else {
      throw new IllegalArgumentException("Время «" + text + "» не распознано. Формат: 21.09.2025 16:19 или 21.09.2025 16:19:05.");
    }
    java.time.LocalDateTime.parse(result);
    return result;
  }

  static String timeText(String iso) {
    if (iso == null) return "";
    return iso.substring(8, 10) + "." + iso.substring(5, 7) + "." + iso.substring(0, 4) + " " + iso.substring(11, 19);
  }

  static int index(String[] items, String value) {
    for (int i = 0; i < items.length; i++) if (items[i].equals(value)) return i;
    return 0;
  }

  static String[] labels(String[] keys, java.util.function.Function<String, String> name) {
    String[] result = new String[keys.length];
    for (int i = 0; i < keys.length; i++) result[i] = name.apply(keys[i]);
    return result;
  }

  static void edit(MainActivity a, String obsId) {
    Store s = a.store;
    Model.Obs obs = s.obsById.get(obsId);
    a.frame("Реквизиты замера", a.obsTitle(obs) + " · " + MainActivity.date(obs.time), "grades");
    a.note("Меняются только реквизиты; числа, нормы и статусы сверки не затрагиваются. Каждое изменение с причиной попадает в историю. "
      + "Неизвестное оставляйте неизвестным.");
    List<String> gradeIds = new ArrayList<>();
    List<String> gradeNames = new ArrayList<>();
    gradeIds.add(null);
    gradeNames.add("Марка неизвестна");
    for (Model.Grade grade : s.grades) { gradeIds.add(grade.id); gradeNames.add(grade.name); }
    Spinner grade = a.select("Марка", gradeNames.toArray(new String[0]), Math.max(0, gradeIds.indexOf(obs.grade)));
    List<String> origins = new ArrayList<>(java.util.Arrays.asList(GRADE_ORIGINS));
    if (!origins.contains(obs.gradeOrigin)) origins.add(0, obs.gradeOrigin);
    String[] originKeys = origins.toArray(new String[0]);
    Spinner gradeOrigin = a.select("Источник определения марки", labels(originKeys, MainActivity::gradeOrigin), origins.indexOf(obs.gradeOrigin));
    Spinner line = a.select("Линия", LINES, obs.line == null ? 0 : index(LINES, obs.line));
    EditText time = a.input("Время (ДД.ММ.ГГГГ ЧЧ:ММ:СС, пусто — неизвестно)", timeText(obs.time), InputType.TYPE_CLASS_DATETIME);
    Spinner timeOrigin = a.select("Откуда время", labels(TIME_ORIGINS, MainActivity::timeOrigin), index(TIME_ORIGINS, obs.timeOrigin));
    Spinner a103 = a.select("А-103", labels(EQUIPMENT, MainActivity::equipment), index(EQUIPMENT, obs.a103));
    Spinner ab124 = a.select("АБ-124", labels(EQUIPMENT, MainActivity::equipment), index(EQUIPMENT, obs.ab124));
    Spinner suutp = a.select("СУУТП", labels(SUUTP, MainActivity::equipment), index(SUUTP, obs.suutp));
    Spinner steady = a.select("Режим", new String[]{"не указано", "устойчивый режим", "переход"},
      obs.steady == null ? 0 : obs.steady ? 1 : 2);
    List<Model.Doc> docs = new ArrayList<>();
    List<String> docNames = new ArrayList<>();
    docs.add(null);
    docNames.add("не указана");
    for (Model.Doc doc : s.docs) {
      docs.add(doc);
      docNames.add(s.gradeName(doc.grade) + " · " + doc.version + " (с " + MainActivity.date(doc.validFrom) + ")");
    }
    Spinner doc = a.select("Техкарта", docNames.toArray(new String[0]), Math.max(0, docs.indexOf(obs.doc == null ? null : s.docById.get(obs.doc))));
    EditText recipe12 = a.input("Версия рецептуры зон 1–2", obs.recipe12, InputType.TYPE_CLASS_TEXT);
    EditText recipe3 = a.input("Версия рецептуры зоны 3", obs.recipe3, InputType.TYPE_CLASS_TEXT);
    EditText screen = a.input("Тип экрана", obs.screen, InputType.TYPE_CLASS_TEXT);
    Spinner excluded = a.select("Участие в расчётах", new String[]{"используется", "исключён из расчётов (дубль, ошибка атрибуции)"}, obs.excluded ? 1 : 0);
    EditText reason = a.input("Причина изменения", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    a.buttons("Новая марка…", () -> a.prompt("Новая марка", "Название марки", "", name -> {
      if (name.isEmpty()) return;
      a.save(() -> s.addGrade(name), a::refresh);
    }), null, null);
    a.buttons("Сохранить", () -> {
      try {
        String why = reason.getText().toString().trim();
        if (why.isEmpty()) throw new IllegalArgumentException("Укажите причину изменения.");
        Map<String, Object> changes = new LinkedHashMap<>();
        String newGrade = gradeIds.get(grade.getSelectedItemPosition());
        String newTime = parseTime(time.getText().toString());
        Model.Doc newDoc = docs.get(doc.getSelectedItemPosition());
        if (newDoc != null) {
          if (!newDoc.grade.equals(newGrade)) throw new IllegalArgumentException("Техкарта относится к другой марке.");
          String day = newTime == null ? null : newTime.substring(0, 10);
          if (day != null && (newDoc.validFrom.compareTo(day) > 0 || newDoc.validTo != null && newDoc.validTo.compareTo(day) < 0)) {
            throw new IllegalArgumentException("Техкарта " + newDoc.version + " не действовала на дату замера.");
          }
        }
        put(changes, "grade", obs.grade, newGrade);
        put(changes, "grade_origin", obs.gradeOrigin, originKeys[gradeOrigin.getSelectedItemPosition()]);
        put(changes, "line", obs.line, line.getSelectedItemPosition() == 0 ? null : LINES[line.getSelectedItemPosition()]);
        put(changes, "time", obs.time, newTime);
        put(changes, "time_origin", obs.timeOrigin, newTime == null ? "unknown" : TIME_ORIGINS[timeOrigin.getSelectedItemPosition()]);
        put(changes, "a103", obs.a103, EQUIPMENT[a103.getSelectedItemPosition()]);
        put(changes, "ab124", obs.ab124, EQUIPMENT[ab124.getSelectedItemPosition()]);
        put(changes, "suutp", obs.suutp, SUUTP[suutp.getSelectedItemPosition()]);
        Boolean newSteady = steady.getSelectedItemPosition() == 0 ? null : steady.getSelectedItemPosition() == 1;
        put(changes, "steady", obs.steady, newSteady);
        put(changes, "doc", obs.doc, newDoc == null ? null : newDoc.id);
        put(changes, "recipe12", obs.recipe12, recipe12.getText().toString().trim());
        put(changes, "recipe3", obs.recipe3, recipe3.getText().toString().trim());
        put(changes, "screen", obs.screen, screen.getText().toString().trim());
        put(changes, "excluded", obs.excluded, excluded.getSelectedItemPosition() == 1);
        if (excluded.getSelectedItemPosition() == 1 && !obs.excluded) changes.put("excluded_reason", why);
        if (changes.isEmpty()) { a.back(); return; }
        a.save(() -> s.patch(obs, changes, why), a::back);
      } catch (IllegalArgumentException | java.time.DateTimeException error) {
        a.alert("Не сохранено", error.getMessage());
      }
    }, "Отмена", a::back);
  }

  private static void put(Map<String, Object> changes, String field, Object before, Object after) {
    boolean same = before == null ? after == null : before.equals(after);
    if (!same) changes.put(field, after);
  }

  // ---------------------------------------------------------------- new value

  /** Form state kept while the operator visits the parameter list or the photo. */
  static final class Draft {
    Model.Param param;
    String number = "", unit = "", reason = "";
    int state, source = -1, status;
    float[] region;
  }

  static void addValue(MainActivity a, String obsId, Draft draft) {
    Store s = a.store;
    Model.Obs obs = s.obsById.get(obsId);
    a.frame("Новое значение", a.obsTitle(obs) + " · " + MainActivity.date(obs.time), "grades");
    a.note("Вводите число так, как оно видно на экране. Если поле пустое или не читается — выберите «пропуск» или «ошибка исходника», "
      + "не подставляйте ноль. Такое же число с другого фото добавит фото к существующему значению; другое число не перезапишет прежнее.");
    a.buttons(draft.param == null ? "Выбрать параметр" : "Параметр: " + draft.param.name, () -> a.go(() -> pickParam(a, "", param -> {
      draft.param = param;
      draft.unit = param.unit;
      a.back();
    })), null, null);
    if (draft.param != null) {
      a.note("Тип: " + MainActivity.type(draft.param.type) + (draft.param.tag.isEmpty() ? "" : " · тег " + draft.param.tag)
        + " · единица в словаре: " + (draft.param.unit.isEmpty() ? "не установлена" : draft.param.unit));
    }
    EditText number = a.numberInput("Число", draft.number);
    Spinner state = a.select("Что на исходнике", new String[]{"Число", "Пропуск (поле пустое)", "Ошибка исходника"}, draft.state);
    EditText unit = a.input("Единица" + (draft.param != null && !draft.param.unit.isEmpty() ? " (из словаря)" : " — укажите, если видна на экране"),
      draft.unit, InputType.TYPE_CLASS_TEXT);
    if (draft.param != null && !draft.param.unit.isEmpty()) unit.setEnabled(false);
    List<String> photoIds = new ArrayList<>();
    List<String> photoNames = new ArrayList<>();
    photoIds.add(null);
    photoNames.add("Без фото");
    for (String id : obs.sources) {
      Model.Source source = s.sourceById.get(id);
      if (source != null && source.isImage()) { photoIds.add(id); photoNames.add(source.name); }
    }
    if (draft.source < 0) draft.source = photoIds.size() > 1 ? 1 : 0;
    Spinner photo = a.select("Фото-источник", photoNames.toArray(new String[0]), draft.source);
    Spinner status = a.select("Статус", new String[]{"Требует сверки", "Подтвердить — сверено с фото"}, draft.status);
    EditText reason = a.input("Комментарий", draft.reason, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    Runnable keep = () -> {
      draft.number = number.getText().toString();
      draft.state = state.getSelectedItemPosition();
      draft.unit = unit.getText().toString().trim();
      draft.source = photo.getSelectedItemPosition();
      draft.status = status.getSelectedItemPosition();
      draft.reason = reason.getText().toString().trim();
    };
    for (EditText field : new EditText[]{number, unit, reason}) {
      field.addTextChangedListener(new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable text) { keep.run(); }
      });
    }
    a.buttons(draft.region == null ? "Отметить область на фото" : "Область отмечена — изменить", () -> {
      keep.run();
      String id = photoIds.get(photo.getSelectedItemPosition());
      if (id == null) { a.alert("Нет фото", "Выберите фото-источник."); return; }
      a.go(() -> PhotoScreens.view(a, id, draft.region, region -> {
        draft.region = region;
        a.back();
      }));
    }, null, null);
    a.buttons("Сохранить", () -> {
      keep.run();
      if (draft.param == null) { a.alert("Нет параметра", "Выберите параметр из словаря или добавьте новый."); return; }
      String chosen = draft.state == 0 ? "number" : draft.state == 1 ? "missing" : "error";
      Double parsed = Calc.parse(draft.number);
      if ("number".equals(chosen) && parsed == null) { a.alert("Неверное число", "Введите число; запятая и точка допустимы."); return; }
      String sourceId = photoIds.get(draft.source);
      if (draft.status == 1 && sourceId == null) { a.alert("Нет фото", "Подтвердить можно только сверив число с фото. Выберите фото или оставьте «требует сверки»."); return; }
      a.save(() -> {
        Model.Val created = s.addValue(obs, draft.param, "number".equals(chosen) ? parsed : null, chosen, draft.unit, sourceId,
          sourceId == null ? null : draft.region, draft.status == 1, draft.reason);
        if (!Model.CONFIRMED.equals(created.status) && draft.status == 1) {
          a.alert("Отправлено на сверку", "В замере уже есть другое число этого параметра. Оба значения ждут решения в «Сверке».");
        }
      }, a::back);
    }, "Отмена", a::back);
  }

  static void pickParam(MainActivity a, String query, Consumer<Model.Param> done) {
    Store s = a.store;
    a.frame("Параметр", s.params.size() + " в словаре", "grades");
    EditText search = a.input("Поиск по названию, ключу или тегу", query, InputType.TYPE_CLASS_TEXT);
    LinearLayout list = a.vertical();
    a.page.addView(list);
    Runnable fill = () -> {
      list.removeAllViews();
      LinearLayout outer = a.page;
      a.page = list;
      a.card("Новый параметр…", "Если поля нет в словаре: ключ, название, единица и тип", () -> newParam(a, done));
      String needle = search.getText().toString().trim().toLowerCase(Locale.ROOT);
      String group = null;
      for (Model.Param param : s.params) {
        String haystack = (param.name + " " + param.key + " " + param.tag).toLowerCase(Locale.ROOT);
        if (!needle.isEmpty() && !haystack.contains(needle)) continue;
        if (!param.group.equals(group)) { group = param.group; a.section(group); }
        a.card(param.name, MainActivity.type(param.type) + " · " + (param.unit.isEmpty() ? "единица не установлена" : param.unit)
          + (param.tag.isEmpty() ? "" : " · " + param.tag) + " · " + param.key, () -> done.accept(param));
      }
      a.page = outer;
    };
    search.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable text) { fill.run(); }
    });
    fill.run();
  }

  static void newParam(MainActivity a, Consumer<Model.Param> done) {
    a.go(() -> {
      a.frame("Новый параметр", "Добавляется в словарь телефона", "grades");
      EditText key = a.input("Ключ (латиница, цифры, _)", "", InputType.TYPE_CLASS_TEXT);
      EditText name = a.input("Название", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
      EditText unit = a.input("Единица (пусто — неизвестна, в расчётах не участвует)", "", InputType.TYPE_CLASS_TEXT);
      String[] types = {Model.FACT, Model.SETPOINT, Model.OUTPUT, Model.CALC, Model.LAB};
      Spinner type = a.select("Тип", labels(types, MainActivity::type), 0);
      EditText group = a.input("Группа", "Прочие данные", InputType.TYPE_CLASS_TEXT);
      a.buttons("Добавить", () -> {
        String k = key.getText().toString().trim();
        if (!k.matches("[a-z][a-z0-9_]{1,40}")) { a.alert("Неверный ключ", "Ключ: строчная латиница, цифры и _, от 2 символов."); return; }
        if (name.getText().toString().trim().isEmpty()) { a.alert("Нет названия", "Укажите название параметра."); return; }
        a.save(() -> {
          Model.Param param = a.store.addParam(k, name.getText().toString().trim(), unit.getText().toString().trim(),
            types[type.getSelectedItemPosition()], group.getText().toString().trim());
          a.back();
          done.accept(param);
        }, null);
      }, "Отмена", a::back);
    });
  }
}
