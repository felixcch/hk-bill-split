package hk.billsplit;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@Profile("demo")
public class Demo {
  public record Identity(String id, String name) {}

  public static final List<Identity> USERS =
      List.of(
          new Identity("00000000-0000-0000-0000-000000000001", "阿晴"),
          new Identity("00000000-0000-0000-0000-000000000002", "阿樂"),
          new Identity("00000000-0000-0000-0000-000000000003", "阿欣"));

  @Bean
  SecurityFilterChain demoSecurity(HttpSecurity http) throws Exception {
    return Security.shared(http)
        .addFilterBefore(new DemoFilter(), AnonymousAuthenticationFilter.class)
        .build();
  }

  static class DemoFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
      String id = req.getHeader("X-Demo-User");
      if (USERS.stream().anyMatch(u -> u.id().equals(id))) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(id, null, List.of()));
      }
      chain.doFilter(req, res);
    }
  }
}
