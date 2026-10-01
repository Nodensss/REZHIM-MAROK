package ru.tomskneftekhim.rezhim;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Arithmetic of comparisons and proportions (ТЗ, раздел 5). Every rule about units,
 * value types and zero divisors lives here; a figure is either computed from
 * compatible inputs or refused with a reason that the screen shows verbatim.
 */
final class Calc {
  private Calc() {}

  static final String KG_H = "кг/ч", M3_H = "м³/ч", T_H = "т/ч";

  /** One number taken from an observation together with what is known about it. */
  static final class Input {
    final Double number;
    final String unit, type, label;
    final boolean confirmed;

    Input(Double number, String unit, String type, boolean confirmed, String label) {
      this.number = number;
      this.unit = unit == null ? "" : unit;
      this.type = type == null ? Model.FACT : type;
      this.confirmed = confirmed;
      this.label = label;
    }

    static Input of(Model.Val value, Model.Param param) {
      if (value == null) return null;
      String label = param == null ? value.param : param.name;
      return new Input(value.hasNumber() ? value.number : null, value.unit, value.type, value.confirmed(), label);
    }
  }

  /** A computed figure, or the reason it was not computed. */
  static final class Result {
    final Double value;
    final String unit, reason;
    final boolean unverified;

    private Result(Double value, String unit, String reason, boolean unverified) {
      this.value = value;
      this.unit = unit;
      this.reason = reason;
      this.unverified = unverified;
    }

    static Result of(double value, String unit, boolean unverified) { return new Result(value, unit, null, unverified); }
    static Result refused(String reason) { return new Result(null, "", reason, false); }
    boolean ok() { return value != null; }
  }

  /** Canonical spelling for comparing units: «м3/ч», «м^3/ч» and «м³/ч» are the same unit. */
  static String unit(String raw) {
    if (raw == null) return "";
    return raw.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("м^3", "м³").replace("м3", "м³");
  }

  static boolean sameUnit(String a, String b) {
    String left = unit(a);
    return !left.isEmpty() && left.equals(unit(b));
  }

  static Double delta(Double before, Double after) {
    return before == null || after == null ? null : after - before;
  }

  /** %ΔX = 100 × ΔX / X_до; undefined when X_до is zero. */
  static Double percent(Double before, Double after) {
    if (before == null || after == null || before == 0) return null;
    return 100 * (after - before) / before;
  }

  private static String missing(Input input, String what) {
    if (input == null || input.number == null) return "нет значения: " + what;
    return null;
  }

  /** FΣ = F1 + F2 + F3 when the three flows are facts of one observation with one unit. */
  static Result initiatorSum(Input f1, Input f2, Input f3) {
    Input[] zones = {f1, f2, f3};
    boolean unverified = false;
    for (int i = 0; i < zones.length; i++) {
      String problem = missing(zones[i], "расход инициатора зоны " + (i + 1));
      if (problem != null) return Result.refused(problem);
      if (!Model.FACT.equals(zones[i].type)) return Result.refused("расход зоны " + (i + 1) + " не является фактом");
      unverified |= !zones[i].confirmed;
    }
    if (unit(f1.unit).isEmpty()) return Result.refused("единица расхода инициатора не указана");
    if (!sameUnit(f1.unit, f2.unit) || !sameUnit(f1.unit, f3.unit)) return Result.refused("у расходов зон разные единицы");
    return Result.of(f1.number + f2.number + f3.number, f1.unit, unverified);
  }

  /** Доля зоны = 100 × Fi / FΣ when FΣ > 0. */
  static Result share(Input zone, Result sum) {
    if (!sum.ok()) return Result.refused(sum.reason);
    if (sum.value <= 0) return Result.refused("сумма расходов не больше нуля");
    return Result.of(100 * zone.number / sum.value, "%", sum.unverified);
  }

