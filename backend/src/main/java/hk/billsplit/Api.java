package hk.billsplit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class Api {
  private Api() {}

  public enum SplitMethod {
    EQUAL,
    EXACT,
    PERCENT
  }

  public enum PaymentMethod {
    FPS,
    PAYME,
    BANK,
    CASH
  }

  public enum Category {
    FOOD,
    TRANSPORT,
    STAY,
    SHOPPING,
    FUN,
    OTHER
  }

  public record GroupInput(
      @NotBlank @Size(max = 80) String name,
      @Size(max = 8) String emoji,
      @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 40) String> members) {}

  public record GroupPatch(@NotBlank @Size(max = 80) String name, @Size(max = 8) String emoji) {}

  public record MemberInput(@NotBlank @Size(max = 40) String name) {}

  public record Group(UUID id, String code, String name, String emoji, String currency) {}

  public record Member(UUID id, String name) {}

  public record Participant(@NotNull UUID memberId, @Size(max = 30) String value) {}

  public record ExpenseInput(
      @NotNull UUID payerMemberId,
      @NotBlank @Size(max = 160) String description,
      @NotNull Category category,
      @NotBlank @Size(max = 30) String amount,
      @NotNull SplitMethod splitMethod,
      @NotNull LocalDate incurredOn,
      @NotEmpty @Size(max = 100) List<@NotNull @Valid Participant> participants,
      @PositiveOrZero Integer version) {}

  public record Share(UUID memberId, long amountMinor) {}

  public record Expense(
      UUID id,
      UUID payerMemberId,
      String description,
      Category category,
      long amountMinor,
      SplitMethod splitMethod,
      LocalDate incurredOn,
      int version,
      Instant createdAt,
      List<Share> shares) {}

  public record TransferInput(
      @NotNull UUID fromMemberId,
      @NotNull UUID toMemberId,
      @NotBlank @Size(max = 30) String amount,
      @NotNull PaymentMethod method,
      @NotNull LocalDate incurredOn) {}

  public record Transfer(
      UUID id,
      UUID fromMemberId,
      UUID toMemberId,
      long amountMinor,
      PaymentMethod method,
      LocalDate incurredOn,
      Instant createdAt) {}

  public record Balance(UUID memberId, long amountMinor, long paidMinor, long shareMinor) {}

  public record Suggestion(UUID senderMemberId, UUID recipientMemberId, long amountMinor) {}

  public record Snapshot(
      Group group,
      List<Member> members,
      List<Expense> expenses,
      List<Transfer> transfers,
      List<Balance> balances,
      List<Suggestion> suggestions) {}

  public record Activity(
      UUID id,
      UUID actorMemberId,
      UUID entityId,
      String action,
      String detail,
      Instant createdAt) {}

  public record Page<T>(List<T> items, boolean hasMore) {}
}
