package hk.billsplit;

import static hk.billsplit.Api.*;

import java.math.*;
import java.util.*;

public final class Money {
  public static final long MAX_MINOR = 100_000_000L;

  private Money() {}

  public static long cents(String text, boolean allowZero) {
    if (text == null || !text.matches("\\d{1,9}(\\.\\d{1,2})?"))
      throw ApiException.invalid("INVALID_AMOUNT");
    long value = new BigDecimal(text).movePointRight(2).longValueExact();
    if (value < (allowZero ? 0 : 1) || value > MAX_MINOR)
      throw ApiException.invalid("INVALID_AMOUNT");
    return value;
  }

  public static List<Share> split(long total, SplitMethod method, List<Participant> input) {
    if (input.isEmpty() || input.size() > 100 || total <= 0 || total > MAX_MINOR)
      throw ApiException.invalid("INVALID_SPLIT");
    List<Participant> people =
        input.stream().sorted(Comparator.comparing(p -> p.memberId().toString())).toList();
    if (people.stream().map(Participant::memberId).distinct().count() != people.size())
      throw ApiException.invalid("DUPLICATE_PARTICIPANT");
    if (method == SplitMethod.EXACT) {
      List<Share> shares =
          people.stream().map(p -> new Share(p.memberId(), cents(p.value(), true))).toList();
      if (shares.stream().mapToLong(Share::amountMinor).sum() != total)
        throw ApiException.invalid("SPLIT_TOTAL_MISMATCH");
      return shares;
    }
    long[] weights = new long[people.size()];
    for (int i = 0; i < weights.length; i++) {
      weights[i] = method == SplitMethod.EQUAL ? 1 : cents(people.get(i).value(), true);
    }
    long denominator = Arrays.stream(weights).sum();
    if (method == SplitMethod.PERCENT && denominator != 10_000)
      throw ApiException.invalid("PERCENT_TOTAL_MISMATCH");
    long[] allocated = new long[people.size()];
    List<Integer> order = new ArrayList<>();
    long used = 0;
    for (int i = 0; i < weights.length; i++) {
      allocated[i] = total * weights[i] / denominator;
      used += allocated[i];
      order.add(i);
    }
    order.sort(
        Comparator.<Integer>comparingLong(i -> -(total * weights[i] % denominator))
            .thenComparingInt(i -> i));
    for (int i = 0; i < total - used; i++) allocated[order.get(i)]++;
    List<Share> result = new ArrayList<>();
    for (int i = 0; i < people.size(); i++)
      result.add(new Share(people.get(i).memberId(), allocated[i]));
    return List.copyOf(result);
  }

  public static List<Suggestion> suggest(List<Balance> balances) {
    List<Balance> sorted =
        balances.stream().sorted(Comparator.comparing(b -> b.memberId().toString())).toList();
    List<Balance> debt = sorted.stream().filter(b -> b.amountMinor() < 0).toList();
    List<Balance> credit = sorted.stream().filter(b -> b.amountMinor() > 0).toList();
    long[] debts = debt.stream().mapToLong(b -> -b.amountMinor()).toArray();
    long[] credits = credit.stream().mapToLong(Balance::amountMinor).toArray();
    List<Suggestion> result = new ArrayList<>();
    int d = 0, c = 0;
    while (d < debts.length && c < credits.length) {
      long amount = Math.min(debts[d], credits[c]);
      result.add(new Suggestion(debt.get(d).memberId(), credit.get(c).memberId(), amount));
      debts[d] -= amount;
      credits[c] -= amount;
      if (debts[d] == 0) d++;
      if (credits[c] == 0) c++;
    }
    return List.copyOf(result);
  }
}
