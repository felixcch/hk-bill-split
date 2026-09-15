package hk.billsplit;

import static hk.billsplit.Api.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Groups {
  static final RowMapper<Group> GROUP =
      (r, n) ->
          new Group(
              r.getObject("id", UUID.class),
              r.getString("name"),
              r.getString("currency"),
              instant(r, "archived_at"));
  static final RowMapper<Member> MEMBER =
      (r, n) ->
          new Member(
              r.getObject("id", UUID.class), r.getObject("user_id", UUID.class),
              r.getString("display_name"), r.getString("role"));
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

  @Transactional
  public User profile(UUID userId, Profile input) {
    db.sql(
            """
        INSERT INTO app_user(id, display_name) VALUES (?, ?)
        ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name
        """)
        .params(userId, input.displayName().strip())
        .update();
    return user(userId);
  }

  public User user(UUID userId) {
    return db.sql("SELECT id, display_name FROM app_user WHERE id=?")
        .param(userId)
        .query((r, n) -> new User(r.getObject("id", UUID.class), r.getString("display_name")))
        .optional()
        .orElseThrow(() -> ApiException.invalid("PROFILE_REQUIRED"));
  }

  public List<Group> list(UUID actor) {
    return db.sql(
            """
        SELECT g.* FROM split_group g JOIN group_member m ON m.group_id=g.id
        WHERE m.user_id=? AND m.inactive_at IS NULL ORDER BY g.created_at DESC, g.id
        """)
        .param(actor)
        .query(GROUP)
        .list();
  }

  @Transactional
  public Group create(UUID actor, GroupInput input) {
    user(actor);
    UUID id = UUID.randomUUID();
    db.sql("INSERT INTO split_group(id, name) VALUES (?, ?)")
        .params(id, input.name().strip())
        .update();
    db.sql("INSERT INTO group_member(id, group_id, user_id, role) VALUES (?, ?, ?, 'OWNER')")
        .params(UUID.randomUUID(), id, actor)
        .update();
    audit(id, actor, id, "GROUP_CREATED", input.name().strip());
    return get(actor, id);
  }

  public Group get(UUID actor, UUID group) {
    membership(actor, group);
    return db.sql("SELECT * FROM split_group WHERE id=?")
        .param(group)
        .query(GROUP)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  public Member membership(UUID actor, UUID group) {
    return db.sql(
            """
        SELECT m.*, u.display_name FROM group_member m JOIN app_user u ON u.id=m.user_id
        WHERE m.group_id=? AND m.user_id=? AND m.inactive_at IS NULL
        """)
        .params(group, actor)
        .query(MEMBER)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  public Member owner(UUID actor, UUID group) {
    Member m = membership(actor, group);
    if (!m.role().equals("OWNER")) throw ApiException.missing();
    return m;
  }

  public List<Member> members(UUID actor, UUID group) {
    membership(actor, group);
    return db.sql(
            """
        SELECT m.*, u.display_name FROM group_member m JOIN app_user u ON u.id=m.user_id
        WHERE m.group_id=? AND m.inactive_at IS NULL ORDER BY m.id
        """)
        .param(group)
        .query(MEMBER)
        .list();
  }

  public void writable(UUID group) {
    Group g =
        db.sql("SELECT * FROM split_group WHERE id=? FOR UPDATE")
            .param(group)
            .query(GROUP)
            .optional()
            .orElseThrow(ApiException::missing);
    if (g.archivedAt() != null) throw ApiException.conflict("GROUP_ARCHIVED");
  }

  @Transactional
  public Invite invite(UUID actor, UUID group) {
    owner(actor, group);
    writable(group);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    UUID id = UUID.randomUUID();
    Instant expires = Instant.now().plus(Duration.ofDays(7));
    db.sql("INSERT INTO invitation(id, group_id, token_hash, expires_at) VALUES (?, ?, ?, ?)")
        .params(id, group, hash(token), Timestamp.from(expires))
        .update();
    audit(group, actor, id, "INVITATION_CREATED", "Expires " + expires);
    return new Invite(id, token, expires);
  }

  public List<InviteSummary> invitations(UUID actor, UUID group) {
    owner(actor, group);
    return db.sql("SELECT * FROM invitation WHERE group_id=? ORDER BY expires_at DESC LIMIT 100")
        .param(group)
        .query(
            (r, n) ->
                new InviteSummary(
                    r.getObject("id", UUID.class),
                    instant(r, "expires_at"),
                    instant(r, "redeemed_at"),
                    instant(r, "revoked_at")))
        .list();
  }

  @Transactional
  public void revoke(UUID actor, UUID group, UUID id) {
    owner(actor, group);
    int updated =
        db.sql("UPDATE invitation SET revoked_at=now() WHERE id=? AND group_id=?")
            .params(id, group)
            .update();
    if (updated == 0) throw ApiException.missing();
    audit(group, actor, id, "INVITATION_REVOKED", "Revoked");
  }

  private record InvitationRow(
      UUID id, UUID group, Instant expires, Instant redeemed, UUID redeemedBy, Instant revoked) {}

  @Transactional
  public Group accept(UUID actor, String token) {
    user(actor);
    InvitationRow i =
        db.sql("SELECT * FROM invitation WHERE token_hash=? FOR UPDATE")
            .param(hash(token))
            .query(
                (r, n) ->
                    new InvitationRow(
                        r.getObject("id", UUID.class),
                        r.getObject("group_id", UUID.class),
                        instant(r, "expires_at"),
                        instant(r, "redeemed_at"),
                        r.getObject("redeemed_by", UUID.class),
                        instant(r, "revoked_at")))
            .optional()
            .orElseThrow(() -> ApiException.invalid("INVITE_INVALID"));
    if (i.revoked() != null || i.expires().isBefore(Instant.now()))
      throw ApiException.invalid("INVITE_INVALID");
    if (i.redeemed() != null) {
      if (actor.equals(i.redeemedBy())) return get(actor, i.group());
      throw ApiException.conflict("INVITE_USED");
    }
    writable(i.group());
    db.sql(
            """
        INSERT INTO group_member(id, group_id, user_id, role) VALUES (?, ?, ?, 'MEMBER')
        ON CONFLICT(group_id, user_id) DO NOTHING
        """)
        .params(UUID.randomUUID(), i.group(), actor)
        .update();
    db.sql("UPDATE invitation SET redeemed_at=now(), redeemed_by=? WHERE id=?")
        .params(actor, i.id())
        .update();
    audit(i.group(), actor, i.id(), "MEMBER_JOINED", "Joined group");
    return get(actor, i.group());
  }

  public Page<Audit> history(UUID actor, UUID group, int offset) {
    membership(actor, group);
    List<Audit> rows =
        db.sql(
                """
        SELECT * FROM audit_event WHERE group_id=? ORDER BY created_at DESC, id DESC LIMIT 51 OFFSET ?
        """)
            .params(group, offset)
            .query(
                (r, n) ->
                    new Audit(
                        r.getObject("id", UUID.class),
                        r.getObject("actor_id", UUID.class),
                        r.getObject("entity_id", UUID.class),
                        r.getString("action"),
                        r.getString("detail"),
                        instant(r, "created_at")))
            .list();
    return page(rows);
  }

  static <T> Page<T> page(List<T> rows) {
    return new Page<>(rows.subList(0, Math.min(50, rows.size())), rows.size() > 50);
  }

  void audit(UUID group, UUID actor, UUID entity, String action, String detail) {
    db.sql(
            "INSERT INTO audit_event(id, group_id, actor_id, entity_id, action, detail) VALUES (?, ?, ?, ?, ?, ?)")
        .params(UUID.randomUUID(), group, actor, entity, action, detail)
        .update();
  }
}
