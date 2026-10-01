package ru.tomskneftekhim.rezhim;

import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Viewing originals with SHA-256 verification and adding old photos without inventing missing details (ТЗ, раздел 7). */
final class PhotoScreens {
  private PhotoScreens() {}

  static final String[] SCREENS = {"unknown", "A_general", "panel_A329", "A_PTR_mode", "suutp", "hot_water", "tech_card"};
  static final String[] SCREEN_NAMES = {"Не указан", "Общий экран линии", "Панель А-329", "А_ПТР_режим (RulerControl)", "СУУТП", "Горячая вода", "Техкарта"};

  private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
    @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
  };

  static void thumbnail(MainActivity a, LinearLayout card, Model.Source source) {
    if (!a.store.available(source)) return;
    thumbnail(a, card, source.id, () -> a.store.open(source), source.orientation);
  }

  static void thumbnail(MainActivity a, LinearLayout card, String key, PhotoView.Opener opener, int orientation) {
    ImageView image = new ImageView(a);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackgroundColor(Color.rgb(230, 234, 230));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, a.dp(150));
    params.topMargin = a.dp(8);
    card.addView(image, params);
    Bitmap cached = THUMBS.get(key);
    if (cached != null) {
      image.setImageBitmap(cached);
      return;
    }
    a.runInBackground(() -> {
      try {
        Bitmap bitmap = PhotoView.thumbnail(opener, orientation, 640);
        THUMBS.put(key, bitmap);
        a.runLater(() -> image.setImageBitmap(bitmap));
      } catch (IOException | OutOfMemoryError ignored) {
        // A missing preview does not block opening the original.
      }
    });
  }

  /** Full-screen original. With {@code onRegion} set, the visible part can be saved as the value's region. */
  static void view(MainActivity a, String sourceId, float[] region, Consumer<float[]> onRegion) {
    Store s = a.store;
    Model.Source source = s.sourceById.get(sourceId);
    FrameLayout root = new FrameLayout(a);
    root.setBackgroundColor(Color.BLACK);
    PhotoView photo = new PhotoView(a, () -> s.open(source), source.orientation);
    root.addView(photo, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout top = a.vertical();
    top.setBackgroundColor(Color.argb(190, 0, 0, 0));
    top.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
    top.addView(a.label(source.name, 15, Color.WHITE, true));
    String exif = source.exifTime == null ? "EXIF: нет даты" : "EXIF: " + MainActivity.date(source.exifTime);
    top.addView(a.label(exif + " · " + String.format(Locale.ROOT, "%.1f", source.size / 1048576.0).replace('.', ',') + " МБ", 13,
      Color.rgb(210, 220, 214), false));
    TextView check = a.label("SHA-256: проверяется…", 13, Color.rgb(210, 220, 214), false);
    top.addView(check);
    root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    LinearLayout bottom = new LinearLayout(a);
    bottom.setOrientation(LinearLayout.HORIZONTAL);
    bottom.setBackgroundColor(Color.argb(190, 0, 0, 0));
    bottom.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
    if (onRegion != null) {
      bottom.addView(a.button("Сохранить видимую область", () -> {
        float[] visible = photo.visibleRegion();
        if (visible != null) onRegion.accept(visible);
      }), new LinearLayout.LayoutParams(0, -2, 2));
    } else {
      TextView hint = a.label("Два пальца — масштаб, двойное касание — увеличить", 12, Color.rgb(210, 220, 214), false);
      hint.setGravity(Gravity.CENTER_VERTICAL);
      bottom.addView(hint, new LinearLayout.LayoutParams(0, -1, 2));
    }
    bottom.addView(a.button("Назад", a::back), new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    a.frameRaw(root);
    if (region != null) photo.showRegion(region);
    a.runInBackground(() -> {
      String text;
      int color;
      try {
        boolean same = Store.sha256(s.open(source)).equals(source.sha256);
        text = same ? "SHA-256 совпадает с базой: " + source.sha256.substring(0, 16) + "…" : "ВНИМАНИЕ: SHA-256 не совпадает с базой — файл изменён";
        color = same ? Color.rgb(170, 230, 190) : Color.rgb(255, 150, 140);
      } catch (Exception error) {
        text = "SHA-256 не проверен: " + error.getMessage();
        color = Color.rgb(255, 150, 140);
      }
      String shown = text;
      int shownColor = color;
      a.runLater(() -> { check.setText(shown); check.setTextColor(shownColor); });
    });
  }

  // ---------------------------------------------------------------- adding photos

  static final class Incoming {
    String name, sha, exifTime, nameTime;
    long size;
    int orientation = 1;
    File file;
    Model.Source existing;
  }

  static void add(MainActivity a) {
    a.frame("Добавить фото", "Старые или новые снимки экранов", "home");
    a.note("1. Выберите одно или несколько фото.\n2. Приложение сохранит оригиналы, посчитает SHA-256 и найдёт дубли.\n"
      + "3. Дата EXIF и время в имени файла показываются отдельно — выберите, откуда брать время замера.\n"
      + "4. Марку, линию и время можно оставить неизвестными. Числа вводятся вручную по фото; распознавание текста в этой версии не выполняется.");
    a.buttons("Выбрать фото", () -> pick(a, null), null, null);
  }

  static void pickFor(MainActivity a, String obsId) { pick(a, obsId); }

  private static void pick(MainActivity a, String obsId) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("image/*");
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    a.request(intent, data -> {
      List<Uri> uris = new ArrayList<>();
      ClipData clip = data.getClipData();
      if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
      else if (data.getData() != null) uris.add(data.getData());
      if (uris.isEmpty()) return;
      a.work("Приём фото", progress -> ingest(a, uris, progress), batch -> a.go(() -> review(a, batch, obsId)));
    });
  }

  private static List<Incoming> ingest(MainActivity a, List<Uri> uris, Store.Progress progress) throws Exception {
    File incoming = new File(a.getFilesDir(), "incoming");
    Store.deleteTree(incoming);
    if (!incoming.mkdirs()) throw new IOException("Не удалось подготовить папку для фото");
    List<Incoming> result = new ArrayList<>();
    for (int i = 0; i < uris.size(); i++) {
      progress.update("Фото " + (i + 1) + " из " + uris.size() + ": копирование и SHA-256");
      Incoming item = new Incoming();
      item.name = displayName(a, uris.get(i));
      item.file = new File(incoming, "photo-" + i + extension(item.name));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = a.getContentResolver().openInputStream(uris.get(i)); FileOutputStream out = new FileOutputStream(item.file)) {
        if (in == null) throw new IOException("Не удалось открыть " + item.name);
        byte[] buffer = new byte[1 << 16];
        int count;
        while ((count = in.read(buffer)) != -1) { out.write(buffer, 0, count); digest.update(buffer, 0, count); item.size += count; }
      }
      StringBuilder hex = new StringBuilder();
      for (byte b : digest.digest()) hex.append(String.format("%02x", b));
      item.sha = hex.toString();
      item.existing = a.store.sourceBySha.get(item.sha);
      for (Incoming earlier : result) if (earlier.sha.equals(item.sha)) item.existing = earlier.existing;
      try {
        ExifInterface exif = new ExifInterface(item.file.getPath());
        String raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        if (raw == null) raw = exif.getAttribute(ExifInterface.TAG_DATETIME);
        if (raw != null && raw.matches("\\d{4}:\\d\\d:\\d\\d \\d\\d:\\d\\d:\\d\\d")) {
          item.exifTime = raw.substring(0, 4) + "-" + raw.substring(5, 7) + "-" + raw.substring(8, 10) + "T" + raw.substring(11);
        }
        item.orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
      } catch (IOException ignored) {
        // No EXIF: the time stays unknown unless the operator enters it.
      }
      Matcher name = Pattern.compile("(20\\d\\d)(\\d\\d)(\\d\\d)_(\\d\\d)(\\d\\d)(\\d\\d)").matcher(item.name);
      if (name.find()) {
        item.nameTime = name.group(1) + "-" + name.group(2) + "-" + name.group(3) + "T" + name.group(4) + ":" + name.group(5) + ":" + name.group(6);
      }
      result.add(item);
    }
    return result;
  }

  static String displayName(MainActivity a, Uri uri) {
    try (Cursor cursor = a.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
    } catch (Exception ignored) {
      // Fall back to the last path segment.
    }
    String segment = uri.getLastPathSegment();
    return segment == null ? "photo.jpg" : segment;
  }

  private static String extension(String name) {
    int dot = name.lastIndexOf('.');
    String ext = dot < 0 ? ".jpg" : name.substring(dot).toLowerCase(Locale.ROOT);
    return ext.matches("\\.[a-z0-9]{1,5}") ? ext : ".jpg";
  }

  private static String media(String name) {
    String ext = extension(name);
    return ".png".equals(ext) ? "image/png" : ".webp".equals(ext) ? "image/webp" : ".heic".equals(ext) ? "image/heic" : "image/jpeg";
  }

  static void review(MainActivity a, List<Incoming> batch, String obsId) {
    Store s = a.store;
    List<Incoming> fresh = new ArrayList<>();
    for (Incoming item : batch) if (item.existing == null && !containsSha(fresh, item.sha)) fresh.add(item);
    Model.Obs target = obsId == null ? null : s.obsById.get(obsId);
    a.frame("Новые фото", batch.size() + " выбрано · новых " + fresh.size() + " · дублей " + (batch.size() - fresh.size()), "home");
    for (Incoming item : batch) {
      StringBuilder details = new StringBuilder();
      details.append(String.format(Locale.ROOT, "%.1f", item.size / 1048576.0).replace('.', ',')).append(" МБ · SHA-256 ")
        .append(item.sha, 0, 16).append("…");
      details.append("\nEXIF: ").append(item.exifTime == null ? "даты нет" : MainActivity.date(item.exifTime));
      if (item.nameTime != null) details.append("\nВремя в имени файла: ").append(MainActivity.date(item.nameTime));
      if (item.existing != null) {
        List<String> where = new ArrayList<>();
        for (Model.Obs obs : s.observations) if (obs.sources.contains(item.existing.id)) where.add(a.obsTitle(obs) + ", " + MainActivity.date(obs.time));
        details.append("\nДУБЛЬ: этот файл уже есть в базе как «").append(item.existing.name).append("»")
          .append(where.isEmpty() ? "" : " — " + android.text.TextUtils.join("; ", where)).append(". Повторно не добавляется.");
      } else if (!fresh.contains(item)) {
        details.append("\nДУБЛЬ: этот же файл выбран дважды.");
      }
      LinearLayout card = a.card(item.name, details.toString(), null);
      if (item.existing != null) a.badge(card, "Уже в базе", MainActivity.REVIEW);
      thumbnail(a, card, "incoming:" + item.sha, () -> new java.io.FileInputStream(item.file), item.orientation);
    }
    if (fresh.isEmpty()) {
      a.note("Новых файлов нет: все выбранные фото уже есть в базе. Новый независимый замер не создаётся.");
      a.buttons("Готово", a::back, null, null);
      return;
    }
    if (target != null) {
      a.note("Фото будут прикреплены к замеру " + a.obsTitle(target) + ", " + MainActivity.date(target.time) + ".");
      a.buttons("Прикрепить", () -> a.save(() -> {
        for (Incoming item : fresh) {
          Model.Source source = keep(a, item, "unknown", "");
          s.attachSource(target, source.id, "Фото прикреплено на телефоне");
        }
      }, a::back), "Отмена", a::back);
      return;
    }
    List<String> gradeIds = new ArrayList<>();
    List<String> gradeNames = new ArrayList<>();
    gradeIds.add(null);
    gradeNames.add("Марка неизвестна");
    for (Model.Grade grade : s.grades) { gradeIds.add(grade.id); gradeNames.add(grade.name); }
    a.section("Реквизиты замера");
    Spinner grade = a.select("Марка", gradeNames.toArray(new String[0]), 0);
    String[] gradeOrigins = {"user", "folder", "unknown"};
    Spinner gradeOrigin = a.select("Как определена марка", ObsScreens.labels(gradeOrigins, MainActivity::gradeOrigin), 0);
    Spinner line = a.select("Линия", ObsScreens.LINES, 0);
    boolean anyExif = false;
    for (Incoming item : fresh) anyExif |= item.exifTime != null;
    String[] modes = {"Неизвестно", "EXIF каждого фото", "С экрана — ввести ниже", "Ввести вручную ниже"};
    Spinner mode = a.select("Откуда время замера", modes, 0);
    EditText time = a.input("Время (ДД.ММ.ГГГГ ЧЧ:ММ:СС) — для «с экрана» и «вручную»", "", InputType.TYPE_CLASS_DATETIME);
    EditText period = a.input("Приблизительный период, если время неизвестно", "", InputType.TYPE_CLASS_TEXT);
    if (!anyExif) a.note("У выбранных фото нет даты EXIF — время будет неизвестным, пока вы его не введёте.");
    else a.note("Время EXIF — это момент съёмки телефоном, а не время на экране и не момент смены марки.");
    Spinner screen = a.select("Тип экрана", SCREEN_NAMES, 0);
    Spinner grouping = a.select("Как объединить", new String[]{"Все фото — один замер", "Каждое фото — отдельный замер"}, 0);
    EditText comment = a.input("Комментарий", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    a.buttons("Новая марка…", () -> a.prompt("Новая марка", "Название марки", "", name -> {
      if (!name.isEmpty()) a.save(() -> s.addGrade(name), a::refresh);
    }), null, null);
    a.buttons("Сохранить", () -> {
      try {
        String gradeId = gradeIds.get(grade.getSelectedItemPosition());
        String lineValue = line.getSelectedItemPosition() == 0 ? null : ObsScreens.LINES[line.getSelectedItemPosition()];
        int chosenMode = mode.getSelectedItemPosition();
        String typed = ObsScreens.parseTime(time.getText().toString());
        if ((chosenMode == 2 || chosenMode == 3) && typed == null) throw new IllegalArgumentException("Введите время или выберите «Неизвестно».");
        boolean separate = grouping.getSelectedItemPosition() == 1;
        Plan plan = new Plan();
        plan.gradeId = gradeId;
        plan.gradeOrigin = gradeId == null ? "unknown" : gradeOrigins[gradeOrigin.getSelectedItemPosition()];
        plan.line = lineValue;
        plan.mode = chosenMode;
        plan.typed = typed;
        plan.period = period.getText().toString().trim();
        plan.screen = SCREENS[screen.getSelectedItemPosition()];
        plan.comment = comment.getText().toString().trim();
        List<List<Incoming>> groups = new ArrayList<>();
        if (separate) for (Incoming item : fresh) groups.add(Collections.singletonList(item));
        else groups.add(fresh);
        List<String> mismatches = gradeMismatches(s, fresh, gradeId);
        if (mismatches.isEmpty()) {
          createNext(a, plan, groups, 0, new ArrayList<>());
        } else {
          plan.gradeOrigin = "user";
          a.confirm("Марка в имени файла другая", android.text.TextUtils.join("\n", mismatches)
              + "\n\nПодтвердите, что замер относится к марке «" + s.gradeName(gradeId) + "».", "Подтверждаю",
            () -> createNext(a, plan, groups, 0, new ArrayList<>()));
        }
      } catch (IllegalArgumentException | java.time.DateTimeException error) {
        a.alert("Не сохранено", error.getMessage());
      }
    }, "Отмена", a::back);
  }

  /** File names that mention another grade than the chosen one (ТЗ 4.1: расхождение имени файла и выбранной марки). */
  static List<String> gradeMismatches(Store s, List<Incoming> items, String chosen) {
    List<String> result = new ArrayList<>();
    for (Incoming item : items) {
      for (Model.Grade grade : s.grades) {
        if (grade.id.equals(chosen)) continue;
        for (String alias : (grade.name + "," + grade.aliases).split("[,;]+")) {
          String[] parts = alias.replaceAll("[^0-9]+", " ").trim().split(" ");
          if (alias.replaceAll("[^0-9]", "").length() < 5) continue;
          Pattern code = Pattern.compile("(?<![0-9])" + String.join("[^0-9]?", parts) + "(?![0-9])");
          if (code.matcher(item.name).find()) {
            result.add("«" + item.name + "» похоже на марку " + grade.name + ".");
            break;
          }
        }
      }
    }
    return result;
  }

  private static boolean containsSha(List<Incoming> items, String sha) {
    for (Incoming item : items) if (item.sha.equals(sha)) return true;
    return false;
  }

  private static final class Plan {
    String gradeId, gradeOrigin, line, typed, period, screen, comment;
    int mode;
  }

  private static String timeFor(Plan plan, List<Incoming> group) {
    if (plan.mode == 1) {
      for (Incoming item : group) if (item.exifTime != null) return item.exifTime;
      return null;
    }
    return plan.mode == 2 || plan.mode == 3 ? plan.typed : null;
  }

  /** Creates observations one group at a time, asking before joining an existing observation of the same moment. */
  private static void createNext(MainActivity a, Plan plan, List<List<Incoming>> groups, int index, List<String> created) {
    Store s = a.store;
    if (index >= groups.size()) {
      a.back();
      if (created.size() == 1) {
        a.go(() -> ObsScreens.show(a, created.get(0)));
        a.alert("Фото сохранены", "Теперь добавьте значения с фото: «Добавить значение». Неясные поля можно оставить неизвестными.");
      } else {
        a.alert("Фото сохранены", "Создано или дополнено замеров: " + created.size() + ". Они в списке «Все замеры» и в разделе «Сверка».");
      }
      return;
    }
    List<Incoming> group = groups.get(index);
    String time = timeFor(plan, group);
    Model.Obs match = null;
    if (time != null && plan.line != null) {
      for (Model.Obs obs : s.observations) {
        if (!plan.line.equals(obs.line) || !Analysis.timeKnown(obs)) continue;
        boolean gradeFits = plan.gradeId == null || obs.grade == null || plan.gradeId.equals(obs.grade);
        if (gradeFits && Math.abs(Analysis.hoursBetween(obs.time, time)) * 3600 <= 120) { match = obs; break; }
      }
    }
    Runnable createNew = () -> a.save(() -> {
      List<String> ids = new ArrayList<>();
      for (Incoming item : group) ids.add(keep(a, item, plan.screen, plan.comment).id);
      JSONObject fields = new JSONObject().put("grade", plan.gradeId == null ? JSONObject.NULL : plan.gradeId)
        .put("grade_origin", plan.gradeOrigin).put("grade_note", "Указано при добавлении фото на телефоне.")
        .put("line", plan.line == null ? JSONObject.NULL : plan.line).put("time", time == null ? JSONObject.NULL : time)
        .put("time_origin", time == null ? "unknown" : plan.mode == 1 ? "exif" : plan.mode == 2 ? "screen" : "manual")
        .put("time_note", time == null ? (plan.period.isEmpty() ? "" : "Приблизительный период: " + plan.period)
          : plan.mode == 1 ? "EXIF фото " + group.get(0).name + " — момент съёмки, не время экрана." : "")
        .put("a103", "unknown").put("ab124", "unknown").put("suutp", "unknown").put("steady", JSONObject.NULL)
        .put("screen", "unknown".equals(plan.screen) ? "" : plan.screen)
        .put("notes", plan.comment.isEmpty() ? new JSONArray() : new JSONArray().put(plan.comment));
      created.add(s.addObservation(fields, ids, "Фото добавлены на телефоне.").id);
    }, () -> createNext(a, plan, groups, index + 1, created));
    if (match == null) {
      createNew.run();
      return;
    }
    Model.Obs existing = match;
    new android.app.AlertDialog.Builder(a).setTitle("Найден замер того же момента")
      .setMessage(a.obsTitle(existing) + ", " + MainActivity.date(existing.time) + " (" + MainActivity.timeOrigin(existing.timeOrigin)
        + "). Добавить фото к нему? Реквизиты существующего замера не меняются; разные числа одного параметра попадут на сверку.")
      .setPositiveButton("Добавить к нему", (dialog, which) -> a.save(() -> {
        for (Incoming item : group) s.attachSource(existing, keep(a, item, plan.screen, plan.comment).id, "Фото того же момента и линии");
        created.add(existing.id);
      }, () -> createNext(a, plan, groups, index + 1, created)))
      .setNegativeButton("Создать новый", (dialog, which) -> createNew.run())
      .setCancelable(false).show();
  }

  /** Moves a received file into permanent storage and registers it as a source. */
  private static Model.Source keep(MainActivity a, Incoming item, String screen, String note) throws Exception {
    Model.Source known = a.store.sourceBySha.get(item.sha);
    if (known != null) return known;
    File folder = new File(a.getFilesDir(), "sources");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Нет папки для фото");
    String relative = "sources/" + item.sha + extension(item.name);
    File target = new File(a.getFilesDir(), relative);
    if (!target.exists() && !item.file.renameTo(target)) throw new IOException("Не удалось сохранить " + item.name);
    return a.store.addPhoto(item.name, item.sha, item.size, relative, media(item.name), item.exifTime, item.orientation, screen, note);
  }
}
