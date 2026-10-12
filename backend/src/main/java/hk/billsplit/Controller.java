package hk.billsplit;

import static hk.billsplit.Api.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class Controller {
  private final Groups groups;
  private final Ledger ledger;
  private final JdbcClient db;

  public Controller(Groups groups, Ledger ledger, JdbcClient db) {
    this.groups = groups;
    this.ledger = ledger;
    this.db = db;
  }

  @GetMapping("/health")
  Map<String, String> health() {
    db.sql("SELECT 1").query(Integer.class).single();
    return Map.of("status", "ok");
  }

  @PostMapping("/api/v1/groups")
  Group create(@Valid @RequestBody GroupInput input) {
    return groups.create(input);
  }

  @GetMapping("/api/v1/g/{code}")
  Snapshot snapshot(@PathVariable String code) {
    return ledger.snapshot(code);
  }

  @PatchMapping("/api/v1/g/{code}")
  Group rename(@PathVariable String code, @Valid @RequestBody GroupPatch input) {
    return groups.rename(code, input);
  }

  @PostMapping("/api/v1/g/{code}/members")
  Member addMember(
      @PathVariable String code,
      @RequestHeader(value = "X-Member", required = false) UUID actor,
      @Valid @RequestBody MemberInput input) {
    return groups.addMember(groups.resolve(code), actor, input);
  }

  @PatchMapping("/api/v1/g/{code}/members/{id}")
  Member renameMember(
      @PathVariable String code,
      @PathVariable UUID id,
      @RequestHeader(value = "X-Member", required = false) UUID actor,
      @Valid @RequestBody MemberInput input) {
    return groups.renameMember(groups.resolve(code), actor, id, input);
  }

  @PostMapping("/api/v1/g/{code}/expenses")
  Expense createExpense(
      @PathVariable String code,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestHeader(value = "X-Member", required = false) UUID actor,
      @Valid @RequestBody ExpenseInput input) {
    return ledger.createExpense(groups.resolve(code), actor, key, input);
  }

  @PatchMapping("/api/v1/g/{code}/expenses/{id}")
  Expense editExpense(
      @PathVariable String code,
      @PathVariable UUID id,
      @RequestHeader(value = "X-Member", required = false) UUID actor,
      @Valid @RequestBody ExpenseInput input) {
    return ledger.editExpense(groups.resolve(code), actor, id, input);
  }

  @DeleteMapping("/api/v1/g/{code}/expenses/{id}")
  Map<String, String> deleteExpense(
      @PathVariable String code,
      @PathVariable UUID id,
      @RequestHeader(value = "X-Member", required = false) UUID actor) {
    ledger.deleteExpense(groups.resolve(code), actor, id);
    return Map.of("status", "deleted");
  }

  @PostMapping("/api/v1/g/{code}/transfers")
  Transfer createTransfer(
      @PathVariable String code,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestHeader(value = "X-Member", required = false) UUID actor,
      @Valid @RequestBody TransferInput input) {
    return ledger.createTransfer(groups.resolve(code), actor, key, input);
  }

  @DeleteMapping("/api/v1/g/{code}/transfers/{id}")
  Map<String, String> deleteTransfer(
      @PathVariable String code,
      @PathVariable UUID id,
      @RequestHeader(value = "X-Member", required = false) UUID actor) {
    ledger.deleteTransfer(groups.resolve(code), actor, id);
    return Map.of("status", "deleted");
  }

  @GetMapping("/api/v1/g/{code}/activity")
  Page<Activity> activity(
      @PathVariable String code,
      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset) {
    return groups.activity(groups.resolve(code), offset);
  }
}
