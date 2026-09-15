package hk.billsplit;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class WriteRateLimit extends OncePerRequestFilter {
  private record Window(long minute, int count) {}

  private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(128, 0.75f, true);

  private synchronized boolean allow(String user) {
    long minute = System.currentTimeMillis() / 60_000;
    Window old = windows.get(user);
    int count = old != null && old.minute() == minute ? old.count() + 1 : 1;
    windows.put(user, new Window(minute, count));
    if (windows.size() > 10_000) windows.remove(windows.keySet().iterator().next());
    return count <= 120;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (req.getRequestURI().startsWith("/api/")
        && Set.of("POST", "PUT", "PATCH", "DELETE").contains(req.getMethod())
        && auth != null
        && auth.isAuthenticated()
        && !(auth instanceof AnonymousAuthenticationToken)
        && !allow(auth.getName())) {
      res.setStatus(429);
      res.setHeader("Retry-After", "60");
      res.setContentType("application/json");
      res.getWriter().write("{\"code\":\"RATE_LIMITED\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}
