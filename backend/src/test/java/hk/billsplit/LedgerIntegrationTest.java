package hk.billsplit;

import static hk.billsplit.Api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
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

  private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
  @Autowired Groups groups;
  @Autowired Ledger ledger;
  @Autowired JdbcClient db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  private Group group;
  private UUID id;
  private Member a, b, c;

  @BeforeEach
  void setup() {
    db.sql("TRUNCATE split_group CASCADE").update();
    group = groups.create(new GroupInput("Dinner", null, List.of("Alice", "Bob", "Carol")));
    id = group.id();
    List<Member> members = groups.members(id);
    a = members.get(0);
    b = members.get(1);
    c = members.get(2);
  }

  private ExpenseInput dinner(String amount, Integer version) {
    return new ExpenseInput(
        a.id(),
        "Dinner",
        Category.FOOD,
        amount,
        SplitMethod.EQUAL,
        DAY,
        List.of(
            new Participant(a.id(), null),
            new Participant(b.id(), null),
            new Participant(c.id(), null)),
        version);
  }

  private long balance(UUID member) {
    return ledger.snapshot(group.code()).balances().stream()
        .filter(x -> x.memberId().equals(member))
        .mapToLong(Balance::amountMinor)
        .sum();
  }

  @Test
  void linkCodeIsTheOnlyCredentialAndUnknownCodesAreNotFound() throws Exception {
    assertThat(group.code()).matches("[A-Za-z0-9_-]{27}");
    assertThat(db.sql("SELECT code_hash FROM split_group").query(String.class).single())
        .isNotEqualTo(group.code());
    mvc.perform(get("/api/v1/g/" + group.code())).andExpect(status().isOk());
    mvc.perform(get("/api/v1/g/" + group.code().substring(0, 26) + "x"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/g/short")).andExpect(status().isNotFound());
    mvc.perform(get("/g/" + group.code())).andExpect(forwardedUrl("/index.html"));
  }

  @Test
  void expensesAndTransfersSettleToZero() {
    ledger.createExpense(id, a.id(), UUID.randomUUID(), dinner("90.00", null));
    assertThat(balance(a.id())).isEqualTo(6000);
    assertThat(balance(b.id())).isEqualTo(-3000);
    assertThat(balance(c.id())).isEqualTo(-3000);
    Snapshot snapshot = ledger.snapshot(group.code());
    assertThat(snapshot.suggestions())
        .containsExactlyInAnyOrder(
            new Suggestion(b.id(), a.id(), 3000), new Suggestion(c.id(), a.id(), 3000));
    ledger.createTransfer(
        id,
        b.id(),
        UUID.randomUUID(),
        new TransferInput(b.id(), a.id(), "30.00", PaymentMethod.FPS, DAY));
    Transfer carol =
        ledger.createTransfer(
            id,
            c.id(),
            UUID.randomUUID(),
            new TransferInput(c.id(), a.id(), "30.00", PaymentMethod.PAYME, DAY));
    assertThat(balance(a.id())).isZero();
    assertThat(balance(b.id())).isZero();
    assertThat(ledger.snapshot(group.code()).suggestions()).isEmpty();
    ledger.deleteTransfer(id, c.id(), carol.id());
    assertThat(balance(c.id())).isEqualTo(-3000);
    assertThat(balance(a.id())).isEqualTo(3000);
  }

  @Test
  void retriesReturnOneRecordAndChangedPayloadConflicts() {
    UUID key = UUID.randomUUID();
    Expense first = ledger.createExpense(id, a.id(), key, dinner("90.00", null));
    Expense second = ledger.createExpense(id, a.id(), key, dinner("90.00", null));
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(ledger.snapshot(group.code()).expenses()).hasSize(1);
    assertThatThrownBy(() -> ledger.createExpense(id, a.id(), key, dinner("91.00", null)))
        .hasMessage("IDEMPOTENCY_MISMATCH");
  }

  @Test
  void invalidInputRollsBackEverythingIncludingIdempotency() {
    UUID key = UUID.randomUUID();
    ExpenseInput bad =
        new ExpenseInput(
            a.id(),
            "Bad",
            Category.OTHER,
            "100.00",
            SplitMethod.EXACT,
            DAY,
            List.of(new Participant(a.id(), "60.00"), new Participant(b.id(), "30.00")),
            null);
    assertThatThrownBy(() -> ledger.createExpense(id, a.id(), key, bad))
        .hasMessage("SPLIT_TOTAL_MISMATCH");
    assertThat(db.sql("SELECT count(*) FROM idempotency").query(Long.class).single()).isZero();
    assertThat(db.sql("SELECT count(*) FROM expense").query(Long.class).single()).isZero();
    assertThatThrownBy(
            () ->
                ledger.createExpense(
                    id,
                    a.id(),
                    key,
                    new ExpenseInput(
                        a.id(),
                        "Future",
                        Category.OTHER,
                        "1.00",
                        SplitMethod.EQUAL,
                        LocalDate.now().plusDays(2),
                        List.of(new Participant(a.id(), null)),
                        null)))
        .hasMessage("INVALID_DATE");
    Group other = groups.create(new GroupInput("Other", "🍜", List.of("Zed")));
    UUID stranger = groups.members(other.id()).get(0).id();
    assertThatThrownBy(
            () ->
                ledger.createExpense(
                    id,
                    a.id(),
                    key,
                    new ExpenseInput(
                        stranger,
                        "Cross",
                        Category.OTHER,
                        "1.00",
                        SplitMethod.EQUAL,
                        DAY,
                        List.of(new Participant(a.id(), null)),
                        null)))
        .hasMessage("UNKNOWN_MEMBER");
    assertThatThrownBy(
            () ->
                ledger.createTransfer(
                    id,
                    a.id(),
                    key,
                    new TransferInput(a.id(), a.id(), "1.00", PaymentMethod.CASH, DAY)))
        .hasMessage("SAME_MEMBER");
  }

  @Test
  void editsRejectStaleVersionsAndDeletesRestoreBalances() {
    Expense created = ledger.createExpense(id, a.id(), UUID.randomUUID(), dinner("90.00", null));
    Expense edited = ledger.editExpense(id, b.id(), created.id(), dinner("120.00", 0));
    assertThat(edited.version()).isEqualTo(1);
    assertThat(balance(b.id())).isEqualTo(-4000);
    assertThatThrownBy(() -> ledger.editExpense(id, b.id(), created.id(), dinner("150.00", 0)))
        .hasMessage("STALE_VERSION");
    ledger.deleteExpense(id, c.id(), created.id());
    assertThat(balance(a.id())).isZero();
    assertThat(ledger.snapshot(group.code()).expenses()).isEmpty();
    assertThatThrownBy(() -> ledger.deleteExpense(id, c.id(), created.id()))
        .hasMessage("NOT_FOUND");
    assertThat(groups.activity(id, 0).items())
        .extracting(Activity::action)
        .containsExactly("EXPENSE_DELETED", "EXPENSE_EDITED", "EXPENSE_ADDED", "GROUP_CREATED");
  }

  @Test
  void membersAreUniquePerGroupAndCapped() {
    assertThatThrownBy(() -> groups.addMember(id, a.id(), new MemberInput(" alice ")))
        .hasMessage("DUPLICATE_MEMBER");
    Member dave = groups.addMember(id, a.id(), new MemberInput("  Dave   Wong "));
    assertThat(dave.name()).isEqualTo("Dave Wong");
    assertThat(groups.members(id))
        .extracting(Member::name)
        .containsExactly("Alice", "Bob", "Carol", "Dave Wong");
    assertThatThrownBy(() -> groups.renameMember(id, a.id(), dave.id(), new MemberInput("Bob")))
        .hasMessage("DUPLICATE_MEMBER");
    assertThat(groups.create(new GroupInput("Dup", null, List.of("X", "X", "Y"))).id())
        .satisfies(g -> assertThat(groups.members(g)).hasSize(2));
    assertThat(balance(dave.id())).isZero();
  }

  @Test
  void concurrentDuplicateCreatesApplyOnce() throws Exception {
    UUID key = UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<Expense>> results = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        results.add(
            pool.submit(() -> ledger.createExpense(id, a.id(), key, dinner("60.00", null))));
      Set<UUID> ids = new HashSet<>();
      for (Future<Expense> f : results) ids.add(f.get().id());
      assertThat(ids).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(ledger.snapshot(group.code()).expenses()).hasSize(1);
  }

  @Test
  void apiContractCreatesGroupExpenseAndTransfer() throws Exception {
    String created =
        mvc.perform(
                post("/api/v1/groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"Japan Trip\",\"emoji\":\"🗼\",\"members\":[\"Ka Yan\",\"Ming\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emoji").value("🗼"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String code = json.readTree(created).get("code").asString();
    String snapshot =
        mvc.perform(get("/api/v1/g/" + code))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.members.length()").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String kaYan = json.readTree(snapshot).get("members").get(0).get("id").asString();
    String ming = json.readTree(snapshot).get("members").get(1).get("id").asString();
    String expense =
        """
        {"payerMemberId":"%s","description":"Ramen","category":"FOOD","amount":"200.50",
         "splitMethod":"EQUAL","incurredOn":"2026-01-01",
         "participants":[{"memberId":"%s"},{"memberId":"%s"}]}
        """
            .formatted(kaYan, kaYan, ming);
    mvc.perform(
            post("/api/v1/g/" + code + "/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(expense))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    mvc.perform(
            post("/api/v1/g/" + code + "/expenses")
                .header("Idempotency-Key", UUID.randomUUID())
                .header("X-Member", kaYan)
                .contentType(MediaType.APPLICATION_JSON)
                .content(expense))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shares[0].amountMinor").value(10025));
    mvc.perform(
            post("/api/v1/g/" + code + "/expenses")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(expense.replace("\"200.50\"", "\"200.505\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    mvc.perform(
            post("/api/v1/g/" + code + "/transfers")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"fromMemberId":"%s","toMemberId":"%s","amount":"100.25","method":"FPS","incurredOn":"2026-01-02"}
                    """
                        .formatted(ming, kaYan)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/g/" + code))
        .andExpect(jsonPath("$.balances[0].amountMinor").value(0))
        .andExpect(jsonPath("$.balances[1].amountMinor").value(0))
        .andExpect(jsonPath("$.suggestions.length()").value(0));
    mvc.perform(get("/api/v1/g/" + code + "/activity"))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[1].actorMemberId").value(kaYan));
    mvc.perform(
            post("/api/v1/g/" + code + "/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ming\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_MEMBER"));
    mvc.perform(
            patch("/api/v1/g/" + code)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tokyo 2026\"}"))
        .andExpect(jsonPath("$.name").value("Tokyo 2026"))
        .andExpect(jsonPath("$.emoji").value("🐻"));
  }
}