  /** FПАЛ / FΣ: ratio of observed flows, not a product recipe. */
  static Result palToInitiators(Input pal, Result sum) {
    String problem = missing(pal, "расход ПАЛ");
    if (problem != null) return Result.refused(problem);
    if (!sum.ok()) return Result.refused(sum.reason);
    if (!Model.FACT.equals(pal.type)) return Result.refused("ПАЛ не является фактом");
    if (!sameUnit(pal.unit, sum.unit)) return Result.refused("единицы ПАЛ и расходов инициатора различаются");
    if (sum.value == 0) return Result.refused("сумма расходов равна нулю");
    return Result.of(pal.number / sum.value, "", sum.unverified || !pal.confirmed);
  }

  private static Result ratio(Input numerator, Input denominator, String numeratorUnit, String denominatorUnit, String unit) {
    String problem = missing(numerator, numerator == null ? "числитель" : numerator.label);
    if (problem == null) problem = missing(denominator, denominator == null ? "знаменатель" : denominator.label);
    if (problem != null) return Result.refused(problem);
    for (Input input : new Input[]{numerator, denominator}) {
      if (!Model.FACT.equals(input.type)) return Result.refused(input.label + " не является фактом");
    }
    if (unit(numerator.unit).isEmpty()) return Result.refused("единица «" + numerator.label + "» не установлена — расчёт запрещён");
    if (unit(denominator.unit).isEmpty()) return Result.refused("единица «" + denominator.label + "» не установлена — расчёт запрещён");
    if (!sameUnit(numerator.unit, numeratorUnit)) return Result.refused("«" + numerator.label + "» в " + numerator.unit + ", нужно " + numeratorUnit);
    if (!sameUnit(denominator.unit, denominatorUnit)) return Result.refused("«" + denominator.label + "» в " + denominator.unit + ", нужно " + denominatorUnit);
    if (denominator.number == 0) return Result.refused("«" + denominator.label + "» равно нулю");
    return Result.of(numerator.number / denominator.number, unit, !numerator.confirmed || !denominator.confirmed);
  }

  /** Удельный расход F / Q в кг/т: F в кг/ч, фактическая производительность Q в т/ч. */
  static Result specific(Input flow, Input production) { return ratio(flow, production, KG_H, T_H, "кг/т"); }

  /** ПАЛ / пропилен в кг/м³ — не массовая доля. */
  static Result palPerPropylene(Input pal, Input propylene) { return ratio(pal, propylene, KG_H, M3_H, "кг/м³"); }

  /** Пропилен к выпуску в м³/т; перевод в кг/т требует плотности и условий измерения. */
  static Result propylenePerOutput(Input propylene, Input production) { return ratio(propylene, production, M3_H, T_H, "м³/т"); }

  static double quantile(List<Double> sorted, double p) {
    double index = (sorted.size() - 1) * p;
    int low = (int) Math.floor(index);
    int high = (int) Math.ceil(index);
    return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (index - low);
  }

  static double median(List<Double> values) {
    List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return quantile(sorted, 0.5);
  }

  /** Decimal places a value was written with, e.g. 1.438 → 3, 6.6 → 1. */
  static int decimals(double value) {
    int scale = BigDecimal.valueOf(value).stripTrailingZeros().scale();
    return Math.max(0, Math.min(scale, 6));
  }

  /** Russian decimal comma, at least {@code minimum} decimals, never fewer than the value carries. */
  static String fmt(Double value, int minimum) {
    if (value == null) return "—";
    int places = Math.max(minimum, decimals(value));
    return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
  }

  static String fixed(Double value, int places) {
    if (value == null) return "—";
    return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
  }

  static String signed(Double value, int places) {
    if (value == null) return "—";
    String text = fixed(value, places);
    return value > 0 ? "+" + text : text;
  }

  static String significant(Double value, int digits) {
    if (value == null) return "—";
    return BigDecimal.valueOf(value).round(new MathContext(digits)).stripTrailingZeros().toPlainString().replace('.', ',');
  }

  /** Parses operator input with a comma or a dot; returns null for anything that is not a finite number. */
  static Double parse(String raw) {
    if (raw == null) return null;
    String text = raw.trim().replace(',', '.').replace(" ", "");
    if (!text.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)")) return null;
    double value = Double.parseDouble(text);
    return Double.isInfinite(value) || Double.isNaN(value) ? null : value;
  }
}
