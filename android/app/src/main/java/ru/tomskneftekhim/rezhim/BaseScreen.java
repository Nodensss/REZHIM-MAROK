package ru.tomskneftekhim.rezhim;

import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Base version, integrity check, export (JSON, ZIP with photos) and import with a preview (ТЗ, раздел 9). */
final class BaseScreen {
  private BaseScreen() {}

  static void show(MainActivity a) {
    Store s = a.store;
    JSONObject counts = s.snapshot.optJSONObject("counts");
    a.frame("База и JSON", "Ревизия " + s.snapshot.optInt("revision") + (s.baseOrigin.equals("builtin") ? " · встроенная" : " · из файла"), "home");
    a.note("Снимок базы: ревизия " + s.snapshot.optInt("revision") + " от " + MainActivity.date(s.snapshot.optString("built_at"))
      + "\nSHA-256 базы ПК: " + shortHash(s.snapshot.optString("database_sha256"))
      + (counts == null ? "" : "\nВ снимке: марок " + counts.optInt("grades") + ", замеров " + counts.optInt("observations")
        + ", значений " + counts.optInt("values") + ", полей вне словаря " + counts.optInt("extras") + ", фото " + counts.optInt("photos")
        + ", записей истории " + counts.optInt("history"))
      + "\nПриложение " + s.appVersion + ". Дополнения телефона хранятся отдельно от снимка и сохраняются при обновлении приложения.");
    int obs = 0, values = 0, photos = 0;
    for (Model.Obs item : s.observations) if (item.user) obs++;
    for (Model.Val item : s.values) if (item.user) values++;
    for (Model.Source item : s.sources) if (item.user) photos++;
    a.note("Добавлено на телефоне: замеров " + obs + ", значений " + values + ", фото " + photos + ", решений сверки "
      + s.layer("decisions").length() + ", правок реквизитов " + s.layer("patches").length() + ", переходов " + s.transitions.size() + ".");
    a.buttons("Проверить целостность", () -> a.work("Проверка целостности", s::checkIntegrity,
      report -> a.alert("Проверка целостности", report)), null, null);

    a.section("Выгрузка");
    a.note("JSON содержит все марки, замеры, значения всех статусов, техкарты, нормы, рецептуры, переходы, связи с источниками, "
      + "историю исправлений, единицы и контрольные суммы. Фото в JSON не входят — для них ZIP. Место сохранения выбирается в системном окне.");
    String day = LocalDate.now().toString();
    a.buttons("JSON", () -> export(a, "rezhim-" + day + ".json", false, false),
      "ZIP: JSON + добавленные фото", () -> export(a, "rezhim-" + day + "-dopolneniya.zip", true, false));
    a.buttons("ZIP: JSON + все фото (≈360 МБ)", () -> export(a, "rezhim-" + day + "-vse-foto.zip", true, true), null, null);
    a.note("Резервная копия дополнений — «ZIP: JSON + добавленные фото». Не удаляйте приложение, пока копия не сохранена: "
      + "удаление стирает данные телефона.");

    a.section("Загрузка");
    a.note("Импорт принимает выгрузку этого приложения (JSON или ZIP) и новую версию базы с ПК (снимок rezhim-android-snapshot). "
      + "Перед записью показывается итог. Локальные записи не удаляются; при расхождении ваша версия остаётся, импортированная уходит на сверку.");
    a.buttons("Импорт JSON или ZIP", () -> importFile(a), null, null);

    a.section("Версии базы");
    List<String> installed = s.installedBases();
    String active = s.activeBase();
    a.card("Встроенная в приложение" + ("builtin".equals(active) ? " · действует" : ""), "Поставляется в APK вместе с фото",
      "builtin".equals(active) ? null : () -> switchBase(a, "builtin"));
    for (String revision : installed) {
      a.card("Ревизия " + revision + (revision.equals(active) ? " · действует" : ""), "Загружена из файла",
        revision.equals(active) ? null : () -> switchBase(a, revision));
    }
    if (installed.isEmpty()) a.note("Загруженных из файла версий нет. Чтобы откатиться, выберите другую версию в этом списке.");
  }

