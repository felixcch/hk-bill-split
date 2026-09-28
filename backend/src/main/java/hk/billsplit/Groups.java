package hk.billsplit;

import static hk.billsplit.Api.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Groups are keyed by a secret link code; whoever holds the link is a full participant. */
@Service
public class Groups {
  static final RowMapper<Member> MEMBER =
      (r, n) -> new Member(r.getObject("id", UUID.class), r.getString("name"));
  private final JdbcClient db;
  private final SecureRandom random = new SecureRandom();

  public Groups(JdbcClient db) {
    this.db = db;
  }

  static Instant instant(ResultSet r, String field) throws SQLException {
    Timestamp t = r.getTimestamp(field);
    return t == null ? null : t.toInstant();
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String clean(String name) {
    String value = name.strip().replaceAll("\\s+", " ");
    if (value.isEmpty()) throw ApiException.invalid("INVALID_INPUT");
    return value;
  }

  private static String emoji(String value) {
    return value == null || value.isBlank() ? "🐻" : value.strip();
  }

  @Transactional
  public Group create(GroupInput input) {
    byte[] bytes = new byte[20];
    random.nextBytes(bytes);
    String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    UUID id = UUID.randomUUID();
    db.sql("INSERT INTO split_group(id, code_hash, name, emoji) VALUES (?, ?, ?, ?)")
        .params(id, hash(code), clean(input.name()), emoji(input.emoji()))
        .update();
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (String name : input.members()) names.add(clean(name));
    for (String name : names) insertMember(id, name);
    activity(id, null, id, "GROUP_CREATED", input.name());
    return group(id, code);
  }

  private Member insertMember(UUID group, String name) {
    UUID id = UUID.randomUUID();
    try {
      db.sql(
              """
              INSERT INTO member(id, group_id, name, position)
              SELECT ?, ?, ?, COALESCE(MAX(position), 0) + 1 FROM member WHERE group_id=?
              """)
          .params(id, group, name, group)
          .update();
    } catch (DuplicateKeyException e) {
      throw ApiException.conflict("DUPLICATE_MEMBER");
    }
    return new Member(id, name);
  }

  private Group group(UUID id, String code) {
    return db.sql("SELECT id, name, emoji, currency FROM split_group WHERE id=?")
        .param(id)
        .query(
            (r, n) ->
                new Group(
                    r.getObject("id", UUID.class),
                    code,
                    r.getString("name"),
                    r.getString("emoji"),
                    r.getString("currency")))
        .optional()
        .orElseThrow(ApiException::missing);
  }

  /**
   * Resolves a link code to the group id. Unknown codes are indistinguishable from missing groups.
   */
  public UUID resolve(String code) {
    if (code == null || !code.matches("[A-Za-z0-9_-]{20,64}")) throw ApiException.missing();
    return db.sql("SELECT id FROM split_group WHERE code_hash=?")
        .param(hash(code))
        .query(UUID.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  /** Serializes writes within one group so balance-affecting changes never interleave. */
  void lock(UUID group) {
    db.sql("SELECT id FROM split_group WHERE id=? FOR UPDATE")
        .param(group)
        .query(UUID.class)
        .optional()
        .orElseThrow(ApiException::missing);
    db.sql("UPDATE split_group SET updated_at=now() WHERE id=?").param(group).update();
  }

  public Group read(String code) {
    return group(resolve(code), code);
  }

  @Transactional
  public Group rename(String code, GroupPatch input) {
    UUID id = resolve(code);
    lock(id);
    db.sql("UPDATE split_group SET name=?, emoji=? WHERE id=?")
        .params(clean(input.name()), emoji(input.emoji()), id)
        .update();
    activity(id, null, id, "GROUP_RENAMED", input.name());
    return group(id, code);
  }

  public List<Member> members(UUID group) {
    return db.sql("SELECT id, name FROM member WHERE group_id=? ORDER BY position")
        .param(group)
        .query(MEMBER)
        .list();
  }

  Member member(UUID group, UUID id) {
    return db.sql("SELECT id, name FROM member WHERE group_id=? AND id=?")
        .params(group, id)
        .query(MEMBER)
        .optional()
        .orElseThrow(() -> ApiException.invalid("UNKNOWN_MEMBER"));
  }

  @Transactional
  public Member addMember(UUID group, UUID actor, MemberInput input) {
    lock(group);
    if (members(group).size() >= 50) throw ApiException.invalid("TOO_MANY_MEMBERS");
    Member member = insertMember(group, clean(input.name()));
    activity(group, actor, member.id(), "MEMBER_ADDED", member.name());
    return member;
  }

  @Transactional
  public Member renameMember(UUID group, UUID actor, UUID id, MemberInput input) {
    lock(group);
    member(group, id);
    String name = clean(input.name());
    try {
      db.sql("UPDATE member SET name=? WHERE id=? AND group_id=?").params(name, id, group).update();
    } catch (DuplicateKeyException e) {
      throw ApiException.conflict("DUPLICATE_MEMBER");
    }
    activity(group, actor, id, "MEMBER_RENAMED", name);
    return new Member(id, name);
  }

  void activity(UUID group, UUID actor, UUID entity, String action, String detail) {
    db.sql(
            """
        INSERT INTO activity(id, group_id, actor_member_id, entity_id, action, detail)
        VALUES (?, ?, ?, ?, ?, ?)
        """)
        .params(UUID.randomUUID(), group, actor, entity, action, detail)
        .update();
  }

  public Page<Activity> activity(UUID group, int offset) {
    return page(
        db.sql(
                """
        SELECT * FROM activity WHERE group_id=? ORDER BY created_at DESC, id DESC LIMIT 51 OFFSET ?
        """)
            .params(group, offset)
            .query(
                (r, n) ->
                    new Activity(
                        r.getObject("id", UUID.class),
                        r.getObject("actor_member_id", UUID.class),
                        r.getObject("entity_id", UUID.class),
                        r.getString("action"),
                        r.getString("detail"),
                        instant(r, "created_at")))
            .list());
  }

  static <T> Page<T> page(List<T> rows) {
    return new Page<>(rows.size() > 50 ? rows.subList(0, 50) : rows, rows.size() > 50);
  }
}
