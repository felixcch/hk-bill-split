package hk.billsplit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Per-client write budget so a leaked link or a bug cannot flood a group. */
final class WriteRateLimit extends OncePerRequestFilter {
  private record Window(long minute, int count) {}

  private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(128, 0.75f, true);

  private synchronized boolean allow(String client) {
    long minute = System.currentTimeMillis() / 60_000;
    Window old = windows.get(client);
    int count = old != null && old.minute() == minute ? old.count() + 1 : 1;
    windows.put(client, new Window(minute, count));
    if (windows.size() > 10_000) windows.remove(windows.keySet().iterator().next());
    return count <= 120;
  }

  static String client(HttpServletRequest req) {
    String forwarded = req.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].strip();
    return req.getRemoteAddr();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (req.getRequestURI().startsWith("/api/")
        && Set.of("POST", "PUT", "PATCH", "DELETE").contains(req.getMethod())
        && !allow(client(req))) {
      res.setStatus(429);
      res.setHeader("Retry-After", "60");
      res.setContentType("application/json");
      res.getWriter().write("{\"code\":\"RATE_LIMITED\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}
