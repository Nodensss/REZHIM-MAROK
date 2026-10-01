package ru.tomskneftekhim.rezhim;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The factory snapshot shipped in the APK (or a newer one loaded from a file) plus the
 * phone's own layer: added photos, observations and values, review decisions, attribute
 * corrections, transitions and history. The factory part is never written; every change
 * is appended to the phone layer, so reinstalling or updating the APK keeps it.
 */
final class Store implements Analysis.Data {
  static final String SNAPSHOT_FORMAT = "rezhim-android-snapshot";
  static final String LAYER_FORMAT = "rezhim-android-user-layer";
  static final String EXPORT_FORMAT = "rezhim-android-export";
  static final int SCHEMA = 1;
  static final String[] LAYER_COLLECTIONS = {
    "grades", "params", "sources", "observations", "values", "value_links", "regions", "decisions",
    "patches", "transitions", "duplicates", "conflicts", "history"};

  private final Context context;
  final File dir;
  JSONObject snapshot, layer;
  String baseOrigin = "builtin", appVersion = "";
  private Set<String> assetSources = new HashSet<>();

  final List<Model.Grade> grades = new ArrayList<>();
  final Map<String, Model.Grade> gradeById = new HashMap<>();
  final List<Model.Param> params = new ArrayList<>();
  final Map<String, Model.Param> paramByKey = new HashMap<>();
  final List<Model.Source> sources = new ArrayList<>();
  final Map<String, Model.Source> sourceById = new HashMap<>();
  final Map<String, Model.Source> sourceBySha = new HashMap<>();
  final List<Model.Doc> docs = new ArrayList<>();
  final Map<String, Model.Doc> docById = new HashMap<>();
  final List<Model.Norm> norms = new ArrayList<>();
  final List<Model.Recipe> recipes = new ArrayList<>();
  final List<Model.Obs> observations = new ArrayList<>();
  final Map<String, Model.Obs> obsById = new HashMap<>();
  final List<Model.Val> values = new ArrayList<>();
  final Map<String, Model.Val> valueById = new HashMap<>();
  final Map<String, List<Model.Val>> valuesByObs = new HashMap<>();
  final List<Model.Transition> transitions = new ArrayList<>();
  final Map<String, Model.Transition> transitionById = new HashMap<>();
  final List<Model.History> history = new ArrayList<>();
  final Map<String, Model.History> historyById = new HashMap<>();
  final List<Analysis.Duplicate> duplicates = new ArrayList<>();
  final Set<String> disputed = new HashSet<>();

  private Store(Context context) {
    this.context = context;
    this.dir = context.getFilesDir();
  }

  static Store open(Context context) throws Exception {
    Store store = new Store(context);
    store.appVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
    store.loadBase();
    store.loadLayer();
    store.rebuild();
    return store;
  }

  // ---------------------------------------------------------------- loading

  static String now() { return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(); }

  static String newId() { return "u-" + UUID.randomUUID().toString(); }

