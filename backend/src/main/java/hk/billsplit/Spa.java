package hk.billsplit;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class Spa {
  @GetMapping({"/g/{code}", "/new"})
  String app() {
    return "forward:/index.html";
  }
}
