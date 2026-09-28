package hk.billsplit;

import static hk.billsplit.Api.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MoneyTest {
  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void decimalValidationRejectsPrecisionExponentsNegativesAndLimits() {
    assertThat(Money.cents("100.01", false)).isEqualTo(10001);
    assertThat(Money.cents("0", true)).isZero();
    for (String invalid : List.of("0", "-1", "1.001", "1e2", "NaN", "1000000.01", "99999999999")) {
      assertThatThrownBy(() -> Money.cents(invalid, false)).isInstanceOf(ApiException.class);
    }
  }

  @Test
  void equalSplitsAreOrderIndependentAndAllocateEveryCent() {
    List<Participant> people =
        List.of(new Participant(C, null), new Participant(A, null), new Participant(B, null));
    assertThat(Money.split(10000, SplitMethod.EQUAL, people))
        .containsExactly(new Share(A, 3334), new Share(B, 3333), new Share(C, 3333));
    for (long total = 1; total < 10000; total++) {
      List<Share> result = Money.split(total, SplitMethod.EQUAL, people);
      assertThat(result.stream().mapToLong(Share::amountMinor).sum()).isEqualTo(total);
      assertThat(result.getFirst().amountMinor() - result.getLast().amountMinor())
          .isBetween(0L, 1L);
    }
  }

  @Test
  void percentageRemaindersFollowWeightsAndStableTies() {
    assertThat(
            Money.split(
                5,
                SplitMethod.PERCENT,
                List.of(
                    new Participant(A, "33.33"),
                    new Participant(B, "33.33"),
                    new Participant(C, "33.34"))))
        .containsExactly(new Share(A, 2), new Share(B, 1), new Share(C, 2));
    assertThatThrownBy(
            () -> Money.split(5, SplitMethod.PERCENT, List.of(new Participant(A, "99.99"))))
        .isInstanceOf(ApiException.class);
    assertThat(
            Money.split(
                1,
                SplitMethod.PERCENT,
                List.of(new Participant(A, "0"), new Participant(B, "100"))))
        .containsExactly(new Share(A, 0), new Share(B, 1));
  }

  @Test
  void exactSplitsRequireTheTotalAndUniqueParticipants() {
    assertThat(
            Money.split(
                10000,
                SplitMethod.EXACT,
                List.of(new Participant(A, "25.50"), new Participant(B, "74.50"))))
        .containsExactly(new Share(A, 2550), new Share(B, 7450));
    assertThatThrownBy(
            () -> Money.split(10000, SplitMethod.EXACT, List.of(new Participant(A, "99.99"))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                Money.split(
                    10000,
                    SplitMethod.EQUAL,
                    List.of(new Participant(A, null), new Participant(A, null))))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void suggestedTransfersClearEveryBalance() {
    List<Balance> original =
        List.of(
            new Balance(C, -2000, 0, 2000),
            new Balance(B, -3000, 0, 3000),
            new Balance(A, 5000, 5000, 0));
    Map<UUID, Long> remaining = new HashMap<>();
    original.forEach(b -> remaining.put(b.memberId(), b.amountMinor()));
    List<Suggestion> suggestions = Money.suggest(original);
    for (Suggestion s : suggestions) {
      assertThat(s.amountMinor()).isPositive();
      remaining.merge(s.senderMemberId(), s.amountMinor(), Long::sum);
      remaining.merge(s.recipientMemberId(), -s.amountMinor(), Long::sum);
    }
    assertThat(remaining.values()).containsOnly(0L);
    assertThat(suggestions).hasSize(2);
  }
}
