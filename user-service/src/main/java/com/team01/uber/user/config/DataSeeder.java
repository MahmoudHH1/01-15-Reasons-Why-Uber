package com.team01.uber.user.config;

import com.team01.uber.user.model.User;
import com.team01.uber.user.model.UserRole;
import com.team01.uber.user.model.UserStatus;
import com.team01.uber.user.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        // Seed exactly 1 ADMIN user if it doesn't exist
        String adminEmail = "admin@uber.com";
        
        if (!userRepository.existsByEmail(adminEmail)) {
            User admin = new User();
            admin.setName("Admin User");
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setPhone("+201000000000");
            admin.setRole(UserRole.ADMIN);
            admin.setStatus(UserStatus.ACTIVE);
            
            userRepository.save(admin);
            System.out.println("✓ Seeded ADMIN user: admin@uber.com / admin123");
        }
    }
}