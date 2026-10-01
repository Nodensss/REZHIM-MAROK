package ru.tomskneftekhim.rezhim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Offline archive of grades, observations and photos; one activity with a stack of screens. */
public class MainActivity extends Activity {
  static final int DARK = Color.rgb(20, 63, 53);
  static final int INK = Color.rgb(25, 45, 39);
  static final int MUTED = Color.rgb(93, 110, 102);
  static final int PALE = Color.rgb(243, 246, 242);
  static final int BORDER = Color.rgb(215, 225, 217);
  static final int REVIEW = Color.rgb(153, 104, 21);
  static final int REJECTED = Color.rgb(160, 52, 50);
  static final int CONFIRMED = Color.rgb(23, 108, 68);
  static final int WARN_BG = Color.rgb(253, 244, 225);
  static final int WARN_BORDER = Color.rgb(232, 201, 140);
  static final int ACCENT_BG = Color.rgb(230, 240, 230);

  Store store;
  LinearLayout page;
  private ScrollView scroll;
  private final ExecutorService background = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ArrayDeque<Screen> stack = new ArrayDeque<>();
  private Screen current;
  private final Map<Integer, Consumer<Intent>> results = new HashMap<>();
  private int nextRequest = 100;

  private static final class Screen {
    final Runnable render;
    int scroll;

    Screen(Runnable render) { this.render = render; }
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    frame("Режимы марок", "Загрузка встроенной базы…", null);
    note("Открывается полный снимок базы и список фотографий. Сеть не нужна.");
    background.execute(() -> {
      try {
        Store opened = Store.open(this);
        main.post(() -> {
          store = opened;
          home(() -> ArchiveScreens.home(this));
        });
      } catch (Exception error) {
        main.post(() -> {
          frame("Ошибка чтения базы", "Не удалось открыть встроенный снимок", null);
          note(error.getClass().getSimpleName() + ": " + error.getMessage());
          buttons("Повторить", this::recreate, null, null);
        });
      }
    });
  }

  // ------------------------------------------------------------ navigation

  void go(Runnable render) {
    if (current != null) {
      current.scroll = scroll == null ? 0 : scroll.getScrollY();
      stack.push(current);
    }
    current = new Screen(render);
    render.run();
  }

  void home(Runnable render) {
    stack.clear();
    current = new Screen(render);
    render.run();
  }

  void refresh() {
    if (current == null) return;
    int y = scroll == null ? 0 : scroll.getScrollY();
    current.render.run();
    restoreScroll(y);
  }

  void back() {
    if (stack.isEmpty()) {
      finish();
      return;
    }
    current = stack.pop();
    current.render.run();
    restoreScroll(current.scroll);
  }

  private void restoreScroll(int y) {
    if (scroll != null) scroll.post(() -> scroll.scrollTo(0, y));
  }

  @Override public void onBackPressed() {
    if (store == null) super.onBackPressed(); else back();
  }

  // ------------------------------------------------------------ frame

  int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

