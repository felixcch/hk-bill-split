package hk.billsplit;

import static hk.billsplit.Api.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class Controller {
  private final Groups groups;
  private final Ledger ledger;
  private final JdbcClient db;
  private final Environment environment;
  private final String supabaseUrl;
  private final String publishableKey;

  public Controller(
      Groups groups,
      Ledger ledger,
      JdbcClient db,
      Environment environment,
      @Value("${app.supabase-url}") String supabaseUrl,
      @Value("${app.supabase-publishable-key}") String publishableKey) {
    this.groups = groups;
    this.ledger = ledger;
    this.db = db;
    this.environment = environment;
    this.supabaseUrl = supabaseUrl;
    this.publishableKey = publishableKey;
  }

  private UUID actor(Principal principal) {
    try {
      return UUID.fromString(principal.getName());
    } catch (IllegalArgumentException e) {
      throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }
  }

  public record Config(
      String supabaseUrl,
      String supabasePublishableKey,
      boolean demo,
      List<Demo.Identity> demoUsers) {}

  @GetMapping("/api/config")
  Config config() {
    boolean demo = environment.matchesProfiles("demo");
    return new Config(supabaseUrl, publishableKey, demo, demo ? Demo.USERS : List.of());
  }

  @GetMapping("/health")
  Map<String, String> health() {
    db.sql("SELECT 1").query(Integer.class).single();
    return Map.of("status", "ok");
  }

  @GetMapping("/api/v1/me")
  User me(Principal p) {
    return groups.user(actor(p));
  }

  @PutMapping("/api/v1/me")
  User profile(Principal p, @RequestBody @Valid Profile input) {
    return groups.profile(actor(p), input);
  }

  @GetMapping("/api/v1/groups")
  List<Group> list(Principal p) {
    return groups.list(actor(p));
  }

  @PostMapping("/api/v1/groups")
  Group create(Principal p, @RequestBody @Valid GroupInput input) {
    return groups.create(actor(p), input);
  }

  @GetMapping("/api/v1/groups/{group}")
  Group get(Principal p, @PathVariable UUID group) {
    return groups.get(actor(p), group);
  }

  @GetMapping("/api/v1/groups/{group}/members")
  List<Member> members(Principal p, @PathVariable UUID group) {
    return groups.members(actor(p), group);
  }

  @GetMapping("/api/v1/groups/{group}/invitations")
  List<InviteSummary> invites(Principal p, @PathVariable UUID group) {
    return groups.invitations(actor(p), group);
  }

  @PostMapping("/api/v1/groups/{group}/invitations")
  Invite invite(Principal p, @PathVariable UUID group) {
    return groups.invite(actor(p), group);
  }

  @PostMapping("/api/v1/groups/{group}/invitations/{id}/revoke")
  void revoke(Principal p, @PathVariable UUID group, @PathVariable UUID id) {
    groups.revoke(actor(p), group, id);
  }

  @PostMapping("/api/v1/invitations/accept")
  Group accept(Principal p, @RequestBody @Valid AcceptInvite input) {
    return groups.accept(actor(p), input.token());
  }

  @GetMapping("/api/v1/groups/{group}/expenses")
  Page<Expense> expenses(
      Principal p,
      @PathVariable UUID group,
      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset) {
    return ledger.expenses(actor(p), group, offset);
  }

  @GetMapping("/api/v1/expenses/{id}")
  Expense expense(Principal p, @PathVariable UUID id) {
    return ledger.expense(actor(p), id);
  }

  @PostMapping("/api/v1/groups/{group}/expenses")
  Expense addExpense(
      Principal p,
      @PathVariable UUID group,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody @Valid ExpenseInput input) {
    return ledger.createExpense(actor(p), group, key, input);
  }

  @PatchMapping("/api/v1/expenses/{id}")
  Expense edit(Principal p, @PathVariable UUID id, @RequestBody @Valid ExpenseInput input) {
    return ledger.edit(actor(p), id, input);
  }

  @PostMapping("/api/v1/expenses/{id}/void")
  Expense voidExpense(Principal p, @PathVariable UUID id, @RequestBody @Valid Version input) {
    return ledger.voidExpense(actor(p), id, input.version());
  }

  @GetMapping("/api/v1/groups/{group}/balances")
  Balances balances(Principal p, @PathVariable UUID group) {
    return ledger.balances(actor(p), group);
  }

  @GetMapping("/api/v1/groups/{group}/settlements")
  Page<Settlement> settlements(
      Principal p,
      @PathVariable UUID group,
      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset) {
    return ledger.settlements(actor(p), group, offset);
  }

  @PostMapping("/api/v1/groups/{group}/settlements")
  Settlement repay(
      Principal p,
      @PathVariable UUID group,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody @Valid SettlementInput input) {
    return ledger.repay(actor(p), group, key, input);
  }

  @PostMapping("/api/v1/settlements/{id}/{action}")
  Settlement transition(Principal p, @PathVariable UUID id, @PathVariable String action) {
    return ledger.transition(actor(p), id, action);
  }

  @GetMapping("/api/v1/groups/{group}/audit")
  Page<Audit> audit(
      Principal p,
      @PathVariable UUID group,
      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset) {
    return groups.history(actor(p), group, offset);
  }
}
