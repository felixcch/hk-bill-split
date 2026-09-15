package hk.billsplit;

import static hk.billsplit.Api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "app.auth.issuer=https://auth.example.test/auth/v1",
      "app.auth.jwk-set-uri=https://auth.example.test/auth/v1/.well-known/jwks.json"
    })
@AutoConfigureMockMvc
@Testcontainers
class LedgerIntegrationTest {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired Groups groups;
  @Autowired Ledger ledger;
  @Autowired JdbcClient db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  private UUID alice, bob, carol, outsider, group;
  private Member a, b, c;

  @BeforeEach
  void setup() {
    db.sql("TRUNCATE app_user, split_group CASCADE").update();
    alice = UUID.randomUUID();
    bob = UUID.randomUUID();
    carol = UUID.randomUUID();
    outsider = UUID.randomUUID();
    groups.profile(alice, new Profile("Alice"));
    groups.profile(bob, new Profile("Bob"));
    groups.profile(carol, new Profile("Carol"));
    groups.profile(outsider, new Profile("Outsider"));
    group = groups.create(alice, new GroupInput("Dinner")).id();
    groups.accept(bob, groups.invite(alice, group).token());
    groups.accept(carol, groups.invite(alice, group).token());
    a = groups.membership(alice, group);
    b = groups.membership(bob, group);
    c = groups.membership(carol, group);
  }

  private ExpenseInput dinner(String amount, Integer version) {
    return new ExpenseInput(
        a.id(),
        "Dinner",
        amount,
        SplitMethod.EQUAL,
        LocalDate.of(2026, 1, 1),
        List.of(
            new Participant(a.id(), null),
            new Participant(b.id(), null),
            new Participant(c.id(), null)),
        version);
  }

  private long balance(UUID member) {
    return ledger.balances(alice, group).balances().stream()
        .filter(x -> x.memberId().equals(member))
        .findFirst()
        .orElseThrow()
        .amountMinor();
  }

