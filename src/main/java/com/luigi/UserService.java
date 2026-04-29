package com.luigi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@Service
public class UserService {

    private final File store = new File("users.json");
    private final Map<String, String> users = new HashMap<>(); // username → passwordHash
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void load() {
        if (store.exists()) {
            try {
                Map<String, String> loaded = mapper.readValue(store, new TypeReference<>() {});
                users.putAll(loaded);
            } catch (IOException e) {
                System.err.println("❌ Errore caricamento utenti: " + e.getMessage());
            }
        }

        // Verifica che "luigi" esista, altrimenti lo aggiunge con pwd hardcoded
        if (!users.containsKey("luigi")) {
            String defaultAdminPassword = "Tr0pGSGwbJHS$6$%gsjspaS1cur@2025";
            users.put("luigi", hash(defaultAdminPassword));
            save();
            System.out.println("👤 Utente admin 'luigi' creato con password di default.");
        }
    }


    public boolean isAdmin(String user) {
        return "luigi".equalsIgnoreCase(user);
    }

    public Set<String> listUsers() {
        return new TreeSet<>(users.keySet());
    }

    public String addUser(String username) {
        if (users.containsKey(username)) return null;
        String rawPwd = generateStrongPassword();
        users.put(username, hash(rawPwd));
        save();
        return rawPwd; // Show only once
    }

    public void removeUser(String username) {
        if (!"luigi".equalsIgnoreCase(username)) {
            users.remove(username);
            save();
        }
    }

    public boolean exists(String username) {
        return users.containsKey(username);
    }

    public boolean validateLogin(String username, String password) {
        return users.containsKey(username) && Objects.equals(users.get(username), hash(password));
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(store, users);
        } catch (IOException e) {
            System.err.println("❌ Errore salvataggio utenti: " + e.getMessage());
        }
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encoded);
        } catch (Exception e) {
            throw new RuntimeException("Errore hashing password", e);
        }
    }

    public String generateStrongPassword() {
        SecureRandom random = new SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+[]{}|;:,.<>?";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
