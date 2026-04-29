package com.luigi;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.Set;

@RestController
@RequestMapping("/auth")
public class LoginController {

    private final UserService userService;

    public LoginController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password, HttpSession session) {
        if (userService.validateLogin(username, password)) {
            session.setAttribute("user", username);
            session.setAttribute("username", username);
            session.setAttribute("encKey", username + ":" + password);
            if (userService.isAdmin(username)) {
                session.setAttribute("role", "admin");
                session.setAttribute("isAdmin", Boolean.TRUE);
            }
            return "OK";
        }
        return "FAIL";
    }

    @GetMapping("/me")
    public String getCurrentUser(HttpSession session) {
        Object user = session.getAttribute("user");
        return user != null ? user.toString() : "";
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok().build();
    }

    // 👇 API di gestione utenti (solo admin)

    @GetMapping("/users")
    public ResponseEntity<Set<String>> listUsers(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(userService.listUsers());
    }

    @PostMapping("/users")
    public ResponseEntity<String> addUser(@RequestParam String username, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        String password = userService.addUser(username);
        if (password == null) return ResponseEntity.badRequest().body("Utente già esistente.");
        return ResponseEntity.ok(password);
    }

    @DeleteMapping("/users")
    public ResponseEntity<Void> deleteUser(@RequestParam String username, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        userService.removeUser(username);
        return ResponseEntity.noContent().build();
    }

    private boolean isAdmin(HttpSession session) {
        Object role = session.getAttribute("role");
        return role != null && "admin".equals(role.toString());
    }
}