  static String readText(InputStream in) throws IOException {
    try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count);
      return out.toString("UTF-8");
    }
  }

  static void writeAtomically(File file, String text) throws IOException {
    File parent = file.getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Нет папки " + parent);
    File temporary = new File(file.getPath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temporary)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    }
    if (file.exists()) {
      File backup = new File(file.getPath() + ".bak");
      if (backup.exists() && !backup.delete()) throw new IOException("Не удалось обновить резервную копию");
      if (!file.renameTo(backup)) throw new IOException("Не удалось сохранить резервную копию");
    }
    if (!temporary.renameTo(file)) throw new IOException("Не удалось записать " + file.getName());
  }

  private File baseDir() { return new File(dir, "base"); }

  String activeBase() {
    File state = new File(baseDir(), "active.txt");
    try {
      return state.exists() ? readText(new FileInputStream(state)).trim() : "builtin";
    } catch (IOException error) {
      return "builtin";
    }
  }

  private void loadBase() throws Exception {
    String active = activeBase();
    File file = new File(baseDir(), "snapshot-" + active + ".json");
    if (!"builtin".equals(active) && file.isFile()) {
      snapshot = new JSONObject(readText(new FileInputStream(file)));
      baseOrigin = active;
    } else {
      snapshot = new JSONObject(readText(context.getAssets().open("base/snapshot.json")));
      baseOrigin = "builtin";
    }
    checkSnapshot(snapshot);
    String[] listed = context.getAssets().list("sources");
    assetSources = new HashSet<>(Arrays.asList(listed == null ? new String[0] : listed));
  }

  static void checkSnapshot(JSONObject candidate) throws JSONException {
    if (!SNAPSHOT_FORMAT.equals(candidate.optString("format"))) throw new JSONException("Это не снимок базы «Режимы марок»");
    if (candidate.optInt("schema_version") != SCHEMA) {
      throw new JSONException("Версия схемы снимка " + candidate.optInt("schema_version") + " не поддерживается");
    }
  }

  private void loadLayer() throws Exception {
    File file = new File(dir, "user-layer.json");
    if (file.isFile()) {
      layer = new JSONObject(readText(new FileInputStream(file)));
      if (!LAYER_FORMAT.equals(layer.optString("format"))) throw new JSONException("Повреждён файл дополнений телефона");
    } else {
      layer = new JSONObject();
      layer.put("format", LAYER_FORMAT).put("schema_version", SCHEMA).put("device", UUID.randomUUID().toString())
        .put("created_at", now());
    }
    for (String key : LAYER_COLLECTIONS) if (!layer.has(key)) layer.put(key, new JSONArray());
  }

  JSONArray layer(String key) { return layer.optJSONArray(key); }

  private JSONArray base(String key) {
    JSONArray found = snapshot.optJSONArray(key);
    return found == null ? new JSONArray() : found;
  }

  static List<JSONObject> objects(JSONArray array) {
    List<JSONObject> result = new ArrayList<>();
    if (array == null) return result;
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.optJSONObject(i);
      if (item != null) result.add(item);
    }
    return result;
  }

  static String str(JSONObject item, String key) {
    return item.isNull(key) ? null : item.optString(key, null);
  }

  static Double num(JSONObject item, String key) {
    if (item.isNull(key) || !item.has(key)) return null;
    double value = item.optDouble(key, Double.NaN);
    return Double.isNaN(value) ? null : value;
  }

  static List<String> strings(JSONArray array) {
    List<String> result = new ArrayList<>();
    if (array == null) return result;
    for (int i = 0; i < array.length(); i++) if (!array.isNull(i)) result.add(array.optString(i));
    return result;
  }

  // ---------------------------------------------------------------- merged model

  void rebuild() {
    grades.clear(); gradeById.clear(); params.clear(); paramByKey.clear(); sources.clear(); sourceById.clear();
    sourceBySha.clear(); docs.clear(); docById.clear(); norms.clear(); recipes.clear(); observations.clear();
    obsById.clear(); values.clear(); valueById.clear(); valuesByObs.clear(); transitions.clear();
    transitionById.clear(); history.clear(); historyById.clear(); duplicates.clear(); disputed.clear();

    for (JSONObject item : objects(base("grades"))) addGrade(grade(item, false));
    for (JSONObject item : objects(layer("grades"))) addGrade(grade(item, true));
    for (JSONObject item : objects(base("params"))) addParam(param(item, false));
    for (JSONObject item : objects(layer("params"))) addParam(param(item, true));
    for (JSONObject item : objects(base("sources"))) addSource(source(item, false));
    for (JSONObject item : objects(layer("sources"))) addSource(source(item, true));
    for (JSONObject item : objects(base("docs"))) {
      Model.Doc doc = new Model.Doc();
      doc.id = str(item, "id"); doc.grade = str(item, "grade"); doc.version = item.optString("version");
      doc.validFrom = str(item, "valid_from"); doc.validTo = str(item, "valid_to"); doc.source = str(item, "source");
      doc.notes = item.optString("notes", "");
      JSONObject details = item.optJSONObject("details");
      doc.detailsJson = details == null ? null : details.toString();
      docs.add(doc);
      docById.put(doc.id, doc);
    }
    for (JSONObject item : objects(base("norms"))) {
      Model.Norm norm = new Model.Norm();
      norm.doc = str(item, "doc"); norm.param = str(item, "param"); norm.lower = num(item, "lower");
      norm.target = num(item, "target"); norm.upper = num(item, "upper");
      norm.section = item.optString("section", ""); norm.row = item.optString("row", "");
      norms.add(norm);
    }
    for (JSONObject item : objects(base("recipes"))) {
      Model.Recipe recipe = new Model.Recipe();
      recipe.doc = str(item, "doc"); recipe.zones = item.optString("zones", ""); recipe.version = item.optString("version", "");
      recipe.component = item.optString("component", ""); recipe.percent = num(item, "percent");
      recipe.mass = num(item, "mass"); recipe.count = num(item, "count");
      recipes.add(recipe);
    }
    for (JSONObject item : objects(base("observations"))) addObs(obs(item, false));
    for (JSONObject item : objects(layer("observations"))) addObs(obs(item, true));
    for (JSONObject item : objects(base("values"))) addValue(value(item, false, false));
    for (JSONObject item : objects(base("extras"))) addValue(extra(item));
    for (JSONObject item : objects(layer("values"))) addValue(value(item, true, item.optBoolean("extra")));

    for (JSONObject item : objects(layer("value_links"))) {
      Model.Val value = valueById.get(str(item, "value"));
      Model.Source source = sourceById.get(str(item, "source"));
      if (value == null || source == null) continue;
      if (!value.sources.contains(source.id)) value.sources.add(source.id);
      if (source.isImage() && !value.photos.contains(source.id)) value.photos.add(source.id);
      Model.Obs obs = obsById.get(value.obs);
      if (obs != null && !obs.sources.contains(source.id)) obs.sources.add(source.id);
    }
    for (JSONObject item : objects(layer("regions"))) {
      Model.Val value = valueById.get(str(item, "value"));
      JSONArray region = item.optJSONArray("region");
      if (value != null && region != null && region.length() == 4) {
        value.region = new float[]{(float) region.optDouble(0), (float) region.optDouble(1),
          (float) region.optDouble(2), (float) region.optDouble(3)};
        String source = str(item, "source");
        if (source != null && !value.photos.contains(source)) value.photos.add(0, source);
      }
    }
    for (JSONObject item : objects(layer("decisions"))) {
      Model.Val value = valueById.get(str(item, "value"));
      if (value == null) continue;
      value.status = item.optString("status", value.status);
      value.statusReason = item.optString("reason", "");
      value.history.add(item.optString("id"));
    }
    for (JSONObject item : objects(layer("patches"))) {
      Model.Obs obs = obsById.get(str(item, "obs"));
      if (obs != null) applyPatch(obs, item.optString("field"), item.opt("after"));
    }
    for (List<Model.Val> list : valuesByObs.values()) list.sort(VALUE_ORDER);

    for (JSONObject item : objects(layer("transitions"))) {
      Model.Transition transition = transition(item);
      transitions.add(transition);
      transitionById.put(transition.id, transition);
    }
    for (JSONObject item : objects(base("history"))) addHistory(history(item, false));
    for (JSONObject item : objects(layer("history"))) addHistory(history(item, true));
    history.sort(Comparator.comparing((Model.History h) -> h.at == null ? "" : h.at));

    Set<String> resolved = new HashSet<>();
    for (JSONObject item : objects(layer("duplicates"))) resolved.add(item.optString("pair"));
    List<Model.Obs> active = new ArrayList<>();
    for (Model.Obs obs : observations) if (!obs.excluded) active.add(obs);
    for (Analysis.Duplicate duplicate : Analysis.duplicates(active, this)) {
      if (resolved.contains(duplicate.key())) continue;
      duplicates.add(duplicate);
      disputed.add(duplicate.a);
      disputed.add(duplicate.b);
    }
  }

  private final Comparator<Model.Val> VALUE_ORDER = (a, b) -> {
    Model.Param pa = paramByKey.get(a.param), pb = paramByKey.get(b.param);
    String ga = pa == null ? "яя" : pa.group, gb = pb == null ? "яя" : pb.group;
    int compare = ga.compareTo(gb);
    if (compare != 0) return compare;
    String na = pa == null ? (a.label == null ? "" : a.label) : pa.name;
    String nb = pb == null ? (b.label == null ? "" : b.label) : pb.name;
    compare = na.compareTo(nb);
    return compare != 0 ? compare : a.id.compareTo(b.id);
  };

  private void addGrade(Model.Grade grade) { grades.add(grade); gradeById.put(grade.id, grade); }

  private void addParam(Model.Param param) {
    if (paramByKey.containsKey(param.key)) return;
    params.add(param);
    paramByKey.put(param.key, param);
  }

  private void addSource(Model.Source source) {
    sources.add(source);
    sourceById.put(source.id, source);
    if (source.sha256 != null) sourceBySha.put(source.sha256, source);
  }

  private void addObs(Model.Obs obs) {
    observations.add(obs);
    obsById.put(obs.id, obs);
    valuesByObs.put(obs.id, new ArrayList<>());
  }

  private void addValue(Model.Val value) {
    values.add(value);
    valueById.put(value.id, value);
    List<Model.Val> list = valuesByObs.get(value.obs);
    if (list != null) list.add(value);
  }

  private void addHistory(Model.History entry) { history.add(entry); historyById.put(entry.id, entry); }

  private static Model.Grade grade(JSONObject item, boolean user) {
    Model.Grade grade = new Model.Grade();
    grade.id = str(item, "id"); grade.name = item.optString("name"); grade.aliases = item.optString("aliases", "");
    grade.user = user;
    return grade;
  }

  private static Model.Param param(JSONObject item, boolean user) {
    Model.Param param = new Model.Param();
    param.key = item.optString("key"); param.name = item.optString("name", param.key); param.tag = item.optString("tag", "");
    param.unit = item.optString("unit", ""); param.role = item.optString("role", "measurement");
    param.type = item.optString("type", Model.FACT); param.group = item.optString("group", "Прочие данные");
    param.precision = item.optInt("precision", 2); param.user = user;
    return param;
  }

  private static Model.Source source(JSONObject item, boolean user) {
    Model.Source source = new Model.Source();
    source.id = str(item, "id"); source.name = item.optString("name"); source.sha256 = str(item, "sha256");
    source.kind = item.optString("kind", "photo"); source.screen = item.optString("screen", "");
    source.createdAt = str(item, "created_at"); source.asset = str(item, "asset"); source.file = str(item, "file");
    source.media = item.optString("media", ""); source.exifTime = str(item, "exif_time");
    source.size = item.optLong("size"); source.orientation = item.optInt("orientation", 1);
    source.note = item.optString("note", ""); source.user = user;
    return source;
  }

  private static Model.Obs obs(JSONObject item, boolean user) {
    Model.Obs obs = new Model.Obs();
    obs.id = str(item, "id"); obs.grade = str(item, "grade"); obs.line = str(item, "line"); obs.time = str(item, "time");
    obs.timeOrigin = item.optString("time_origin", "unknown"); obs.timeNote = item.optString("time_note", "");
    obs.gradeOrigin = item.optString("grade_origin", ""); obs.gradeNote = item.optString("grade_note", "");
    obs.a103 = item.optString("a103", "unknown"); obs.ab124 = item.optString("ab124", "unknown");
    obs.suutp = item.optString("suutp", "unknown");
    obs.steady = item.isNull("steady") || !item.has("steady") ? null : item.optBoolean("steady");
    obs.doc = str(item, "doc"); obs.recipe12 = item.optString("recipe12", ""); obs.recipe3 = item.optString("recipe3", "");
    obs.phase = str(item, "phase"); obs.createdAt = str(item, "created_at");
    JSONArray screens = item.optJSONArray("screens");
    obs.screen = screens != null ? android.text.TextUtils.join(", ", strings(screens)) : item.optString("screen", "");
    for (JSONObject pump : objects(item.optJSONArray("pumps"))) obs.pumps.add(new int[]{pump.optInt("zone"), pump.optInt("pump")});
    obs.notes = strings(item.optJSONArray("notes"));
    obs.sources = strings(item.optJSONArray("sources"));
    obs.user = user;
    return obs;
  }

  private Model.Val value(JSONObject item, boolean user, boolean extra) {
    Model.Val value = new Model.Val();
    value.id = str(item, "id"); value.obs = str(item, "obs"); value.param = str(item, "param");
    value.number = num(item, "number"); value.state = item.optString("state", "number");
    value.status = item.optString("status", Model.REVIEW); value.unit = item.optString("unit", "");
    value.unitNote = item.optString("unit_note", ""); value.type = item.optString("type", Model.FACT);
    value.method = item.optString("method", "manual"); value.raw = str(item, "raw"); value.label = str(item, "label");
    value.tag = item.optString("tag", ""); value.replaces = str(item, "replaces"); value.createdAt = str(item, "created_at");
    value.sources = strings(item.optJSONArray("sources")); value.photos = strings(item.optJSONArray("photos"));
    value.history = strings(item.optJSONArray("history"));
    value.statusReason = item.optString("reason", "");
    JSONArray region = item.optJSONArray("region");
    if (region != null && region.length() == 4) {
      value.region = new float[]{(float) region.optDouble(0), (float) region.optDouble(1),
        (float) region.optDouble(2), (float) region.optDouble(3)};
    }
    value.user = user;
    value.extra = extra;
    Model.Param param = value.param == null ? null : paramByKey.get(value.param);
    if (param != null && !item.has("unit")) value.unit = param.unit;
    return value;
  }

  private static Model.Val extra(JSONObject item) {
    Model.Val value = new Model.Val();
    value.id = str(item, "id"); value.obs = str(item, "obs"); value.param = null; value.extra = true;
    value.label = item.optString("label"); value.tag = item.optString("tag", ""); value.unit = item.optString("unit", "");
    value.raw = str(item, "raw"); value.number = num(item, "number");
    value.state = value.number == null ? "error" : "number";
    value.status = item.optString("status", Model.REVIEW); value.method = "recognition"; value.type = "";
    value.photos = strings(item.optJSONArray("photos"));
    value.sources = new ArrayList<>(value.photos);
    String parseError = item.optString("parse_error", "");
    if (!parseError.isEmpty()) value.unitNote = "Ошибка разбора: " + parseError;
    return value;
  }

  private static Model.Transition transition(JSONObject item) {
    Model.Transition transition = new Model.Transition();
    transition.id = str(item, "id"); transition.from = str(item, "from"); transition.to = str(item, "to");
    transition.line = str(item, "line"); transition.start = str(item, "start"); transition.end = str(item, "end");
    transition.setpointTime = str(item, "setpoint_time"); transition.delayMin = num(item, "delay_min");
    transition.production = num(item, "production"); transition.lab = item.optString("lab", "");
    transition.operator = item.optString("operator", ""); transition.notes = item.optString("notes", "");
    transition.status = item.optString("status", "incomplete"); transition.createdAt = str(item, "created_at");
    transition.updatedAt = str(item, "updated_at");
    transition.before = strings(item.optJSONArray("before")); transition.during = strings(item.optJSONArray("during"));
    transition.after = strings(item.optJSONArray("after"));
    return transition;
  }

  private static Model.History history(JSONObject item, boolean user) {
    Model.History entry = new Model.History();
    entry.id = str(item, "id"); entry.at = str(item, "at"); entry.action = item.optString("action");
    entry.obs = str(item, "obs"); entry.text = item.optString("text"); entry.values = strings(item.optJSONArray("values"));
    entry.user = user;
    return entry;
  }

  static void applyPatch(Model.Obs obs, String field, Object after) {
    String text = after == null || after == JSONObject.NULL ? null : String.valueOf(after);
    switch (field) {
      case "grade": obs.grade = text; break;
      case "grade_origin": obs.gradeOrigin = text == null ? "" : text; break;
      case "grade_note": obs.gradeNote = text == null ? "" : text; break;
      case "line": obs.line = text; break;
      case "time": obs.time = text; break;
      case "time_origin": obs.timeOrigin = text == null ? "unknown" : text; break;
      case "time_note": obs.timeNote = text == null ? "" : text; break;
      case "a103": obs.a103 = text == null ? "unknown" : text; break;
      case "ab124": obs.ab124 = text == null ? "unknown" : text; break;
      case "suutp": obs.suutp = text == null ? "unknown" : text; break;
      case "steady": obs.steady = text == null ? null : Boolean.valueOf(text); break;
      case "doc": obs.doc = text; break;
      case "recipe12": obs.recipe12 = text == null ? "" : text; break;
      case "recipe3": obs.recipe3 = text == null ? "" : text; break;
      case "screen": obs.screen = text == null ? "" : text; break;
      case "excluded": obs.excluded = Boolean.parseBoolean(text); break;
      case "excluded_reason": obs.excludedReason = text == null ? "" : text; break;
      case "note": if (text != null) obs.notes.add(text); break;
      case "source": if (text != null && !obs.sources.contains(text)) obs.sources.add(text); break;
      default: break;
    }
  }

  // ---------------------------------------------------------------- queries

  @Override public Model.Obs obs(String id) { return obsById.get(id); }

  @Override public List<Model.Val> values(String obsId) {
    List<Model.Val> found = valuesByObs.get(obsId);
    return found == null ? Collections.emptyList() : found;
  }

  String gradeName(String id) {
    if (id == null) return "Марка неизвестна";
    Model.Grade grade = gradeById.get(id);
    return grade == null ? "Марка " + id : grade.name;
  }

  String paramName(Model.Val value) {
    if (value.extra || value.param == null) return value.label == null ? "Дополнительное поле" : value.label;
    Model.Param param = paramByKey.get(value.param);
    return param == null ? value.param : param.name;
  }

  List<Model.Obs> observationsOf(String gradeId) {
    List<Model.Obs> result = new ArrayList<>();
    for (Model.Obs obs : observations) if (gradeId == null || gradeId.equals(obs.grade)) result.add(obs);
    result.sort(NEWEST_FIRST);
    return result;
  }

  static final Comparator<Model.Obs> NEWEST_FIRST = (a, b) -> {
    String ta = a.time == null ? "" : a.time, tb = b.time == null ? "" : b.time;
    int compare = tb.compareTo(ta);
    return compare != 0 ? compare : b.id.compareTo(a.id);
  };

  List<Model.Doc> docsOf(String gradeId) {
    List<Model.Doc> result = new ArrayList<>();
    for (Model.Doc doc : docs) if (gradeId == null || gradeId.equals(doc.grade)) result.add(doc);
    result.sort((a, b) -> String.valueOf(b.validFrom).compareTo(String.valueOf(a.validFrom)));
    return result;
  }

  /** The tech-card version of a grade in force on a date, or null. */
  Model.Doc docOn(String gradeId, String date) {
    if (gradeId == null) return null;
    String day = date == null || date.length() < 10 ? OffsetDateTime.now().toLocalDate().toString() : date.substring(0, 10);
    for (Model.Doc doc : docsOf(gradeId)) {
      if (doc.validFrom != null && doc.validFrom.compareTo(day) <= 0 && (doc.validTo == null || doc.validTo.compareTo(day) >= 0)) {
        return doc;
      }
    }
    return null;
  }

  Model.Doc effectiveDoc(Model.Obs obs) {
    Model.Doc doc = obs.doc == null ? null : docById.get(obs.doc);
    return doc != null ? doc : docOn(obs.grade, obs.time);
  }

  Map<String, Model.Norm> normsOf(Model.Doc doc) {
    Map<String, Model.Norm> result = new LinkedHashMap<>();
    if (doc == null) return result;
    for (Model.Norm norm : norms) if (doc.id.equals(norm.doc)) result.put(norm.param, norm);
    return result;
  }

  /** The single active number of a parameter in an observation; null when missing or in conflict. */
  Model.Val single(String obsId, String param) {
    Model.Val found = null;
    for (Model.Val value : values(obsId)) {
      if (value.extra || !param.equals(value.param) || !value.active()) continue;
      if (found != null && !(found.hasNumber() && value.hasNumber() && found.number.equals(value.number))) return null;
      if (found == null) found = value;
    }
    return found;
  }

  /** Parameters with two different active numbers in one observation. */
  List<String> conflicts(String obsId) {
    Map<String, Set<Double>> seen = new TreeMap<>();
    for (Model.Val value : values(obsId)) {
      if (value.extra || !value.active() || !value.hasNumber()) continue;
      seen.computeIfAbsent(value.param, key -> new HashSet<>()).add(value.number);
    }
    List<String> result = new ArrayList<>();
    for (Map.Entry<String, Set<Double>> entry : seen.entrySet()) if (entry.getValue().size() > 1) result.add(entry.getKey());
    return result;
  }

  Calc.Input input(String obsId, String param) {
    Model.Val value = single(obsId, param);
    return Calc.Input.of(value, paramByKey.get(param));
  }

  int count(String status) {
    int result = 0;
    for (Model.Val value : values) if (!value.extra && status.equals(value.status)) result++;
    return result;
  }

  List<Model.History> historyOfValue(Model.Val value) {
    List<Model.History> result = new ArrayList<>();
    for (String id : value.history) {
      Model.History entry = historyById.get(id);
      if (entry != null) result.add(entry);
    }
    for (Model.History entry : history) {
      if (entry.user && entry.values.contains(value.id) && !result.contains(entry)) result.add(entry);
    }
    return result;
  }

  List<Model.History> historyOfObs(String obsId) {
    List<Model.History> result = new ArrayList<>();
    for (Model.History entry : history) if (obsId.equals(entry.obs)) result.add(entry);
    return result;
  }

  // ---------------------------------------------------------------- files

  File userFile(String relative) { return new File(dir, relative); }

  boolean available(Model.Source source) {
    if (source.file != null && userFile(source.file).isFile()) return true;
    if (source.asset == null) return false;
    String name = source.asset.substring(source.asset.lastIndexOf('/') + 1);
    return assetSources.contains(name) || userFile(source.asset).isFile();
  }

  InputStream open(Model.Source source) throws IOException {
    if (source.file != null && userFile(source.file).isFile()) return new FileInputStream(userFile(source.file));
    if (source.asset != null) {
      String name = source.asset.substring(source.asset.lastIndexOf('/') + 1);
      if (assetSources.contains(name)) return context.getAssets().open(source.asset);
      if (userFile(source.asset).isFile()) return new FileInputStream(userFile(source.asset));
    }
    throw new FileNotFoundException("Файл «" + source.name + "» отсутствует в поставке и на телефоне");
  }

  static String sha256(InputStream in) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream stream = in) {
      byte[] buffer = new byte[1 << 16];
      int count;
      while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest()) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      StringBuilder hex = new StringBuilder();
      for (byte b : digest.digest(text.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  // ---------------------------------------------------------------- writes

  void save() throws IOException, JSONException {
    layer.put("updated_at", now());
    writeAtomically(new File(dir, "user-layer.json"), layer.toString());
  }

  private void commit() throws IOException, JSONException {
    save();
    rebuild();
  }

  JSONObject log(String action, String obs, List<String> valueIds, String text) {
    JSONObject entry = new JSONObject();
    try {
      entry.put("id", newId()).put("at", now()).put("action", action).put("obs", obs == null ? JSONObject.NULL : obs)
        .put("values", new JSONArray(valueIds == null ? new ArrayList<String>() : valueIds)).put("text", text);
    } catch (JSONException error) {
      throw new IllegalStateException(error);
    }
    layer("history").put(entry);
    return entry;
  }

  static String statusName(String status) {
    switch (status == null ? "" : status) {
      case Model.CONFIRMED: return "подтверждено";
      case Model.REVIEW: return "требует сверки";
      case Model.REJECTED: return "отклонено";
      default: return "статус неизвестен";
    }
  }

  void decide(Model.Val value, String status, String reason) throws Exception {
    JSONObject history = log("decision", value.obs, Collections.singletonList(value.id),
      paramName(value) + ": " + statusName(value.status) + " → " + statusName(status) + ". " + reason);
    JSONObject decision = new JSONObject().put("id", history.getString("id")).put("value", value.id).put("status", status)
      .put("before", value.status).put("reason", reason).put("at", now());
    layer("decisions").put(decision);
    commit();
  }

  /** The old number stays as a rejected record; the corrected one is a new value linked to it. */
  Model.Val correct(Model.Val value, Double number, String state, boolean confirm, String reason) throws Exception {
    String id = newId();
    JSONObject rejected = log("correction", value.obs, Arrays.asList(value.id, id),
      paramName(value) + ": " + describe(value.number, value.state) + " исправлено на " + describe(number, state) + ". " + reason);
    layer("decisions").put(new JSONObject().put("id", rejected.getString("id")).put("value", value.id)
      .put("status", Model.REJECTED).put("before", value.status).put("reason", "Исправлено: " + reason).put("at", now()));
    JSONObject item = new JSONObject().put("id", id).put("obs", value.obs).put("param", value.param == null ? JSONObject.NULL : value.param)
      .put("number", number == null ? JSONObject.NULL : number).put("state", state)
      .put("status", confirm ? Model.CONFIRMED : Model.REVIEW).put("unit", value.unit).put("type", value.type)
      .put("method", "correction").put("replaces", value.id).put("sources", new JSONArray(value.sources))
      .put("photos", new JSONArray(value.photos)).put("reason", reason).put("created_at", now())
      .put("extra", value.extra).put("label", value.label == null ? JSONObject.NULL : value.label).put("tag", value.tag);
    if (value.region != null) item.put("region", regionJson(value.region));
    layer("values").put(item);
    commit();
    return valueById.get(id);
  }

  static String describe(Double number, String state) {
    if ("missing".equals(state)) return "пропуск";
    if ("error".equals(state)) return "ошибка исходника";
    return Calc.fmt(number, 0);
  }

  static JSONArray regionJson(float[] region) throws JSONException {
    JSONArray array = new JSONArray();
    for (float part : region) array.put((double) part);
    return array;
  }

  void setRegion(Model.Val value, String sourceId, float[] region) throws Exception {
    log("region", value.obs, Collections.singletonList(value.id),
      paramName(value) + ": отмечена область на фото " + sourceById.get(sourceId).name + ".");
    layer("regions").put(new JSONObject().put("id", newId()).put("value", value.id).put("source", sourceId)
      .put("region", regionJson(region)).put("at", now()));
    commit();
  }

  /**
   * Adds a number to an observation. The same number from another photo only links the
   * photo to the existing value; a different number never overwrites it and both go to review.
   */
  Model.Val addValue(Model.Obs obs, Model.Param param, Double number, String state, String unit, String sourceId,
                     float[] region, boolean confirm, String reason) throws Exception {
    Model.Val existing = null;
    boolean conflict = false;
    for (Model.Val value : values(obs.id)) {
      if (value.extra || !param.key.equals(value.param) || !value.active()) continue;
      if (value.state.equals(state) && (number == null ? value.number == null : number.equals(value.number))) existing = value;
      else conflict = true;
    }
    if (existing != null && sourceId != null) {
      log("value_link", obs.id, Collections.singletonList(existing.id),
        param.name + ": то же число на фото " + sourceById.get(sourceId).name + " — фото добавлено к существующему значению.");
      layer("value_links").put(new JSONObject().put("id", newId()).put("value", existing.id).put("source", sourceId).put("at", now()));
      commit();
      return valueById.get(existing.id);
    }
    String id = newId();
    String status = confirm && !conflict ? Model.CONFIRMED : Model.REVIEW;
    String text = param.name + " = " + describe(number, state) + (unit.isEmpty() ? "" : " " + unit) + ", ручной ввод, "
      + statusName(status) + "." + (conflict ? " Есть другое число этого параметра — оба на сверке." : "")
      + (reason.isEmpty() ? "" : " " + reason);
    log("value", obs.id, Collections.singletonList(id), text);
    JSONObject item = new JSONObject().put("id", id).put("obs", obs.id).put("param", param.key)
      .put("number", number == null ? JSONObject.NULL : number).put("state", state).put("status", status)
      .put("unit", unit).put("type", param.type).put("method", "manual").put("reason", reason).put("created_at", now());
    JSONArray linked = new JSONArray();
    if (sourceId != null) linked.put(sourceId);
    item.put("sources", linked).put("photos", linked);
    if (region != null) item.put("region", regionJson(region));
    layer("values").put(item);
    commit();
    return valueById.get(id);
  }

  void patch(Model.Obs obs, Map<String, Object> changes, String reason) throws Exception {
    if (changes.isEmpty()) return;
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, Object> change : changes.entrySet()) {
      Object before = before(obs, change.getKey());
      Object after = change.getValue() == null ? JSONObject.NULL : change.getValue();
      layer("patches").put(new JSONObject().put("id", newId()).put("obs", obs.id).put("field", change.getKey())
        .put("before", before == null ? JSONObject.NULL : before).put("after", after).put("reason", reason).put("at", now()));
      parts.add(change.getKey() + ": " + (before == null ? "—" : before) + " → " + (after == JSONObject.NULL ? "—" : after));
    }
    log("patch", obs.id, null, "Реквизиты замера: " + android.text.TextUtils.join("; ", parts) + ". " + reason);
    commit();
  }

  private static Object before(Model.Obs obs, String field) {
    switch (field) {
      case "grade": return obs.grade;
      case "grade_origin": return obs.gradeOrigin;
      case "line": return obs.line;
      case "time": return obs.time;
      case "time_origin": return obs.timeOrigin;
      case "a103": return obs.a103;
      case "ab124": return obs.ab124;
      case "suutp": return obs.suutp;
      case "steady": return obs.steady;
      case "doc": return obs.doc;
      case "recipe12": return obs.recipe12;
      case "recipe3": return obs.recipe3;
      case "screen": return obs.screen;
      case "excluded": return obs.excluded;
      default: return null;
    }
  }

  Model.Grade addGrade(String name) throws Exception {
    for (Model.Grade grade : grades) if (grade.name.equalsIgnoreCase(name.trim())) return grade;
    String id = newId();
    layer("grades").put(new JSONObject().put("id", id).put("name", name.trim()).put("aliases", "").put("created_at", now()));
    log("grade", null, null, "Добавлена марка " + name.trim() + ".");
    commit();
    return gradeById.get(id);
  }

  Model.Param addParam(String key, String name, String unit, String type, String group) throws Exception {
    if (paramByKey.containsKey(key)) throw new IllegalArgumentException("Параметр «" + key + "» уже есть в словаре");
    String role = Model.SETPOINT.equals(type) ? "setpoint" : Model.OUTPUT.equals(type) ? "controller_output"
      : Model.CALC.equals(type) ? "suutp" : Model.LAB.equals(type) ? "lab" : "measurement";
    layer("params").put(new JSONObject().put("key", key).put("name", name).put("tag", "").put("unit", unit)
      .put("precision", 2).put("role", role).put("type", type).put("group", group).put("created_at", now()));
    log("param", null, null, "В словарь добавлен параметр " + name + " (" + key + ", " + (unit.isEmpty() ? "единица не указана" : unit) + ").");
    commit();
    return paramByKey.get(key);
  }

  /** Registers a photo already copied to files/sources. */
  Model.Source addPhoto(String name, String sha, long size, String relativeFile, String media, String exifTime,
                        int orientation, String screen, String note) throws Exception {
    Model.Source known = sourceBySha.get(sha);
    if (known != null) return known;
    String id = newId();
    layer("sources").put(new JSONObject().put("id", id).put("name", name).put("sha256", sha).put("size", size)
      .put("kind", "photo").put("screen", screen).put("created_at", now()).put("file", relativeFile).put("media", media)
      .put("exif_time", exifTime == null ? JSONObject.NULL : exifTime).put("orientation", orientation).put("note", note));
    log("photo", null, null, "Добавлено фото " + name + " (SHA-256 " + sha.substring(0, 12) + "…).");
    commit();
    return sourceById.get(id);
  }

  Model.Obs addObservation(JSONObject fields, List<String> sourceIds, String reason) throws Exception {
    String id = newId();
    fields.put("id", id).put("sources", new JSONArray(sourceIds)).put("created_at", now());
    if (!fields.has("notes")) fields.put("notes", new JSONArray());
    layer("observations").put(fields);
    Model.Obs draft = obs(fields, true);
    log("observation", id, null, "Создан замер: " + gradeName(draft.grade) + ", линия " + (draft.line == null ? "неизвестна" : draft.line)
      + ", " + (draft.time == null ? "время неизвестно" : draft.time.replace('T', ' ')) + ". " + reason);
    commit();
    return obsById.get(id);
  }

  void attachSource(Model.Obs obs, String sourceId, String reason) throws Exception {
    if (obs.sources.contains(sourceId)) return;
    Map<String, Object> change = new HashMap<>();
    change.put("source", sourceId);
    patch(obs, change, reason);
  }

  void saveTransition(JSONObject item, String text) throws Exception {
    JSONArray list = layer("transitions");
    String id = item.getString("id");
    boolean replaced = false;
    for (int i = 0; i < list.length(); i++) {
      JSONObject existing = list.optJSONObject(i);
      if (existing != null && id.equals(existing.optString("id"))) {
        item.put("created_at", existing.optString("created_at", now()));
        list.put(i, item);
        replaced = true;
      }
    }
    item.put("updated_at", now());
    if (!replaced) {
      if (!item.has("created_at")) item.put("created_at", now());
      list.put(item);
    }
    log("transition", null, null, text);
    commit();
  }

  void resolveDuplicate(Analysis.Duplicate duplicate, String resolution, String reason) throws Exception {
    layer("duplicates").put(new JSONObject().put("id", newId()).put("pair", duplicate.key()).put("resolution", resolution)
      .put("reason", reason).put("at", now()));
    log("duplicate", duplicate.a, null, "Возможный дубль " + duplicate.a + " / " + duplicate.b + ": " + resolution + ". " + reason);
    commit();
  }

  // ---------------------------------------------------------------- export

  JSONObject exportJson() throws Exception {
    JSONObject data = new JSONObject();
    JSONArray gradeArray = new JSONArray();
    for (Model.Grade grade : grades) {
      gradeArray.put(new JSONObject().put("id", grade.id).put("name", grade.name).put("aliases", grade.aliases).put("added_on_phone", grade.user));
    }
    data.put("grades", gradeArray);
    JSONArray paramArray = new JSONArray();
    for (Model.Param param : params) {
      paramArray.put(new JSONObject().put("key", param.key).put("name", param.name).put("tag", param.tag).put("unit", param.unit)
        .put("type", param.type).put("role", param.role).put("group", param.group).put("added_on_phone", param.user));
    }
    data.put("params", paramArray);
    data.put("docs", base("docs")).put("norms", base("norms")).put("recipes", base("recipes"));
    JSONArray sourceArray = new JSONArray();
    JSONObject checksums = new JSONObject();
    for (Model.Source source : sources) {
      sourceArray.put(new JSONObject().put("id", source.id).put("name", source.name).put("sha256", source.sha256)
        .put("size", source.size).put("kind", source.kind).put("screen", source.screen).put("media", source.media)
        .put("exif_time", source.exifTime == null ? JSONObject.NULL : source.exifTime).put("added_on_phone", source.user)
        .put("zip_path", zipPath(source)));
      checksums.put(source.id, source.sha256);
    }
    data.put("sources", sourceArray);
    JSONArray obsArray = new JSONArray();
    for (Model.Obs obs : observations) {
      JSONArray pumps = new JSONArray();
      for (int[] pump : obs.pumps) pumps.put(new JSONObject().put("zone", pump[0]).put("pump", pump[1]));
      obsArray.put(new JSONObject().put("id", obs.id).put("grade", nullable(obs.grade)).put("grade_name", gradeName(obs.grade))
        .put("grade_origin", obs.gradeOrigin).put("line", nullable(obs.line)).put("time", nullable(obs.time))
        .put("time_origin", obs.timeOrigin).put("time_note", obs.timeNote).put("a103", obs.a103).put("ab124", obs.ab124)
        .put("suutp", obs.suutp).put("steady", obs.steady == null ? JSONObject.NULL : obs.steady).put("doc", nullable(obs.doc))
        .put("recipe12", obs.recipe12).put("recipe3", obs.recipe3).put("pumps", pumps).put("screen", obs.screen)
        .put("excluded", obs.excluded).put("excluded_reason", obs.excludedReason).put("notes", new JSONArray(obs.notes))
        .put("sources", new JSONArray(obs.sources)).put("added_on_phone", obs.user)
        .put("possible_duplicate", disputed.contains(obs.id)));
    }
    data.put("observations", obsArray);
    JSONArray valueArray = new JSONArray();
    for (Model.Val value : values) {
      JSONObject item = new JSONObject().put("id", value.id).put("obs", value.obs).put("param", nullable(value.param))
        .put("name", paramName(value)).put("extra", value.extra).put("number", value.number == null ? JSONObject.NULL : value.number)
        .put("state", value.state).put("unit", value.unit).put("unit_known", !Calc.unit(value.unit).isEmpty()).put("type", value.type)
        .put("method", value.method).put("status", value.status).put("status_reason", value.statusReason)
        .put("raw", nullable(value.raw)).put("replaces", nullable(value.replaces)).put("sources", new JSONArray(value.sources))
        .put("photos", new JSONArray(value.photos)).put("history", new JSONArray(value.history)).put("added_on_phone", value.user);
      if (value.region != null) item.put("region", regionJson(value.region));
      valueArray.put(item);
    }
    data.put("values", valueArray);
    data.put("transitions", layer("transitions"));
    JSONArray historyArray = new JSONArray();
    for (Model.History entry : history) {
      historyArray.put(new JSONObject().put("id", entry.id).put("at", nullable(entry.at)).put("action", entry.action)
        .put("obs", nullable(entry.obs)).put("values", new JSONArray(entry.values)).put("text", entry.text).put("on_phone", entry.user));
    }
    data.put("history", historyArray);
    JSONObject counts = new JSONObject().put("grades", grades.size()).put("observations", observations.size())
      .put("values", values.size() - countExtras()).put("extras", countExtras()).put("sources", sources.size())
      .put("confirmed", count(Model.CONFIRMED)).put("review", count(Model.REVIEW)).put("rejected", count(Model.REJECTED))
      .put("transitions", transitions.size()).put("history", history.size());
    String dataText = data.toString();
    return new JSONObject().put("format", EXPORT_FORMAT).put("schema_version", SCHEMA).put("exported_at", now())
      .put("app_version", appVersion)
      .put("base", new JSONObject().put("revision", snapshot.optInt("revision")).put("built_at", snapshot.optString("built_at"))
        .put("database_sha256", snapshot.optString("database_sha256")).put("origin", baseOrigin))
      .put("device", layer.optString("device")).put("counts", counts)
      .put("checksums", new JSONObject().put("data_sha256", sha256(dataText)).put("sources_sha256", checksums))
      .put("data", new JSONObject(dataText)).put("user_layer", layer);
  }

  private int countExtras() {
    int result = 0;
    for (Model.Val value : values) if (value.extra) result++;
    return result;
  }

  private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }

  static String zipPath(Model.Source source) {
    String extension = source.name.contains(".") ? source.name.substring(source.name.lastIndexOf('.')).toLowerCase() : "";
    if (source.asset != null && source.asset.contains(".")) extension = source.asset.substring(source.asset.lastIndexOf('.'));
    if (source.file != null && source.file.contains(".")) extension = source.file.substring(source.file.lastIndexOf('.'));
    return "sources/" + source.sha256 + extension;
  }

  interface Progress { void update(String text); }

  void writeExport(OutputStream out, boolean zip, boolean allPhotos, Progress progress) throws Exception {
    byte[] json = exportJson().toString(1).getBytes(StandardCharsets.UTF_8);
    if (!zip) {
      out.write(json);
      out.flush();
      return;
    }
    try (ZipOutputStream stream = new ZipOutputStream(out)) {
      stream.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
      stream.putNextEntry(new ZipEntry("export.json"));
      stream.write(json);
      stream.closeEntry();
      List<Model.Source> chosen = new ArrayList<>();
      for (Model.Source source : sources) if ((allPhotos || source.user) && available(source)) chosen.add(source);
      int done = 0;
      for (Model.Source source : chosen) {
        progress.update("Файлы: " + (++done) + " из " + chosen.size());
        stream.putNextEntry(new ZipEntry(zipPath(source)));
        try (InputStream in = open(source)) {
          byte[] buffer = new byte[1 << 16];
          int count;
          while ((count = in.read(buffer)) != -1) stream.write(buffer, 0, count);
        }
        stream.closeEntry();
      }
    }
  }

  // ---------------------------------------------------------------- import

  /** What an import file would change, shown before anything is written. */
  static final class Preview {
    String kind, fileName, summary;
    JSONObject document;
    final Map<String, File> files = new HashMap<>();
    final Map<String, int[]> counts = new LinkedHashMap<>();
    final List<String> conflicts = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    int added;
  }

  Preview preview(InputStream in, String fileName, Progress progress) throws Exception {
    Preview preview = new Preview();
    preview.fileName = fileName;
    File staging = new File(dir, "import-staging");
    deleteTree(staging);
    if (!staging.mkdirs()) throw new IOException("Не удалось подготовить временную папку");
    byte[] head = new byte[4];
    java.io.BufferedInputStream buffered = new java.io.BufferedInputStream(in);
    buffered.mark(8);
    int read = buffered.read(head);
    buffered.reset();
    if (read == 4 && head[0] == 'P' && head[1] == 'K') {
      try (ZipInputStream zip = new ZipInputStream(buffered)) {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          String name = entry.getName();
          if (entry.isDirectory()) continue;
          if (name.endsWith(".json") && !name.startsWith("sources/")) {
            preview.document = new JSONObject(readText(new NonClosing(zip)));
          } else if (name.startsWith("sources/") && name.indexOf('/', 8) < 0) {
            progress.update("Проверка файлов: " + name.substring(8));
            File target = new File(staging, name.substring(8));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileOutputStream out = new FileOutputStream(target)) {
              byte[] buffer = new byte[1 << 16];
              int count;
              while ((count = zip.read(buffer)) != -1) { out.write(buffer, 0, count); digest.update(buffer, 0, count); }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            String expected = name.substring(8).contains(".") ? name.substring(8, name.lastIndexOf('.')) : name.substring(8);
            if (!hex.toString().equals(expected)) {
              preview.warnings.add("Файл " + name + " повреждён: SHA-256 не совпадает. Он не будет импортирован.");
              if (!target.delete()) preview.warnings.add("Не удалось удалить повреждённый файл " + target.getName());
            } else {
              preview.files.put(expected, target);
            }
          }
        }
      }
    } else {
      preview.document = new JSONObject(readText(buffered));
    }
    if (preview.document == null) throw new JSONException("В архиве нет JSON с данными");
    String format = preview.document.optString("format");
    if (SNAPSHOT_FORMAT.equals(format)) {
      checkSnapshot(preview.document);
      previewSnapshot(preview);
    } else if (EXPORT_FORMAT.equals(format) || LAYER_FORMAT.equals(format)) {
      if (preview.document.optInt("schema_version") != SCHEMA) {
        throw new JSONException("Версия схемы " + preview.document.optInt("schema_version") + " не поддерживается");
      }
      previewLayer(preview);
    } else {
      throw new JSONException("Неизвестный формат файла: " + (format.isEmpty() ? "нет поля format" : format));
    }
    return preview;
  }

  private void previewSnapshot(Preview preview) {
    JSONObject document = preview.document;
    preview.kind = "snapshot";
    int revision = document.optInt("revision");
    int current = snapshot.optInt("revision");
    JSONObject counts = document.optJSONObject("counts");
    preview.summary = "Новая версия базы: ревизия " + revision + " от " + document.optString("built_at")
      + (counts == null ? "" : ". Марок " + counts.optInt("grades") + ", замеров " + counts.optInt("observations")
        + ", значений " + counts.optInt("values") + ", фото " + counts.optInt("photos"))
      + ". Сейчас действует ревизия " + current + ".";
    if (revision <= current) preview.warnings.add("Ревизия не новее действующей — это откат или повтор. Дополнения телефона сохранятся.");
    int missing = 0;
    for (JSONObject item : objects(document.optJSONArray("sources"))) {
      String sha = item.optString("sha256");
      String asset = item.optString("asset");
      String name = asset.substring(asset.lastIndexOf('/') + 1);
      if (!assetSources.contains(name) && !preview.files.containsKey(sha) && !userFile(asset).isFile()) missing++;
    }
    if (missing > 0) preview.warnings.add("Нет файлов для " + missing + " источников — они будут видны без фото, пока не загружен архив с ними.");
  }

  private void previewLayer(Preview preview) throws JSONException {
    preview.kind = "layer";
    JSONObject data = preview.document.optJSONObject("data");
    JSONObject sums = preview.document.optJSONObject("checksums");
    if (data != null && sums != null && !sha256(data.toString()).equals(sums.optString("data_sha256"))) {
      preview.warnings.add("Контрольная сумма раздела data не совпадает — файл изменён или повреждён после выгрузки.");
    }
    JSONObject base = preview.document.optJSONObject("base");
    if (base != null && base.optInt("revision") != snapshot.optInt("revision")) {
      preview.warnings.add("Файл выгружен при другой ревизии базы (" + base.optInt("revision") + ").");
    }
    JSONObject incoming = EXPORT_FORMAT.equals(preview.document.optString("format"))
      ? preview.document.optJSONObject("user_layer") : preview.document;
    if (incoming == null) throw new JSONException("В файле нет раздела дополнений телефона (user_layer)");
    preview.document = incoming;
    for (String key : LAYER_COLLECTIONS) {
      Map<String, JSONObject> local = new HashMap<>();
      for (JSONObject item : objects(layer(key))) local.put(recordId(key, item), item);
      int added = 0, same = 0, conflicting = 0;
      for (JSONObject item : objects(incoming.optJSONArray(key))) {
        JSONObject existing = local.get(recordId(key, item));
        if (existing == null) added++;
        else if (canonical(existing).equals(canonical(item))) same++;
        else {
          conflicting++;
          if (preview.conflicts.size() < 30) preview.conflicts.add(key + ": " + recordId(key, item));
        }
      }
      if (added + same + conflicting > 0) preview.counts.put(key, new int[]{added, same, conflicting});
      preview.added += added;
    }
    for (JSONObject item : objects(incoming.optJSONArray("sources"))) {
      String sha = item.optString("sha256");
      if (sourceBySha.containsKey(sha) || preview.files.containsKey(sha)) continue;
      preview.warnings.add("Для фото " + item.optString("name") + " нет файла — нужен ZIP с фотографиями.");
    }
    int conflicts = 0;
    for (int[] count : preview.counts.values()) conflicts += count[2];
    preview.summary = "Новых записей: " + preview.added + ", совпадающих: " + totalSame(preview) + ", конфликтов: " + conflicts
      + ". Локальные записи не удаляются; при конфликте остаётся ваша версия, импортированная уходит на сверку.";
  }

  private static int totalSame(Preview preview) {
    int result = 0;
    for (int[] count : preview.counts.values()) result += count[1];
    return result;
  }

  private static String recordId(String collection, JSONObject item) {
    return "params".equals(collection) ? item.optString("key") : item.optString("id");
  }

  /** JSON text with keys sorted at every level, for comparing records regardless of key order. */
  static String canonical(Object value) {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      List<String> keys = new ArrayList<>();
      for (Iterator<String> it = object.keys(); it.hasNext(); ) keys.add(it.next());
      Collections.sort(keys);
      StringBuilder out = new StringBuilder("{");
      for (String key : keys) {
        if (out.length() > 1) out.append(',');
        out.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key)));
      }
      return out.append('}').toString();
    }
    if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      StringBuilder out = new StringBuilder("[");
      for (int i = 0; i < array.length(); i++) {
        if (i > 0) out.append(',');
        out.append(canonical(array.opt(i)));
      }
      return out.append(']').toString();
    }
    if (value instanceof String) return JSONObject.quote((String) value);
    if (value instanceof Number) return numberText((Number) value);
    return String.valueOf(value);
  }

  private static String numberText(Number number) {
    try {
      return JSONObject.numberToString(number);
    } catch (JSONException error) {
      return String.valueOf(number);
    }
  }

  void apply(Preview preview) throws Exception {
    if ("snapshot".equals(preview.kind)) {
      applySnapshot(preview);
      return;
    }
    File sourcesDir = new File(dir, "sources");
    if (!sourcesDir.isDirectory() && !sourcesDir.mkdirs()) throw new IOException("Нет папки для фото");
    for (Map.Entry<String, File> file : preview.files.entrySet()) {
      File target = new File(sourcesDir, file.getValue().getName());
      if (!target.exists() && !file.getValue().renameTo(target)) throw new IOException("Не удалось сохранить " + target.getName());
    }
    int added = 0, conflicted = 0;
    Map<String, String> alias = new HashMap<>();
    for (String key : LAYER_COLLECTIONS) {
      if ("conflicts".equals(key)) continue;
      Map<String, JSONObject> local = new HashMap<>();
      for (JSONObject item : objects(layer(key))) local.put(recordId(key, item), item);
      for (JSONObject item : objects(preview.document.optJSONArray(key))) {
        remapSources(item, alias);
        JSONObject existing = local.get(recordId(key, item));
        if (existing == null) {
          Model.Source same = "sources".equals(key) ? sourceBySha.get(item.optString("sha256")) : null;
          if (same != null) {
            if (!same.id.equals(item.optString("id"))) alias.put(item.optString("id"), same.id);
            continue;
          }
          layer(key).put(item);
          added++;
        } else if (!canonical(existing).equals(canonical(item))) {
          layer("conflicts").put(new JSONObject().put("id", newId()).put("collection", key).put("record", recordId(key, item))
            .put("local", existing).put("imported", item).put("file", preview.fileName).put("at", now()).put("resolved", false));
          conflicted++;
        }
      }
    }
    log("import", null, null, "Импорт «" + preview.fileName + "»: добавлено записей " + added + ", конфликтов на сверку " + conflicted + ".");
    commit();
    deleteTree(new File(dir, "import-staging"));
  }

  /** The same photo added on two phones gets two ids; imported records are pointed at the local one. */
  private static void remapSources(JSONObject item, Map<String, String> alias) throws JSONException {
    if (alias.isEmpty()) return;
    for (String field : new String[]{"source"}) {
      String id = item.optString(field, null);
      if (id != null && alias.containsKey(id)) item.put(field, alias.get(id));
    }
    for (String field : new String[]{"sources", "photos"}) {
      JSONArray array = item.optJSONArray(field);
      if (array == null) continue;
      for (int i = 0; i < array.length(); i++) {
        String id = array.optString(i);
        if (alias.containsKey(id)) array.put(i, alias.get(id));
      }
    }
  }

  private void applySnapshot(Preview preview) throws Exception {
    File sourcesDir = new File(dir, "sources");
    if (!sourcesDir.isDirectory() && !sourcesDir.mkdirs()) throw new IOException("Нет папки для фото");
    for (JSONObject item : objects(preview.document.optJSONArray("sources"))) {
      File staged = preview.files.get(item.optString("sha256"));
      if (staged == null) continue;
      File target = userFile(item.optString("asset"));
      File parent = target.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Нет папки " + parent);
      if (!target.exists() && !staged.renameTo(target)) throw new IOException("Не удалось сохранить " + target.getName());
    }
    String revision = String.valueOf(preview.document.optInt("revision"));
    writeAtomically(new File(baseDir(), "snapshot-" + revision + ".json"), preview.document.toString());
    activateBase(revision, "Загружена база ревизии " + revision + " из «" + preview.fileName + "».");
    deleteTree(new File(dir, "import-staging"));
  }

  List<String> installedBases() {
    List<String> result = new ArrayList<>();
    File[] files = baseDir().listFiles();
    if (files != null) {
      for (File file : files) {
        String name = file.getName();
        if (name.startsWith("snapshot-") && name.endsWith(".json")) result.add(name.substring(9, name.length() - 5));
      }
    }
    Collections.sort(result);
    return result;
  }

  void activateBase(String revision, String text) throws Exception {
    writeAtomically(new File(baseDir(), "active.txt"), revision);
    loadBase();
    log("base", null, null, text);
    commit();
  }

  void resolveConflict(JSONObject conflict, boolean takeImported) throws Exception {
    conflict.put("resolved", true).put("resolution", takeImported ? "imported" : "local").put("resolved_at", now());
    if (takeImported) {
      String key = conflict.getString("collection");
      JSONArray list = layer(key);
      for (int i = 0; i < list.length(); i++) {
        JSONObject item = list.optJSONObject(i);
        if (item != null && recordId(key, item).equals(conflict.optString("record"))) list.put(i, conflict.getJSONObject("imported"));
      }
    }
    log("conflict", null, null, "Конфликт импорта " + conflict.optString("collection") + " " + conflict.optString("record") + ": "
      + (takeImported ? "принята импортированная версия, прежняя сохранена в конфликте" : "оставлена версия телефона") + ".");
    commit();
  }

  List<JSONObject> openConflicts() {
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject item : objects(layer("conflicts"))) if (!item.optBoolean("resolved")) result.add(item);
    return result;
  }

  static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    if (file.exists() && !file.delete()) file.deleteOnExit();
  }

  // ---------------------------------------------------------------- integrity

  String checkIntegrity(Progress progress) {
    StringBuilder report = new StringBuilder();
    JSONObject counts = snapshot.optJSONObject("counts");
    int baseValues = 0, baseObs = 0;
    for (Model.Val value : values) if (!value.user && !value.extra) baseValues++;
    for (Model.Obs obs : observations) if (!obs.user) baseObs++;
    if (counts != null) {
      report.append("Снимок базы: марок ").append(counts.optInt("grades")).append(", замеров ").append(counts.optInt("observations"))
        .append(", значений ").append(counts.optInt("values")).append(", фото ").append(counts.optInt("photos")).append(".\n");
      if (counts.optInt("observations") != baseObs || counts.optInt("values") != baseValues) {
        report.append("ОШИБКА: загружено замеров ").append(baseObs).append(", значений ").append(baseValues).append(" — не совпадает со снимком.\n");
      } else {
        report.append("Число загруженных записей совпадает со снимком.\n");
      }
    }
    int ok = 0, bad = 0, missing = 0, photos = 0;
    for (Model.Source source : sources) {
      if (source.isImage()) photos++;
      progress.update("SHA-256: " + (ok + bad + missing + 1) + " из " + sources.size());
      if (!available(source)) { missing++; report.append("Нет файла: ").append(source.name).append('\n'); continue; }
      try {
        if (sha256(open(source)).equals(source.sha256)) ok++;
        else { bad++; report.append("SHA-256 НЕ совпадает: ").append(source.name).append('\n'); }
      } catch (Exception error) {
        bad++;
        report.append("Ошибка чтения ").append(source.name).append(": ").append(error.getMessage()).append('\n');
      }
    }
    report.append("Источников: ").append(sources.size()).append(" (фото ").append(photos).append("). SHA-256 совпал: ").append(ok)
      .append(", не совпал: ").append(bad).append(", нет файла: ").append(missing).append(".\n");
    int dangling = 0;
    for (Model.Val value : values) if (!obsById.containsKey(value.obs)) dangling++;
    for (Model.Obs obs : observations) if (obs.grade != null && !gradeById.containsKey(obs.grade)) dangling++;
    for (Model.Transition transition : transitions) {
      List<String> all = new ArrayList<>(transition.before);
      all.addAll(transition.during);
      all.addAll(transition.after);
      for (String id : all) if (!obsById.containsKey(id)) dangling++;
    }
    report.append(dangling == 0 ? "Связи между записями целы." : "Разорванных ссылок: " + dangling + ".");
    return report.toString();
  }

  /** Keeps a ZipInputStream open when a reader closes the stream it was given. */
  private static final class NonClosing extends java.io.FilterInputStream {
    NonClosing(InputStream in) { super(in); }
    @Override public void close() {}
  }
}
