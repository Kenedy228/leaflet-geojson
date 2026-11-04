package com.github.gis.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {
  @GetMapping("/login")
  public String login(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setCharacterEncoding("UTF-8");
    return "forward:/login.html";
  }
}
