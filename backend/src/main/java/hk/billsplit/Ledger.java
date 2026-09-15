package hk.billsplit;

import static hk.billsplit.Api.*;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class Ledger {
  private final JdbcClient db;
  private final Groups groups;
  private static final RowMapper<Settlement> SETTLEMENT =
      (r, n) ->
          new Settlement(
              r.getObject("id", UUID.class),
              r.getObject("group_id", UUID.class),
              r.getObject("sender_member_id", UUID.class),
              r.getObject("recipient_member_id", UUID.class),
              r.getLong("amount_minor"),
              PaymentMethod.valueOf(r.getString("method")),
              r.getString("status"),
              r.getInt("version"),
              Groups.instant(r, "created_at"),
              Groups.instant(r, "confirmed_at"));

  public Ledger(JdbcClient db, Groups groups) {
    this.db = db;
    this.groups = groups;
  }

  private record Retry(String operation, String fingerprint, UUID resultId) {}

  private UUID retry(UUID actor, UUID key, String operation, String payload) {
    String fingerprint = Groups.hash(payload);
    db.sql(
            """
        INSERT INTO idempotency(actor_id, operation_key, operation, fingerprint)
        VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
        """)
        .params(actor, key, operation, fingerprint)
        .update();
    Retry row =
        db.sql("SELECT * FROM idempotency WHERE actor_id=? AND operation_key=? FOR UPDATE")
            .params(actor, key)
            .query(
                (r, n) ->
                    new Retry(
                        r.getString("operation"),
                        r.getString("fingerprint"),
                        r.getObject("result_id", UUID.class)))
            .single();
    if (!row.operation().equals(operation) || !row.fingerprint().equals(fingerprint))
      throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
    return row.resultId();
  }

  private void complete(UUID actor, UUID key, UUID result) {
    db.sql("UPDATE idempotency SET result_id=? WHERE actor_id=? AND operation_key=?")
        .params(result, actor, key)
        .update();
  }

  private List<Share> validate(UUID actor, UUID group, ExpenseInput input) {
    Set<UUID> members =
        new HashSet<>(groups.members(actor, group).stream().map(Member::id).toList());
    if (!members.contains(input.payerMemberId())
        || input.participants().stream().anyMatch(p -> !members.contains(p.memberId())))
      throw ApiException.invalid("INVALID_MEMBER");
    if (input.incurredOn().isBefore(LocalDate.of(2000, 1, 1))
        || input.incurredOn().isAfter(LocalDate.now(ZoneId.of("Asia/Hong_Kong"))))
      throw ApiException.invalid("INVALID_DATE");
    return Money.split(
        Money.cents(input.amount(), false), input.splitMethod(), input.participants());
  }

  @Transactional
  public Expense createExpense(UUID actor, UUID group, UUID key, ExpenseInput input) {
    groups.membership(actor, group);
    groups.writable(group);
    UUID previous = retry(actor, key, "expense:" + group, input.toString());
    if (previous != null) return expense(actor, previous);
    List<Share> shares = validate(actor, group, input);
    UUID id = UUID.randomUUID();
    db.sql(
            """
        INSERT INTO expense(id, group_id, payer_member_id, created_by, description,
          amount_minor, split_method, incurred_on) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """)
        .params(
            id,
            group,
            input.payerMemberId(),
            actor,
            input.description().strip(),
            Money.cents(input.amount(), false),
            input.splitMethod().name(),
            input.incurredOn())
        .update();
    saveShares(id, group, shares);
    Expense result = expense(actor, id);
    groups.audit(group, actor, id, "EXPENSE_CREATED", result.toString());
    complete(actor, key, id);
    return result;
  }

  private void saveShares(UUID expense, UUID group, List<Share> shares) {
    db.sql("DELETE FROM expense_share WHERE expense_id=?").param(expense).update();
    for (Share s : shares) {
      db.sql(
              "INSERT INTO expense_share(expense_id, group_id, member_id, amount_minor) VALUES (?, ?, ?, ?)")
          .params(expense, group, s.memberId(), s.amountMinor())
          .update();
    }
  }

  private Expense row(UUID id) {
    List<Share> shares =
        db.sql(
                "SELECT member_id, amount_minor FROM expense_share WHERE expense_id=? ORDER BY member_id")
            .param(id)
            .query(
                (r, n) ->
                    new Share(r.getObject("member_id", UUID.class), r.getLong("amount_minor")))
            .list();
    return db.sql("SELECT * FROM expense WHERE id=?")
        .param(id)
        .query(
            (r, n) ->
                new Expense(
                    r.getObject("id", UUID.class),
                    r.getObject("group_id", UUID.class),
                    r.getObject("payer_member_id", UUID.class),
                    r.getObject("created_by", UUID.class),
                    r.getString("description"),
                    r.getLong("amount_minor"),
                    SplitMethod.valueOf(r.getString("split_method")),
                    r.getObject("incurred_on", LocalDate.class),
                    r.getInt("version"),
                    Groups.instant(r, "voided_at"),
                    shares))
        .optional()
        .orElseThrow(ApiException::missing);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Expense expense(UUID actor, UUID id) {
    Expense expense = row(id);
    groups.membership(actor, expense.groupId());
    return expense;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page<Expense> expenses(UUID actor, UUID group, int offset) {
    groups.membership(actor, group);
    List<UUID> ids =
        db.sql(
                """
        SELECT id FROM expense WHERE group_id=? ORDER BY incurred_on DESC, created_at DESC, id DESC LIMIT 51 OFFSET ?
        """)
            .params(group, offset)
            .query(UUID.class)
            .list();
    return Groups.page(ids.stream().map(this::row).toList());
  }

  private Expense editable(UUID actor, UUID id, int version) {
    Expense initial = row(id);
    Member member = groups.membership(actor, initial.groupId());
    if (!initial.createdBy().equals(actor) && !member.role().equals("OWNER"))
      throw ApiException.missing();
    groups.writable(initial.groupId());
    Expense current = row(id);
    if (current.version() != version) throw ApiException.conflict("STALE_VERSION");
    if (current.voidedAt() != null) throw ApiException.conflict("EXPENSE_VOIDED");
    return current;
  }

  @Transactional
  public Expense edit(UUID actor, UUID id, ExpenseInput input) {
    if (input.version() == null) throw ApiException.invalid("VERSION_REQUIRED");
    Expense before = editable(actor, id, input.version());
    List<Share> shares = validate(actor, before.groupId(), input);
    db.sql(
            """
        UPDATE expense SET payer_member_id=?, description=?, amount_minor=?, split_method=?,
          incurred_on=?, version=version+1 WHERE id=?
        """)
        .params(
            input.payerMemberId(),
            input.description().strip(),
            Money.cents(input.amount(), false),
            input.splitMethod().name(),
            input.incurredOn(),
            id)
        .update();
    saveShares(id, before.groupId(), shares);
    Expense after = row(id);
    groups.audit(
        before.groupId(), actor, id, "EXPENSE_EDITED", "Before: " + before + "\nAfter: " + after);
    return after;
  }

  @Transactional
  public Expense voidExpense(UUID actor, UUID id, int version) {
    Expense before = editable(actor, id, version);
    db.sql("UPDATE expense SET voided_at=now(), version=version+1 WHERE id=?").param(id).update();
    Expense after = row(id);
    groups.audit(
        before.groupId(), actor, id, "EXPENSE_VOIDED", "Before: " + before + "\nAfter: " + after);
    return after;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Balances balances(UUID actor, UUID group) {
    groups.membership(actor, group);
    List<Balance> balances =
        db.sql(
                """
        WITH entries AS (
          SELECT payer_member_id AS member_id, amount_minor FROM expense WHERE group_id=? AND voided_at IS NULL
          UNION ALL SELECT s.member_id, -s.amount_minor FROM expense_share s JOIN expense e ON e.id=s.expense_id
            WHERE e.group_id=? AND e.voided_at IS NULL
          UNION ALL SELECT sender_member_id, amount_minor FROM settlement WHERE group_id=? AND status='CONFIRMED'
          UNION ALL SELECT recipient_member_id, -amount_minor FROM settlement WHERE group_id=? AND status='CONFIRMED'
        )
        SELECT m.id, COALESCE(sum(e.amount_minor), 0)::bigint AS amount_minor FROM group_member m
          LEFT JOIN entries e ON e.member_id=m.id WHERE m.group_id=? GROUP BY m.id ORDER BY m.id
        """)
            .params(group, group, group, group, group)
            .query((r, n) -> new Balance(r.getObject("id", UUID.class), r.getLong("amount_minor")))
            .list();
    return new Balances(balances, Money.suggest(balances));
  }

  public Page<Settlement> settlements(UUID actor, UUID group, int offset) {
    groups.membership(actor, group);
    return Groups.page(
        db.sql(
                "SELECT * FROM settlement WHERE group_id=? ORDER BY created_at DESC, id DESC LIMIT 51 OFFSET ?")
            .params(group, offset)
            .query(SETTLEMENT)
            .list());
  }

  private Settlement settlement(UUID id) {
    return db.sql("SELECT * FROM settlement WHERE id=?")
        .param(id)
        .query(SETTLEMENT)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  @Transactional
  public Settlement repay(UUID actor, UUID group, UUID key, SettlementInput input) {
    Member sender = groups.membership(actor, group);
    groups.writable(group);
    UUID previous = retry(actor, key, "settlement:" + group, input.toString());
    if (previous != null) return settlement(previous);
    if (sender.id().equals(input.recipientMemberId())
        || groups.members(actor, group).stream()
            .noneMatch(m -> m.id().equals(input.recipientMemberId())))
      throw ApiException.invalid("INVALID_RECIPIENT");
    UUID id = UUID.randomUUID();
    db.sql(
            """
        INSERT INTO settlement(id, group_id, sender_member_id, recipient_member_id, amount_minor, method)
        VALUES (?, ?, ?, ?, ?, ?)
        """)
        .params(
            id,
            group,
            sender.id(),
            input.recipientMemberId(),
            Money.cents(input.amount(), false),
            input.method().name())
        .update();
    Settlement result = settlement(id);
    groups.audit(group, actor, id, "SETTLEMENT_RECORDED", result.toString());
    complete(actor, key, id);
    return result;
  }

  @Transactional
  public Settlement transition(UUID actor, UUID id, String action) {
    Settlement initial = settlement(id);
    Member member = groups.membership(actor, initial.groupId());
    String target =
        switch (action) {
          case "confirm" -> "CONFIRMED";
          case "reject" -> "REJECTED";
          case "cancel" -> "CANCELLED";
          default -> throw ApiException.missing();
        };
    UUID permitted =
        target.equals("CANCELLED") ? initial.senderMemberId() : initial.recipientMemberId();
    if (!member.id().equals(permitted)) throw ApiException.missing();
    groups.writable(initial.groupId());
    Settlement current =
        db.sql("SELECT * FROM settlement WHERE id=? FOR UPDATE")
            .param(id)
            .query(SETTLEMENT)
            .single();
    if (current.status().equals(target)) return current;
    if (!current.status().equals("PENDING")) throw ApiException.conflict("SETTLEMENT_FINAL");
    db.sql(
            """
        UPDATE settlement SET status=?, version=version+1,
          confirmed_at=CASE WHEN ?='CONFIRMED' THEN now() ELSE NULL END WHERE id=?
        """)
        .params(target, target, id)
        .update();
    Settlement result = settlement(id);
    groups.audit(
        current.groupId(),
        actor,
        id,
        "SETTLEMENT_" + target,
        "Before: " + current + "\nAfter: " + result);
    return result;
  }
}
