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

  public record Profile(@NotBlank @Size(max = 60) String displayName) {}

  public record User(UUID id, String displayName) {}

  public record GroupInput(@NotBlank @Size(max = 80) String name) {}

  public record Group(UUID id, String name, String currency, Instant archivedAt) {}

  public record Member(UUID id, UUID userId, String displayName, String role) {}

  public record Participant(@NotNull UUID memberId, @Size(max = 30) String value) {}

  public record ExpenseInput(
      @NotNull UUID payerMemberId,
      @NotBlank @Size(max = 160) String description,
      @NotBlank @Size(max = 30) String amount,
      @NotNull SplitMethod splitMethod,
      @NotNull LocalDate incurredOn,
      @NotEmpty @Size(max = 100) List<@NotNull @Valid Participant> participants,
      @PositiveOrZero Integer version) {}

  public record Share(UUID memberId, long amountMinor) {}

  public record Expense(
      UUID id,
      UUID groupId,
      UUID payerMemberId,
      UUID createdBy,
      String description,
      long amountMinor,
      SplitMethod splitMethod,
      LocalDate incurredOn,
      int version,
      Instant voidedAt,
      List<Share> shares) {}

  public record Version(@PositiveOrZero int version) {}

  public record Balance(UUID memberId, long amountMinor) {}

  public record Suggestion(UUID senderMemberId, UUID recipientMemberId, long amountMinor) {}

  public record Balances(List<Balance> balances, List<Suggestion> suggestions) {}

  public record SettlementInput(
      @NotNull UUID recipientMemberId,
      @NotBlank @Size(max = 30) String amount,
      @NotNull PaymentMethod method) {}

  public record Settlement(
      UUID id,
      UUID groupId,
      UUID senderMemberId,
      UUID recipientMemberId,
      long amountMinor,
      PaymentMethod method,
      String status,
      int version,
      Instant createdAt,
      Instant confirmedAt) {}

  public record Invite(UUID id, String token, Instant expiresAt) {}

  public record InviteSummary(UUID id, Instant expiresAt, Instant redeemedAt, Instant revokedAt) {}

  public record AcceptInvite(@NotBlank @Size(max = 100) String token) {}

  public record Audit(
      UUID id, UUID actorId, UUID entityId, String action, String detail, Instant createdAt) {}

  public record Page<T>(List<T> items, boolean hasMore) {}
}