  GradientDrawable box(int color, int stroke) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(dp(12));
    if (stroke != Color.TRANSPARENT) shape.setStroke(dp(1), stroke);
    return shape;
  }

  TextView label(String text, float size, int color, boolean bold) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    view.setLineSpacing(dp(2), 1f);
    return view;
  }

  LinearLayout vertical() {
    LinearLayout view = new LinearLayout(this);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  void gap(ViewGroup parent, int height) {
    parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  /** Header, scrolling page and the bottom tabs. */
  void frame(String title, String subtitle, String tab) {
    LinearLayout root = vertical();
    root.setBackgroundColor(PALE);
    setContentView(root);
    LinearLayout header = vertical();
    header.setBackgroundColor(DARK);
    header.setPadding(dp(18), dp(14), dp(18), dp(14));
    header.addView(label("УПЭ · РЕЖИМЫ МАРОК · " + (store == null ? "" : store.appVersion), 12, Color.rgb(188, 217, 198), true));
    gap(header, 4);
    header.addView(label(title, 23, Color.WHITE, true));
    if (subtitle != null && !subtitle.isEmpty()) {
      gap(header, 4);
      header.addView(label(subtitle, 14, Color.rgb(225, 238, 229), false));
    }
    root.addView(header);
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    page = vertical();
    page.setPadding(dp(14), dp(14), dp(14), dp(24));
    scroll.addView(page);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (store == null) return;
    LinearLayout tabs = new LinearLayout(this);
    tabs.setOrientation(LinearLayout.HORIZONTAL);
    tabs.setBackgroundColor(Color.WHITE);
    tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
    tab(tabs, "Главная", "home".equals(tab), () -> home(() -> ArchiveScreens.home(this)));
    tab(tabs, "Марки", "grades".equals(tab), () -> home(() -> ArchiveScreens.grades(this, "")));
    tab(tabs, "Сравнение", "compare".equals(tab), () -> home(() -> CompareScreen.show(this)));
    tab(tabs, "Переходы", "transitions".equals(tab), () -> home(() -> TransitionScreens.list(this)));
    tab(tabs, "Сверка", "review".equals(tab), () -> home(() -> ReviewScreen.show(this)));
    root.addView(tabs);
  }

  private void tab(LinearLayout row, String title, boolean active, Runnable action) {
    TextView view = label(title, 12, active ? DARK : MUTED, active);
    view.setGravity(Gravity.CENTER);
    view.setBackground(box(active ? ACCENT_BG : Color.WHITE, Color.TRANSPARENT));
    view.setContentDescription("Раздел «" + title + "»");
    view.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
    params.setMargins(dp(1), 0, dp(1), 0);
    row.addView(view, params);
  }

  /** A full-screen view without the scrolling page, e.g. the photo viewer. */
  void frameRaw(View content) {
    scroll = null;
    page = null;
    setContentView(content);
  }

  // ------------------------------------------------------------ page blocks

  private LinearLayout.LayoutParams blockParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(9);
    return params;
  }

  LinearLayout card(String title, String details, Runnable action) {
    LinearLayout item = vertical();
    item.setBackground(box(Color.WHITE, BORDER));
    item.setPadding(dp(14), dp(12), dp(14), dp(12));
    item.addView(label(title, 16, INK, true));
    if (details != null && !details.isEmpty()) {
      gap(item, 4);
      item.addView(label(details, 14, MUTED, false));
    }
    if (action != null) {
      item.setClickable(true);
      item.setFocusable(true);
      item.setContentDescription(title + ". " + details);
      item.setOnClickListener(v -> action.run());
    }
    page.addView(item, blockParams());
    return item;
  }

  TextView badge(LinearLayout parent, String text, int color) {
    TextView view = label(text, 13, color, true);
    view.setPadding(0, dp(5), 0, 0);
    parent.addView(view);
    return view;
  }

  void section(String title) {
    TextView view = label(title, 17, INK, true);
    view.setPadding(dp(2), dp(12), dp(2), dp(8));
    page.addView(view);
  }

  TextView note(String text) {
    if (text == null || text.trim().isEmpty()) return null;
    TextView view = label(text, 14, INK, false);
    view.setBackground(box(Color.WHITE, BORDER));
    view.setPadding(dp(14), dp(11), dp(14), dp(11));
    view.setTextIsSelectable(true);
    page.addView(view, blockParams());
    return view;
  }

  TextView warn(String text) {
    if (text == null || text.trim().isEmpty()) return null;
    TextView view = label(text, 14, INK, false);
    view.setBackground(box(WARN_BG, WARN_BORDER));
    view.setPadding(dp(14), dp(11), dp(14), dp(11));
    page.addView(view, blockParams());
    return view;
  }

  Button button(String title, Runnable action) {
    Button view = new Button(this);
    view.setText(title);
    view.setTextSize(13);
    view.setAllCaps(false);
    view.setTextColor(DARK);
    view.setContentDescription(title);
    view.setOnClickListener(v -> action.run());
    return view;
  }

  void buttons(String left, Runnable leftAction, String right, Runnable rightAction) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(button(left, leftAction), new LinearLayout.LayoutParams(0, -2, 1));
    if (right != null) {
      row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
      row.addView(button(right, rightAction), new LinearLayout.LayoutParams(0, -2, 1));
    }
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(8);
    page.addView(row, params);
  }

  /** Horizontally scrollable table; the first row is the header. */
  void table(List<String[]> rows, List<Runnable> actions, List<Integer> colors) {
    TableLayout table = new TableLayout(this);
    table.setBackground(box(Color.WHITE, BORDER));
    table.setPadding(dp(4), dp(4), dp(4), dp(4));
    for (int r = 0; r < rows.size(); r++) {
      TableRow row = new TableRow(this);
      String[] cells = rows.get(r);
      int color = colors != null && r < colors.size() && colors.get(r) != null ? colors.get(r) : INK;
      for (int c = 0; c < cells.length; c++) {
        TextView cell = label(cells[c] == null ? "" : cells[c], 13, r == 0 ? MUTED : color, r == 0 || c == 0);
        cell.setPadding(dp(7), dp(6), dp(7), dp(6));
        cell.setGravity(c == 0 ? Gravity.START : Gravity.END);
        row.addView(cell);
      }
      if (r > 0 && r % 2 == 0) row.setBackgroundColor(Color.rgb(247, 249, 246));
      Runnable action = actions != null && r < actions.size() ? actions.get(r) : null;
      if (action != null) {
        row.setClickable(true);
        row.setOnClickListener(v -> action.run());
      }
      table.addView(row);
    }
    HorizontalScrollView wrapper = new HorizontalScrollView(this);
    wrapper.addView(table);
    page.addView(wrapper, blockParams());
  }

  EditText input(String caption, String value, int type) {
    page.addView(label(caption, 13, MUTED, true));
    EditText field = new EditText(this);
    field.setText(value == null ? "" : value);
    field.setInputType(type);
    field.setTextSize(15);
    field.setContentDescription(caption);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(6);
    page.addView(field, params);
    return field;
  }

  /**
   * A number field that accepts both a comma and a dot. The stock decimal field accepts only the
   * separator of the phone's locale and silently drops the other one, turning «9,86» into 986.
   */
  EditText numberInput(String caption, String value) {
    EditText field = input(caption, value, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
    field.setKeyListener(new android.text.method.NumberKeyListener() {
      @Override protected char[] getAcceptedChars() { return "0123456789.,-+".toCharArray(); }
      @Override public int getInputType() {
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
      }
    });
    return field;
  }

  Spinner select(String caption, String[] items, int selected) {
    page.addView(label(caption, 13, MUTED, true));
    Spinner spinner = new Spinner(this);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);
    if (selected >= 0 && selected < items.length) spinner.setSelection(selected);
    spinner.setContentDescription(caption);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
    params.bottomMargin = dp(6);
    page.addView(spinner, params);
    return spinner;
  }

  // ------------------------------------------------------------ dialogs

  void alert(String title, String message) {
    new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Понятно", null).show();
  }

  void confirm(String title, String message, String ok, Runnable done) {
    new AlertDialog.Builder(this).setTitle(title).setMessage(message)
      .setPositiveButton(ok, (dialog, which) -> done.run()).setNegativeButton("Отмена", null).show();
  }

  void prompt(String title, String message, String initial, Consumer<String> done) {
    EditText field = new EditText(this);
    field.setText(initial == null ? "" : initial);
    field.setSelection(field.getText().length());
    LinearLayout holder = vertical();
    holder.setPadding(dp(20), dp(4), dp(20), 0);
    holder.addView(field);
    new AlertDialog.Builder(this).setTitle(title).setMessage(message).setView(holder)
      .setPositiveButton("Сохранить", (dialog, which) -> done.accept(field.getText().toString().trim()))
      .setNegativeButton("Отмена", null).show();
  }

  void choose(String title, String[] items, IntConsumer done) {
    new AlertDialog.Builder(this).setTitle(title).setItems(items, (dialog, which) -> done.accept(which))
      .setNegativeButton("Отмена", null).show();
  }

  void chooseMany(String title, String[] items, boolean[] checked, Consumer<boolean[]> done) {
    boolean[] state = checked.clone();
    new AlertDialog.Builder(this).setTitle(title)
      .setMultiChoiceItems(items, state, (dialog, which, isChecked) -> state[which] = isChecked)
      .setPositiveButton("Готово", (dialog, which) -> done.accept(state)).setNegativeButton("Отмена", null).show();
  }

  interface Job<T> { T run(Store.Progress progress) throws Exception; }

  /** Runs slow work off the main thread behind a progress dialog. */
  <T> void work(String title, Job<T> job, Consumer<T> done) {
    TextView message = label("Подождите…", 15, INK, false);
    message.setPadding(dp(22), dp(16), dp(22), dp(16));
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(message).setCancelable(false).show();
    background.execute(() -> {
      try {
        T result = job.run(text -> main.post(() -> message.setText(text)));
        main.post(() -> {
          dialog.dismiss();
          done.accept(result);
        });
      } catch (Exception | OutOfMemoryError error) {
        main.post(() -> {
          dialog.dismiss();
          alert("Не выполнено", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        });
      }
    });
  }

  /** Runs a store write and reports a refusal as a dialog instead of crashing. */
  void save(ThrowingRunnable action, Runnable after) {
    try {
      action.run();
      if (after != null) after.run();
    } catch (Exception error) {
      alert("Не сохранено", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
  }

  interface ThrowingRunnable { void run() throws Exception; }

  void runLater(Runnable action) { main.post(action); }

  void runInBackground(Runnable action) { background.execute(action); }

  // ------------------------------------------------------------ system pickers

  void request(Intent intent, Consumer<Intent> done) {
    int code = nextRequest++;
    results.put(code, done);
    try {
      startActivityForResult(intent, code);
    } catch (Exception error) {
      results.remove(code);
      alert("Нет подходящего приложения", error.getMessage());
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    Consumer<Intent> done = results.remove(requestCode);
    if (done != null && resultCode == RESULT_OK && data != null) done.accept(data);
  }

  // ------------------------------------------------------------ comparison choice

  SharedPreferences prefs() { return getSharedPreferences("screens", MODE_PRIVATE); }

  String compareSlot(String slot) { return prefs().getString("compare_" + slot, null); }

  void setCompareSlot(String slot, String obsId) { prefs().edit().putString("compare_" + slot, obsId).apply(); }

  // ------------------------------------------------------------ wording

  /** «1 замер», «3 замера», «54 замера», «11 замеров». */
  static String count(int n, String one, String few, String many) {
    int tens = Math.abs(n) % 100, units = Math.abs(n) % 10;
    String word = tens >= 11 && tens <= 14 ? many : units == 1 ? one : units >= 2 && units <= 4 ? few : many;
    return n + " " + word;
  }

  static String grades(int n) { return count(n, "марка", "марки", "марок"); }
  static String observations(int n) { return count(n, "замер", "замера", "замеров"); }
  static String values(int n) { return count(n, "значение", "значения", "значений"); }
  static String docs(int n) { return count(n, "техкарта", "техкарты", "техкарт"); }

  /** Plant times are stored without an offset and shown as is; times with an offset (history, builds) in the phone's zone. */
  static String date(String raw) {
    if (raw == null || raw.length() < 10) return raw == null ? "время неизвестно" : raw;
    if (raw.length() > 19 && (raw.endsWith("Z") || raw.matches(".*[+-]\\d\\d:\\d\\d$"))) {
      try {
        raw = java.time.OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()
          .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
      } catch (java.time.DateTimeException ignored) {
        // Shown as written.
      }
    }
    String result = raw.substring(8, 10) + "." + raw.substring(5, 7) + "." + raw.substring(0, 4);
    if (raw.length() >= 16) result += " " + raw.substring(11, 16);
    if (raw.length() >= 19 && !":00".equals(raw.substring(16, 19))) result += raw.substring(16, 19);
    return result;
  }

  static String timeOrigin(String origin) {
    switch (origin == null ? "" : origin) {
      case "screen": return "с экрана";
      case "exif": return "из EXIF фото";
      case "manual": return "введено вручную";
      default: return "происхождение неизвестно";
    }
  }

  static String gradeOrigin(String origin) {
    switch (origin == null ? "" : origin) {
      case "user": return "указана пользователем";
      case "prototype": return "из прототипа";
      case "recognition": return "из распознанного JSON";
      case "reassigned": return "переназначена";
      case "manual": return "введена вручную";
      case "folder": return "по папке или имени файла";
      case "unknown": return "не определена";
      default: return origin == null || origin.isEmpty() ? "происхождение не указано" : origin;
    }
  }

  static String equipment(String state) {
    switch (state == null ? "" : state) {
      case "running": return "работает";
      case "stopped": return "остановлен";
      case "on": return "включена";
      case "off": return "выключена";
      default: return "неизвестно";
    }
  }

  static String steady(Boolean steady) {
    return steady == null ? "не указано" : steady ? "устойчивый режим" : "переход";
  }

  static String type(String type) {
    switch (type == null ? "" : type) {
      case Model.FACT: return "Факт";
      case Model.SETPOINT: return "Уставка";
      case Model.OUTPUT: return "Выход регулятора";
      case Model.CALC: return "Расчёт СУТП";
      case Model.LAB: return "Лаборатория";
      default: return "Вне словаря";
    }
  }

  static String method(String method) {
    switch (method == null ? "" : method) {
      case "recognition": return "распознано с фото";
      case "correction": return "ручная правка";
      default: return "ручной ввод";
    }
  }

  static String status(String status) {
    switch (status == null ? "" : status) {
      case Model.CONFIRMED: return "Подтверждено";
      case Model.REVIEW: return "Требует сверки";
      case Model.REJECTED: return "Отклонено";
      default: return "Статус неизвестен";
    }
  }

  static int statusColor(String status) {
    switch (status == null ? "" : status) {
      case Model.CONFIRMED: return CONFIRMED;
      case Model.REVIEW: return REVIEW;
      case Model.REJECTED: return REJECTED;
      default: return MUTED;
    }
  }

  String reading(Model.Val value) {
    if ("missing".equals(value.state)) return "пропуск";
    if ("error".equals(value.state) || value.number == null) {
      return "ошибка исходника" + (value.raw == null ? "" : " («" + value.raw + "»)");
    }
    Model.Param param = value.param == null ? null : store.paramByKey.get(value.param);
    String unit = value.unit == null || value.unit.isEmpty() ? " (ед. не указана)" : " " + value.unit;
    return Calc.fmt(value.number, param == null ? 0 : param.precision) + unit;
  }

  String obsTitle(Model.Obs obs) {
    return store.gradeName(obs.grade) + " · линия " + (obs.line == null ? "?" : obs.line);
  }

  String obsLine(Model.Obs obs) {
    int confirmed = 0, review = 0, total = 0;
    for (Model.Val value : store.values(obs.id)) {
      if (value.extra) continue;
      total++;
      if (value.confirmed()) confirmed++;
      else if (Model.REVIEW.equals(value.status)) review++;
    }
    String text = date(obs.time) + " (" + timeOrigin(obs.timeOrigin) + ") · " + total + " знач.: " + confirmed + " подтв., " + review + " на сверке";
    if (obs.excluded) text += " · исключён из расчётов";
    if (store.disputed.contains(obs.id)) text += " · возможный дубль";
    return text;
  }
}