  static String shortHash(String sha) { return sha.length() > 16 ? sha.substring(0, 16) + "…" : sha.isEmpty() ? "не указан" : sha; }

  private static void switchBase(MainActivity a, String revision) {
    a.confirm("Сменить версию базы", "Сделать действующей " + ("builtin".equals(revision) ? "встроенную базу" : "ревизию " + revision)
      + "? Дополнения телефона не изменятся.", "Сменить",
      () -> a.save(() -> a.store.activateBase(revision, "Действующая база: " + ("builtin".equals(revision) ? "встроенная" : "ревизия " + revision) + "."),
        a::refresh));
  }

  private static void export(MainActivity a, String name, boolean zip, boolean allPhotos) {
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(zip ? "application/zip" : "application/json");
    intent.putExtra(Intent.EXTRA_TITLE, name);
    a.request(intent, data -> {
      Uri uri = data.getData();
      if (uri == null) return;
      a.work("Выгрузка", progress -> {
        try (OutputStream out = a.getContentResolver().openOutputStream(uri, "w")) {
          if (out == null) throw new IllegalStateException("Не удалось открыть файл для записи");
          a.store.writeExport(out, zip, allPhotos, progress);
        }
        return name;
      }, saved -> a.save(() -> {
        a.store.log("export", null, null, "Выгрузка «" + saved + "»" + (zip ? (allPhotos ? " со всеми фото" : " с добавленными фото") : "") + ".");
        a.store.save();
      }, () -> a.alert("Сохранено", "Файл «" + saved + "» записан. Проверьте, что он появился в выбранной папке.")));
    });
  }

  private static void importFile(MainActivity a) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "application/zip", "application/octet-stream", "text/plain"});
    a.request(intent, data -> {
      Uri uri = data.getData();
      if (uri == null) return;
      String name = PhotoScreens.displayName(a, uri);
      a.work("Проверка файла", progress -> {
        try (InputStream in = a.getContentResolver().openInputStream(uri)) {
          if (in == null) throw new IllegalStateException("Не удалось открыть файл");
          return a.store.preview(in, name, progress);
        }
      }, preview -> a.go(() -> preview(a, preview)));
    });
  }

  static void preview(MainActivity a, Store.Preview preview) {
    a.frame("Предварительный итог", preview.fileName, "home");
    a.note(preview.summary);
    for (String warning : preview.warnings) a.warn(warning);
    if ("layer".equals(preview.kind)) {
      List<String[]> rows = new ArrayList<>();
      rows.add(new String[]{"Раздел", "Новых", "Совпадает", "Конфликтов"});
      for (Map.Entry<String, int[]> entry : preview.counts.entrySet()) {
        rows.add(new String[]{entry.getKey(), String.valueOf(entry.getValue()[0]), String.valueOf(entry.getValue()[1]), String.valueOf(entry.getValue()[2])});
      }
      if (rows.size() > 1) a.table(rows, null, null); else a.note("В файле нет записей дополнений.");
      if (!preview.conflicts.isEmpty()) a.warn("Конфликты (первые " + preview.conflicts.size() + "):\n" + android.text.TextUtils.join("\n", preview.conflicts));
      a.note("Фото в архиве: " + preview.files.size() + ".");
    }
    boolean nothing = "layer".equals(preview.kind) && preview.added == 0 && preview.conflicts.isEmpty();
    if (nothing) {
      a.note("Импорт ничего не изменит: все записи уже есть.");
      a.buttons("Закрыть", a::back, null, null);
      return;
    }
    a.buttons("snapshot".equals(preview.kind) ? "Загрузить эту версию базы" : "Импортировать", () -> a.work("Импорт", progress -> {
      a.store.apply(preview);
      return true;
    }, done -> {
      a.back();
      a.refresh();
      a.alert("Импорт выполнен", "snapshot".equals(preview.kind) ? "Действует загруженная версия базы. Вернуться к прежней можно в «Версии базы»."
        : "Новые записи добавлены. Конфликты, если были, ждут решения в «Сверке».");
    }), "Отмена", () -> {
      Store.deleteTree(new File(a.getFilesDir(), "import-staging"));
      a.back();
    });
  }
}
