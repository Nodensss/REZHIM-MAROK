package ru.tomskneftekhim.rezhim;

import android.widget.LinearLayout;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Queue of unconfirmed numbers, disputed grades, duplicates and import conflicts. Every decision keeps its history. */
final class ReviewScreen {
  private ReviewScreen() {}

  static void show(MainActivity a) {
    Store s = a.store;
    a.frame("Сверка", MainActivity.values(s.count(Model.REVIEW)) + " на сверке", "review");
    a.note("Подтверждайте только числа, сверенные с исходным фото. Решение и его причина сохраняются в истории; "
      + "отклонённое число не удаляется. В расчётную подсказку попадают только подтверждённые значения.");

    a.section("Спорные марки и возможные дубли");
    if (s.duplicates.isEmpty()) a.note("Совпадающих наборов показаний не найдено.");
    for (Analysis.Duplicate duplicate : s.duplicates) {
      Model.Obs left = s.obsById.get(duplicate.a), right = s.obsById.get(duplicate.b);
      LinearLayout card = a.card((duplicate.differentGrade ? "Спорная марка: " : "Возможный дубль: ")
          + s.gradeName(left.grade) + " / " + s.gradeName(right.grade),
        MainActivity.date(left.time) + " (" + MainActivity.timeOrigin(left.timeOrigin) + ") и " + MainActivity.date(right.time)
          + " (" + MainActivity.timeOrigin(right.timeOrigin) + ")\nСовпадают " + duplicate.common + " показателей. "
          + "Пока вопрос не решён, оба замера не используются как аналоги.", () -> a.go(() -> duplicate(a, duplicate)));
      a.badge(card, "Открыть и решить", MainActivity.REJECTED);
    }

    List<JSONObject> conflicts = s.openConflicts();
    if (!conflicts.isEmpty()) {
      a.section("Конфликты импорта");
      for (JSONObject conflict : conflicts) {
        LinearLayout card = a.card(conflict.optString("collection") + " · " + conflict.optString("record"),
          "Из файла «" + conflict.optString("file") + "» пришла другая версия записи. Сейчас действует версия телефона.", null);
        LinearLayout row = new LinearLayout(a);
        row.addView(a.button("Оставить мою", () -> a.save(() -> s.resolveConflict(conflict, false), a::refresh)), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(a.button("Принять из файла", () -> a.save(() -> s.resolveConflict(conflict, true), a::refresh)), new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
      }
    }

    List<Model.Obs> withConflicts = new ArrayList<>();
    for (Model.Obs obs : s.observations) if (!s.conflicts(obs.id).isEmpty()) withConflicts.add(obs);
    if (!withConflicts.isEmpty()) {
      a.section("Разные числа одного параметра");
      for (Model.Obs obs : withConflicts) {
        a.card(a.obsTitle(obs) + " · " + MainActivity.date(obs.time), s.conflicts(obs.id).size() + " параметров с разными числами",
          () -> a.go(() -> ObsScreens.show(a, obs.id)));
      }
    }

    a.section("Требуют сверки");
    Map<String, Integer> pending = new HashMap<>();
    for (Model.Val value : s.values) if (Model.REVIEW.equals(value.status)) pending.merge(value.obs, 1, Integer::sum);
    int shown = 0;
    for (Model.Obs obs : s.observationsOf(null)) {
      Integer count = pending.get(obs.id);
      if (count == null) continue;
      shown++;
      a.card(a.obsTitle(obs) + " · " + MainActivity.date(obs.time), count + " на сверке · фото: " + ObsScreens.photoCount(s, obs),
        () -> a.go(() -> observation(a, obs.id)));
    }
    if (shown == 0) a.note("Все значения сверены.");
  }

  /** Values of one observation awaiting review, with quick decisions. */
  static void observation(MainActivity a, String obsId) {
    Store s = a.store;
    Model.Obs obs = s.obsById.get(obsId);
    List<Model.Val> pending = new ArrayList<>();
    for (Model.Val value : s.values(obsId)) if (Model.REVIEW.equals(value.status)) pending.add(value);
    a.frame("Сверка замера", a.obsTitle(obs) + " · " + MainActivity.date(obs.time) + " · " + pending.size() + " на сверке", "review");
    List<String> photos = new ArrayList<>();
    for (String id : obs.sources) {
      Model.Source source = s.sourceById.get(id);
      if (source != null && source.isImage()) photos.add(id);
    }
    if (photos.isEmpty()) a.warn("У замера нет фото. Подтверждать числа без исходника нельзя — только отклонить или оставить на сверке.");
    else {
      String[] names = new String[photos.size()];
      for (int i = 0; i < names.length; i++) names[i] = s.sourceById.get(photos.get(i)).name;
      a.buttons("Открыть фото (" + photos.size() + ")", () -> {
        if (photos.size() == 1) a.go(() -> PhotoScreens.view(a, photos.get(0), null, null));
        else a.choose("Фото замера", names, which -> a.go(() -> PhotoScreens.view(a, photos.get(which), null, null)));
      }, "Весь замер", () -> a.go(() -> ObsScreens.show(a, obsId)));
    }
    if (pending.isEmpty()) a.note("В этом замере всё сверено.");
    for (Model.Val value : pending) {
      Model.Param param = value.param == null ? null : s.paramByKey.get(value.param);
      String tag = param != null && !param.tag.isEmpty() ? " · " + param.tag : "";
      LinearLayout card = a.card(s.paramName(value), a.reading(value) + tag + "\n" + MainActivity.type(value.type)
        + (value.raw == null ? "" : " · распознано «" + value.raw + "»"), () -> a.go(() -> ObsScreens.value(a, value.id)));
      LinearLayout row = new LinearLayout(a);
      String photo = value.photos.isEmpty() ? (photos.isEmpty() ? null : photos.get(0)) : value.photos.get(0);
      if (photo != null) {
        row.addView(a.button("Подтвердить", () -> ObsScreens.decide(a, value, Model.CONFIRMED,
          "Сверено с фото " + s.sourceById.get(photo).name)), new LinearLayout.LayoutParams(0, -2, 1));
      }
      row.addView(a.button("Отклонить", () -> ObsScreens.decide(a, value, Model.REJECTED, "Число не совпадает с исходником")),
        new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(a.button("Исправить", () -> a.go(() -> ObsScreens.correct(a, value.id))), new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(row);
    }
  }

  static void duplicate(MainActivity a, Analysis.Duplicate duplicate) {
    Store s = a.store;
    Model.Obs left = s.obsById.get(duplicate.a), right = s.obsById.get(duplicate.b);
    a.frame(duplicate.differentGrade ? "Спорная марка" : "Возможный дубль", s.gradeName(left.grade) + " / " + s.gradeName(right.grade), "review");
    a.warn("У двух замеров совпадают " + duplicate.common + " показателей. Возможно, это один и тот же экран, приписанный разным маркам, "
      + "или повторно распознанное фото. Пока вопрос не решён, оба замера показаны в архиве, но исключены из поиска аналогов.");
    a.card("А: " + a.obsTitle(left), a.obsLine(left) + "\n" + gradeSource(left), () -> a.go(() -> ObsScreens.show(a, left.id)));
    a.card("Б: " + a.obsTitle(right), a.obsLine(right) + "\n" + gradeSource(right), () -> a.go(() -> ObsScreens.show(a, right.id)));
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[]{"Параметр", "А", "Б"});
    Map<String, Double> l = Analysis.numbers(s.values(left.id)), r = Analysis.numbers(s.values(right.id));
    for (String key : new TreeSet<>(l.keySet())) {
      if (!r.containsKey(key)) continue;
      Model.Param param = s.paramByKey.get(key);
      rows.add(new String[]{param == null ? key : param.name, Calc.fmt(l.get(key), 0), Calc.fmt(r.get(key), 0)});
    }
    a.table(rows, null, null);
    a.section("Решение");
    a.buttons("Это разные замеры", () -> resolve(a, duplicate, "разные замеры, совпадение случайно", null),
      "Сравнить", () -> { a.setCompareSlot("a", left.id); a.setCompareSlot("b", right.id); a.home(() -> CompareScreen.show(a)); });
    a.buttons("Исключить А", () -> resolve(a, duplicate, "дубль: исключён замер А", left),
      "Исключить Б", () -> resolve(a, duplicate, "дубль: исключён замер Б", right));
    a.note("Чтобы исправить марку, откройте замер и измените реквизиты — прежняя марка сохранится в истории.");
  }

  private static String gradeSource(Model.Obs obs) {
    return obs.gradeNote.isEmpty() ? "Марка " + MainActivity.gradeOrigin(obs.gradeOrigin) + "." : obs.gradeNote;
  }

  private static void resolve(MainActivity a, Analysis.Duplicate duplicate, String resolution, Model.Obs exclude) {
    a.prompt("Решение", resolution + ". Укажите основание.", "", reason -> {
      if (reason.isEmpty()) { a.alert("Нужна причина", "Укажите, на основании чего принято решение."); return; }
      a.save(() -> {
        if (exclude != null) {
          Map<String, Object> changes = new HashMap<>();
          changes.put("excluded", true);
          changes.put("excluded_reason", resolution + ": " + reason);
          a.store.patch(exclude, changes, reason);
        }
        a.store.resolveDuplicate(duplicate, resolution, reason);
      }, a::back);
    });
  }
}