  @Test
  void completeDinnerAndConfirmedRepaymentsSettleToZero() {
    ledger.createExpense(alice, group, UUID.randomUUID(), dinner("1500.00", null));
    assertThat(balance(a.id())).isEqualTo(100000);
    assertThat(balance(b.id())).isEqualTo(-50000);
    Settlement payment =
        ledger.repay(
            bob,
            group,
            UUID.randomUUID(),
            new SettlementInput(a.id(), "500.00", PaymentMethod.FPS));
    assertThat(balance(b.id())).isEqualTo(-50000);
    assertThatThrownBy(() -> ledger.transition(bob, payment.id(), "confirm"))
        .isInstanceOf(ApiException.class);
    ledger.transition(alice, payment.id(), "confirm");
    ledger.transition(alice, payment.id(), "confirm");
    assertThat(balance(b.id())).isZero();
    Settlement last =
        ledger.repay(
            carol,
            group,
            UUID.randomUUID(),
            new SettlementInput(a.id(), "500.00", PaymentMethod.PAYME));
    ledger.transition(alice, last.id(), "confirm");
    assertThat(ledger.balances(alice, group).balances()).allMatch(x -> x.amountMinor() == 0);
    assertThat(ledger.balances(alice, group).suggestions()).isEmpty();
    assertThat(
            db.sql("SELECT count(*) FROM audit_event WHERE action='SETTLEMENT_CONFIRMED'")
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void retriesReturnOneRecordAndChangedPayloadConflicts() {
    UUID key = UUID.randomUUID();
    Expense first = ledger.createExpense(alice, group, key, dinner("30", null));
    assertThat(ledger.createExpense(alice, group, key, dinner("30", null)).id())
        .isEqualTo(first.id());
    assertThatThrownBy(() -> ledger.createExpense(alice, group, key, dinner("31", null)))
        .isInstanceOf(ApiException.class)
        .hasMessage("IDEMPOTENCY_CONFLICT");
    assertThat(ledger.expenses(alice, group, 0).items()).hasSize(1);
    UUID repaymentKey = UUID.randomUUID();
    SettlementInput payment = new SettlementInput(a.id(), "10", PaymentMethod.CASH);
    Settlement repayment = ledger.repay(bob, group, repaymentKey, payment);
    assertThat(ledger.repay(bob, group, repaymentKey, payment).id()).isEqualTo(repayment.id());
    assertThat(ledger.settlements(alice, group, 0).items()).hasSize(1);
  }

  @Test
  void invalidSharesRollBackExpenseAuditAndIdempotency() {
    ExpenseInput invalid =
        new ExpenseInput(
            a.id(),
            "Bad total",
            "10",
            SplitMethod.EXACT,
            LocalDate.of(2026, 1, 1),
            List.of(new Participant(a.id(), "9.99")),
            null);
    assertThatThrownBy(() -> ledger.createExpense(alice, group, UUID.randomUUID(), invalid))
        .isInstanceOf(ApiException.class);
    assertThat(db.sql("SELECT count(*) FROM expense").query(Long.class).single()).isZero();
    assertThat(db.sql("SELECT count(*) FROM idempotency").query(Long.class).single()).isZero();
    assertThat(
            db.sql("SELECT count(*) FROM audit_event WHERE action='EXPENSE_CREATED'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void editsRejectStaleVersionsAndVoidsKeepConfirmedRepayments() {
    Expense expense = ledger.createExpense(alice, group, UUID.randomUUID(), dinner("30", null));
    assertThatThrownBy(() -> ledger.edit(bob, expense.id(), dinner("60", 0)))
        .isInstanceOf(ApiException.class);
    Expense edited = ledger.edit(alice, expense.id(), dinner("60", 0));
    assertThat(edited.version()).isEqualTo(1);
    assertThat(balance(b.id())).isEqualTo(-2000);
    assertThatThrownBy(() -> ledger.edit(alice, expense.id(), dinner("90", 0)))
        .hasMessage("STALE_VERSION");
    Settlement payment =
        ledger.repay(
            bob, group, UUID.randomUUID(), new SettlementInput(a.id(), "20", PaymentMethod.FPS));
    ledger.transition(alice, payment.id(), "confirm");
    ledger.voidExpense(alice, expense.id(), 1);
    assertThat(balance(b.id())).isEqualTo(2000);
    assertThat(balance(a.id())).isEqualTo(-2000);
    assertThat(ledger.expense(alice, expense.id()).voidedAt()).isNotNull();
    assertThat(groups.history(alice, group, 0).items())
        .anyMatch(
            x ->
                x.action().equals("EXPENSE_EDITED")
                    && x.detail().contains("Before:")
                    && x.detail().contains("After:"));
  }

  @Test
  void rejectedCancelledAndOverpaidRepaymentsHaveExplicitEffects() {
    ledger.createExpense(alice, group, UUID.randomUUID(), dinner("30", null));
    Settlement rejected =
        ledger.repay(
            bob, group, UUID.randomUUID(), new SettlementInput(a.id(), "10", PaymentMethod.CASH));
    ledger.transition(alice, rejected.id(), "reject");
    assertThatThrownBy(() -> ledger.transition(alice, rejected.id(), "confirm"))
        .hasMessage("SETTLEMENT_FINAL");
    Settlement cancelled =
        ledger.repay(
            bob, group, UUID.randomUUID(), new SettlementInput(a.id(), "10", PaymentMethod.CASH));
    ledger.transition(bob, cancelled.id(), "cancel");
    assertThat(balance(b.id())).isEqualTo(-1000);
    Settlement overpaid =
        ledger.repay(
            bob, group, UUID.randomUUID(), new SettlementInput(a.id(), "20", PaymentMethod.CASH));
    ledger.transition(alice, overpaid.id(), "confirm");
    assertThat(balance(b.id())).isEqualTo(1000);
    assertThat(
            ledger.balances(alice, group).balances().stream().mapToLong(Balance::amountMinor).sum())
        .isZero();
  }

  @Test
  void crossGroupAccessAndParticipantsAreRejected() throws Exception {
    Expense expense = ledger.createExpense(alice, group, UUID.randomUUID(), dinner("30", null));
    for (String path :
        List.of(
            "/groups/" + group,
            "/groups/" + group + "/members",
            "/groups/" + group + "/expenses",
            "/groups/" + group + "/balances",
            "/groups/" + group + "/settlements",
            "/groups/" + group + "/audit",
            "/expenses/" + expense.id())) {
      mvc.perform(get("/api/v1" + path).with(jwt().jwt(j -> j.subject(outsider.toString()))))
          .andExpect(status().isNotFound());
    }
    UUID foreignGroup = groups.create(outsider, new GroupInput("Other")).id();
    Member foreign = groups.membership(outsider, foreignGroup);
    ExpenseInput invalid =
        new ExpenseInput(
            a.id(),
            "Cross group",
            "10",
            SplitMethod.EQUAL,
            LocalDate.of(2026, 1, 1),
            List.of(new Participant(foreign.id(), null)),
            null);
    assertThatThrownBy(() -> ledger.createExpense(alice, group, UUID.randomUUID(), invalid))
        .hasMessage("INVALID_MEMBER");
    assertThatThrownBy(() -> groups.invite(bob, group)).isInstanceOf(ApiException.class);
  }

  @Test
  void inviteHashExpirationRevocationAndSingleUseAreEnforced() {
    Invite i = groups.invite(alice, group);
    assertThat(
            db.sql("SELECT token_hash FROM invitation WHERE id=?")
                .param(i.id())
                .query(String.class)
                .single())
        .isNotEqualTo(i.token())
        .hasSize(64);
    groups.accept(outsider, i.token());
    groups.accept(outsider, i.token());
    assertThatThrownBy(() -> groups.accept(bob, i.token())).hasMessage("INVITE_USED");
    Invite expired = groups.invite(alice, group);
    db.sql("UPDATE invitation SET expires_at=now()-interval '1 day' WHERE id=?")
        .param(expired.id())
        .update();
    assertThatThrownBy(() -> groups.accept(outsider, expired.token())).hasMessage("INVITE_INVALID");
    Invite revoked = groups.invite(alice, group);
    groups.revoke(alice, group, revoked.id());
    assertThatThrownBy(() -> groups.accept(outsider, revoked.token())).hasMessage("INVITE_INVALID");
  }

  private <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<T> a =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return first.call();
              });
      Future<T> b =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return second.call();
              });
      return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }
  }

  @Test
  void concurrentConfirmationsAndDuplicateCreatesApplyOnce() throws Exception {
    UUID key = UUID.randomUUID();
    List<Expense> expenses =
        concurrent(
            () -> ledger.createExpense(alice, group, key, dinner("30", null)),
            () -> ledger.createExpense(alice, group, key, dinner("30", null)));
    assertThat(expenses.getFirst().id()).isEqualTo(expenses.getLast().id());
    Settlement payment =
        ledger.repay(
            bob, group, UUID.randomUUID(), new SettlementInput(a.id(), "10", PaymentMethod.FPS));
    concurrent(
        () -> ledger.transition(alice, payment.id(), "confirm"),
        () -> ledger.transition(alice, payment.id(), "confirm"));
    assertThat(balance(b.id())).isZero();
    assertThat(
            db.sql("SELECT count(*) FROM audit_event WHERE action='SETTLEMENT_CONFIRMED'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void authenticationAndApiValidationAreEnforced() throws Exception {
    mvc.perform(get("/api/v1/groups")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/groups").header("X-Demo-User", alice))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.demo").value(false))
        .andExpect(jsonPath("$.demoUsers").isEmpty());
    mvc.perform(get("/health")).andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/groups")
                .with(jwt().jwt(j -> j.subject(alice.toString())))
                .contentType("application/json")
                .content("{\"name\":\"   \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/groups/" + group + "/expenses")
                .with(jwt().jwt(j -> j.subject(alice.toString())))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/groups/" + group + "/expenses?offset=-1")
                .with(jwt().jwt(j -> j.subject(alice.toString()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void apiContractCreatesAnExpenseAndConfirmsItsRepayment() throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/groups/" + group + "/expenses")
                    .with(jwt().jwt(j -> j.subject(alice.toString())))
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(json.writeValueAsString(dinner("30", null))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amountMinor").value(3000))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Expense created = json.readValue(response, Expense.class);
    assertThat(created.shares()).hasSize(3);
    String repayment =
        mvc.perform(
                post("/api/v1/groups/" + group + "/settlements")
                    .with(jwt().jwt(j -> j.subject(bob.toString())))
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            new SettlementInput(a.id(), "10", PaymentMethod.FPS))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Settlement payment = json.readValue(repayment, Settlement.class);
    mvc.perform(
            post("/api/v1/settlements/" + payment.id() + "/confirm")
                .with(jwt().jwt(j -> j.subject(alice.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    assertThat(balance(b.id())).isZero();
    String invalid =
        "{\"description\":\"Null participant\",\"amount\":\"1\",\"payerMemberId\":\""
            + a.id()
            + "\",\"splitMethod\":\"EQUAL\",\"incurredOn\":\"2026-01-01\",\"participants\":[null]}";
    mvc.perform(
            post("/api/v1/groups/" + group + "/expenses")
                .with(jwt().jwt(j -> j.subject(alice.toString())))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(invalid))
        .andExpect(status().isBadRequest());
  }

  @Test
  void concurrentEditsAllowOnlyOneVersionAndKeepSharesConsistent() throws Exception {
    Expense initial = ledger.createExpense(alice, group, UUID.randomUUID(), dinner("30", null));
    Callable<String> edit =
        () -> {
          try {
            return ledger.edit(alice, initial.id(), dinner("60", 0)).description();
          } catch (ApiException error) {
            return error.getMessage();
          }
        };
    assertThat(concurrent(edit, edit)).containsExactlyInAnyOrder("Dinner", "STALE_VERSION");
    assertThat(
            ledger.expense(alice, initial.id()).shares().stream()
                .mapToLong(Share::amountMinor)
                .sum())
        .isEqualTo(6000);
    assertThat(balance(a.id())).isEqualTo(4000);
  }

  @Test
  void concurrentInvitationRedemptionAdmitsOneNewMember() throws Exception {
    UUID stranger = UUID.randomUUID();
    groups.profile(stranger, new Profile("Stranger"));
    Invite invite = groups.invite(alice, group);
    Callable<String> one =
        () -> {
          try {
            return groups.accept(outsider, invite.token()).name();
          } catch (ApiException error) {
            return error.getMessage();
          }
        };
    Callable<String> two =
        () -> {
          try {
            return groups.accept(stranger, invite.token()).name();
          } catch (ApiException error) {
            return error.getMessage();
          }
        };
    assertThat(concurrent(one, two)).containsExactlyInAnyOrder("Dinner", "INVITE_USED");
    assertThat(groups.members(alice, group)).hasSize(4);
  }
}
