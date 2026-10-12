package hk.billsplit;

import static hk.billsplit.Api.*;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Ledger {
  private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");
  private final JdbcClient db;
  private final Groups groups;

  public Ledger(JdbcClient db, Groups groups) {
    this.db = db;
    this.groups = groups;
  }

  private static void checkDate(LocalDate date) {
    LocalDate today = LocalDate.now(HONG_KONG);
    if (date.isBefore(LocalDate.of(2000, 1, 1)) || date.isAfter(today))
      throw ApiException.invalid("INVALID_DATE");
  }

  /** Returns a previously completed result for a repeated key, or null when the key is fresh. */
  private UUID retry(UUID group, UUID key, String operation, String payload) {
    String fingerprint = Groups.hash(operation + "\n" + payload);
    db.sql(
            """
        INSERT INTO idempotency(group_id, key, operation, fingerprint) VALUES (?, ?, ?, ?)
        ON CONFLICT DO NOTHING
        """)
        .params(group, key, operation, fingerprint)
        .update();
    return db.sql(
            "SELECT operation, fingerprint, result_id FROM idempotency WHERE group_id=? AND key=? FOR UPDATE")
        .params(group, key)
        .query(
            (r, n) -> {
              if (!r.getString("operation").equals(operation)
                  || !r.getString("fingerprint").equals(fingerprint))
                throw ApiException.conflict("IDEMPOTENCY_MISMATCH");
              UUID result = r.getObject("result_id", UUID.class);
              if (result == null) return Optional.<UUID>empty();
              return Optional.of(result);
            })
        .single()
        .orElse(null);
  }

  private void complete(UUID group, UUID key, UUID result) {
    db.sql("UPDATE idempotency SET result_id=? WHERE group_id=? AND key=?")
        .params(result, group, key)
        .update();
  }

  private List<Share> validate(UUID group, ExpenseInput input) {
    groups.member(group, input.payerMemberId());
    for (Participant p : input.participants()) groups.member(group, p.memberId());
    checkDate(input.incurredOn());
    return Money.split(
        Money.cents(input.amount(), false), input.splitMethod(), input.participants());
  }

  @Transactional
  public Expense createExpense(UUID group, UUID actor, UUID key, ExpenseInput input) {
    groups.lock(group);
    UUID previous = retry(group, key, "CREATE_EXPENSE", input.toString());
    if (previous != null) return expense(group, previous);
    List<Share> shares = validate(group, input);
    UUID id = UUID.randomUUID();
    db.sql(
            """
        INSERT INTO expense(id, group_id, payer_member_id, description, category, amount_minor,
          split_method, incurred_on) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """)
        .params(
            id,
            group,
            input.payerMemberId(),
            Groups.clean(input.description()),
            input.category().name(),
            Money.cents(input.amount(), false),
            input.splitMethod().name(),
            input.incurredOn())
        .update();
    saveShares(id, group, shares);
    Expense result = expense(group, id);
    groups.activity(group, actor, id, "EXPENSE_ADDED", result.toString());
    complete(group, key, id);
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

  private Map<UUID, List<Share>> shares(UUID group) {
    Map<UUID, List<Share>> result = new HashMap<>();
    db.sql(
            "SELECT expense_id, member_id, amount_minor FROM expense_share WHERE group_id=? ORDER BY member_id")
        .param(group)
        .query(
            (r, n) -> {
              result
                  .computeIfAbsent(r.getObject("expense_id", UUID.class), k -> new ArrayList<>())
                  .add(new Share(r.getObject("member_id", UUID.class), r.getLong("amount_minor")));
              return n;
            })
        .list();
    return result;
  }

  private List<Expense> expenses(UUID group, UUID only) {
    Map<UUID, List<Share>> shares = shares(group);
    return db.sql(
            """
        SELECT * FROM expense WHERE group_id=? AND deleted_at IS NULL AND (?::uuid IS NULL OR id=?::uuid)
        ORDER BY incurred_on DESC, created_at DESC, id DESC
        """)
        .params(group, only, only)
        .query(
            (r, n) -> {
              UUID id = r.getObject("id", UUID.class);
              return new Expense(
                  id,
                  r.getObject("payer_member_id", UUID.class),
                  r.getString("description"),
                  Category.valueOf(r.getString("category")),
                  r.getLong("amount_minor"),
                  SplitMethod.valueOf(r.getString("split_method")),
                  r.getObject("incurred_on", LocalDate.class),
                  r.getInt("version"),
                  Groups.instant(r, "created_at"),
                  shares.getOrDefault(id, List.of()));
            })
        .list();
  }

  public Expense expense(UUID group, UUID id) {
    return expenses(group, id).stream().findFirst().orElseThrow(ApiException::missing);
  }

  @Transactional
  public Expense editExpense(UUID group, UUID actor, UUID id, ExpenseInput input) {
    if (input.version() == null) throw ApiException.invalid("VERSION_REQUIRED");
    groups.lock(group);
    Expense before = expense(group, id);
    if (before.version() != input.version()) throw ApiException.conflict("STALE_VERSION");
    List<Share> shares = validate(group, input);
    db.sql(
            """
        UPDATE expense SET payer_member_id=?, description=?, category=?, amount_minor=?,
          split_method=?, incurred_on=?, version=version+1 WHERE id=? AND group_id=?
        """)
        .params(
            input.payerMemberId(),
            Groups.clean(input.description()),
            input.category().name(),
            Money.cents(input.amount(), false),
            input.splitMethod().name(),
            input.incurredOn(),
            id,
            group)
        .update();
    saveShares(id, group, shares);
    Expense after = expense(group, id);
    groups.activity(group, actor, id, "EXPENSE_EDITED", before + " -> " + after);
    return after;
  }

  @Transactional
  public void deleteExpense(UUID group, UUID actor, UUID id) {
    groups.lock(group);
    Expense before = expense(group, id);
    db.sql("UPDATE expense SET deleted_at=now() WHERE id=? AND group_id=?")
        .params(id, group)
        .update();
    groups.activity(group, actor, id, "EXPENSE_DELETED", before.toString());
  }

  @Transactional
  public Transfer createTransfer(UUID group, UUID actor, UUID key, TransferInput input) {
    groups.lock(group);
    UUID previous = retry(group, key, "CREATE_TRANSFER", input.toString());
    if (previous != null) return transfer(group, previous);
    if (input.fromMemberId().equals(input.toMemberId())) throw ApiException.invalid("SAME_MEMBER");
    groups.member(group, input.fromMemberId());
    groups.member(group, input.toMemberId());
    checkDate(input.incurredOn());
    UUID id = UUID.randomUUID();
    db.sql(
            """
        INSERT INTO transfer(id, group_id, from_member_id, to_member_id, amount_minor, method, incurred_on)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """)
        .params(
            id,
            group,
            input.fromMemberId(),
            input.toMemberId(),
            Money.cents(input.amount(), false),
            input.method().name(),
            input.incurredOn())
        .update();
    Transfer result = transfer(group, id);
    groups.activity(group, actor, id, "TRANSFER_ADDED", result.toString());
    complete(group, key, id);
    return result;
  }

  private List<Transfer> transfers(UUID group, UUID only) {
    return db.sql(
            """
        SELECT * FROM transfer WHERE group_id=? AND deleted_at IS NULL AND (?::uuid IS NULL OR id=?::uuid)
        ORDER BY incurred_on DESC, created_at DESC, id DESC
        """)
        .params(group, only, only)
        .query(
            (r, n) ->
                new Transfer(
                    r.getObject("id", UUID.class),
                    r.getObject("from_member_id", UUID.class),
                    r.getObject("to_member_id", UUID.class),
                    r.getLong("amount_minor"),
                    PaymentMethod.valueOf(r.getString("method")),
                    r.getObject("incurred_on", LocalDate.class),
                    Groups.instant(r, "created_at")))
        .list();
  }

  public Transfer transfer(UUID group, UUID id) {
    return transfers(group, id).stream().findFirst().orElseThrow(ApiException::missing);
  }

  @Transactional
  public void deleteTransfer(UUID group, UUID actor, UUID id) {
    groups.lock(group);
    Transfer before = transfer(group, id);
    db.sql("UPDATE transfer SET deleted_at=now() WHERE id=? AND group_id=?")
        .params(id, group)
        .update();
    groups.activity(group, actor, id, "TRANSFER_DELETED", before.toString());
  }

  /**
   * Balance = paid − own share + transfers sent − transfers received; positive means owed money.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot snapshot(String code) {
    Group group = groups.read(code);
    UUID id = group.id();
    List<Member> members = groups.members(id);
    List<Expense> expenses = expenses(id, null);
    List<Transfer> transfers = transfers(id, null);
    Map<UUID, long[]> totals = new LinkedHashMap<>();
    for (Member m : members) totals.put(m.id(), new long[3]);
    for (Expense e : expenses) {
      totals.get(e.payerMemberId())[1] += e.amountMinor();
      for (Share s : e.shares()) totals.get(s.memberId())[2] += s.amountMinor();
    }
    for (Transfer t : transfers) {
      totals.get(t.fromMemberId())[0] += t.amountMinor();
      totals.get(t.toMemberId())[0] -= t.amountMinor();
    }
    List<Balance> balances = new ArrayList<>();
    totals.forEach(
        (member, t) -> balances.add(new Balance(member, t[0] + t[1] - t[2], t[1], t[2])));
    return new Snapshot(group, members, expenses, transfers, balances, Money.suggest(balances));
  }
}
