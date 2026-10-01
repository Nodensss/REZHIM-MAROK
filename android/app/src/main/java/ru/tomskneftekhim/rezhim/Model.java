package ru.tomskneftekhim.rezhim;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain records of the merged database: the factory snapshot with the phone's own
 * additions and decisions applied. No Android types, so the analysis code that uses
 * them runs in ordinary JVM unit tests.
 */
final class Model {
  private Model() {}

  static final String CONFIRMED = "confirmed", REVIEW = "review", REJECTED = "rejected";
  static final String FACT = "fact", SETPOINT = "setpoint", OUTPUT = "output", CALC = "calc", LAB = "lab";

  static final class Grade {
    String id, name, aliases = "";
    boolean user;
  }

  static final class Param {
    String key, name, tag = "", unit = "", role = "measurement", type = FACT, group = "Прочие данные";
    int precision = 2;
    boolean user;
  }

  static final class Source {
    String id, name, sha256, kind, screen = "", createdAt, asset, file, media = "", exifTime, note = "";
    long size;
    int orientation = 1;
    boolean user;

    boolean isImage() { return media != null && media.startsWith("image/"); }
  }

  static final class Doc {
    String id, grade, version, validFrom, validTo, source, notes = "", detailsJson;
  }

  static final class Norm {
    String doc, param, section = "", row = "";
    Double lower, target, upper;
  }

  static final class Recipe {
    String doc, zones = "", version = "", component = "";
    Double percent, mass, count;
  }

  static final class Obs {
    String id, grade, line, time, timeOrigin = "unknown", timeNote = "", gradeOrigin = "", gradeNote = "";
    String a103 = "unknown", ab124 = "unknown", suutp = "unknown", doc, recipe12 = "", recipe3 = "";
    String phase, screen = "", createdAt, excludedReason = "";
    Boolean steady;
    boolean excluded, user;
    List<int[]> pumps = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    List<String> sources = new ArrayList<>();
  }

  static final class Val {
    String id, obs, param, state = "number", status = REVIEW, unit = "", unitNote = "", type = FACT;
    String method = "manual", raw, label, tag = "", replaces, createdAt, statusReason = "";
    Double number;
    float[] region;
    boolean user, extra;
    List<String> sources = new ArrayList<>();
    List<String> photos = new ArrayList<>();
    List<String> history = new ArrayList<>();

    boolean active() { return !REJECTED.equals(status); }
    boolean confirmed() { return CONFIRMED.equals(status); }
    boolean hasNumber() { return "number".equals(state) && number != null; }
  }

  static final class Transition {
    String id, from, to, line, start, end, setpointTime, lab = "", operator = "", notes = "";
    String status = "incomplete", createdAt, updatedAt;
    Double delayMin, production;
    List<String> before = new ArrayList<>();
    List<String> during = new ArrayList<>();
    List<String> after = new ArrayList<>();

    boolean confirmed() { return "confirmed".equals(status); }
  }

  static final class History {
    String id, at, action, obs, text;
    List<String> values = new ArrayList<>();
    boolean user;
  }
}
