package ru.tomskneftekhim.rezhim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class CalcTest {
  private static Calc.Input fact(double number, String unit) {
    return new Calc.Input(number, unit, Model.FACT, true, "x");
  }

  private static Calc.Input review(double number, String unit) {
    return new Calc.Input(number, unit, Model.FACT, false, "x");
  }

  /** ТЗ, раздел 5: подтверждённый снимок 15803-020 от 21.09.2025 16:19:00. */
  @Test public void controlSnapshot15803() {
    Calc.Input z1 = fact(29.85, "кг/ч"), z2 = fact(34.19, "кг/ч"), z3 = fact(17.28, "кг/ч");
    Calc.Result sum = Calc.initiatorSum(z1, z2, z3);
    assertTrue(sum.ok());
    assertEquals(81.32, sum.value, 1e-9);
    assertFalse(sum.unverified);
    assertEquals("36,7", Calc.fixed(Calc.share(z1, sum).value, 1));
    assertEquals("42,0", Calc.fixed(Calc.share(z2, sum).value, 1));
    assertEquals("21,2", Calc.fixed(Calc.share(z3, sum).value, 1));
    Calc.Result ratio = Calc.palPerPropylene(fact(6.60, "кг/ч"), fact(33.55, "м³/ч"));
    assertEquals("кг/м³", ratio.unit);
    assertEquals("0,1967", Calc.significant(ratio.value, 4));
  }

  /** ТЗ, раздел 5: два отдельных непроверенных снимка 40200 и 80210. */
  @Test public void separateSnapshots40200And80210() {
    assertEquals("+3,94", Calc.signed(Calc.delta(3.52, 7.46), 2));
    assertEquals("+111,9", Calc.signed(Calc.percent(3.52, 7.46), 1));
    Calc.Result before = Calc.initiatorSum(review(35.19, "кг/ч"), review(34.30, "кг/ч"), review(22.88, "кг/ч"));
    assertTrue(before.unverified);
    assertEquals("38,1", Calc.fixed(Calc.share(review(35.19, "кг/ч"), before).value, 1));
    assertEquals("37,1", Calc.fixed(Calc.share(review(34.30, "кг/ч"), before).value, 1));
    assertEquals("24,8", Calc.fixed(Calc.share(review(22.88, "кг/ч"), before).value, 1));
    Calc.Result after = Calc.initiatorSum(review(33.58, "кг/ч"), review(45.48, "кг/ч"), review(29.40, "кг/ч"));
    assertEquals("31,0", Calc.fixed(Calc.share(review(33.58, "кг/ч"), after).value, 1));
    assertEquals("41,9", Calc.fixed(Calc.share(review(45.48, "кг/ч"), after).value, 1));
    assertEquals("27,1", Calc.fixed(Calc.share(review(29.40, "кг/ч"), after).value, 1));
  }

  @Test public void percentIsUndefinedForZeroBase() {
    assertNull(Calc.percent(0.0, 5.0));
    assertEquals(5.0, Calc.delta(0.0, 5.0), 0);
    assertNull(Calc.delta(null, 5.0));
  }

  @Test public void sumRefusesMissingZoneMixedUnitsAndNonFacts() {
    Calc.Result missing = Calc.initiatorSum(fact(1, "кг/ч"), null, fact(1, "кг/ч"));
    assertFalse(missing.ok());
    assertTrue(missing.reason.contains("зоны 2"));
    assertFalse(Calc.initiatorSum(fact(1, "кг/ч"), fact(1, "л/ч"), fact(1, "кг/ч")).ok());
    assertFalse(Calc.initiatorSum(fact(1, ""), fact(1, ""), fact(1, "")).ok());
    Calc.Input setpoint = new Calc.Input(1.0, "кг/ч", Model.SETPOINT, true, "уставка");
    assertFalse(Calc.initiatorSum(fact(1, "кг/ч"), setpoint, fact(1, "кг/ч")).ok());
  }

  @Test public void shareRefusesZeroSum() {
    Calc.Input zero = fact(0, "кг/ч");
    Calc.Result sum = Calc.initiatorSum(zero, zero, zero);
    assertTrue(sum.ok());
    assertFalse(Calc.share(zero, sum).ok());
    assertFalse(Calc.palToInitiators(fact(2, "кг/ч"), sum).ok());
  }

  @Test public void propyleneWithoutUnitIsForbidden() {
    Calc.Result result = Calc.palPerPropylene(fact(6.6, "кг/ч"), fact(7.2, ""));
    assertFalse(result.ok());
    assertTrue(result.reason.contains("запрещён"));
    assertFalse(Calc.palPerPropylene(fact(6.6, "кг/ч"), fact(7.2, "кг/ч")).ok());
    assertFalse(Calc.palPerPropylene(fact(6.6, "кг/ч"), fact(0, "м³/ч")).ok());
  }

  @Test public void specificConsumptionNeedsActualProduction() {
    Calc.Result result = Calc.specific(fact(6.6, "кг/ч"), fact(17.5, "т/ч"));
    assertEquals("кг/т", result.unit);
    assertEquals(6.6 / 17.5, result.value, 1e-12);
    Calc.Input plannedOutput = new Calc.Input(17.5, "т/ч", Model.SETPOINT, true, "план");
    assertFalse(Calc.specific(fact(6.6, "кг/ч"), plannedOutput).ok());
    assertEquals("м³/т", Calc.propylenePerOutput(fact(33.55, "м3/ч"), fact(17.5, "т/ч")).unit);
  }

  @Test public void unitSpellingsAreEquivalent() {
    assertTrue(Calc.sameUnit("м3/ч", "м³/ч"));
    assertTrue(Calc.sameUnit(" кг/ч", "кг/ч"));
    assertFalse(Calc.sameUnit("", ""));
  }

  @Test public void formattingKeepsWrittenPrecision() {
    assertEquals("6,60", Calc.fmt(6.6, 2));
    assertEquals("1,438", Calc.fmt(1.438, 2));
    assertEquals("313", Calc.fmt(313.0, 0));
    assertEquals(2.0, Calc.median(Arrays.asList(3.0, 1.0, 2.0)), 0);
    assertEquals(20.5, Calc.parse("20,5"), 0);
    assertNull(Calc.parse("12a"));
    assertNull(Calc.parse(""));
  }
}
