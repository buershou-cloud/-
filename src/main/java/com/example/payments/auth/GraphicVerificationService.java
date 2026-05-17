package com.example.payments.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class GraphicVerificationService {

    private static final String SESSION_ATTRIBUTE = GraphicVerificationService.class.getName() + ".challenge";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int WIDTH = 320;
    private static final int HEIGHT = 120;
    private static final int MIN_X = 36;
    private static final int MAX_X = 284;
    private static final int TOLERANCE = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    public GraphicChallengeView createChallenge(HttpServletRequest request) {
        int targetX = MIN_X + secureRandom.nextInt(MAX_X - MIN_X + 1);
        Challenge challenge = new Challenge(UUID.randomUUID().toString(), targetX, Instant.now().plus(TTL));
        request.getSession(true).setAttribute(SESSION_ATTRIBUTE, challenge);
        return new GraphicChallengeView(challenge.id(), renderImage(targetX), WIDTH, HEIGHT, MIN_X, MAX_X);
    }

    public boolean verify(HttpServletRequest request, String challengeId, Integer verificationX) {
        HttpSession session = request.getSession(false);
        if (session == null || challengeId == null || verificationX == null) {
            return false;
        }
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        session.removeAttribute(SESSION_ATTRIBUTE);
        if (!(value instanceof Challenge challenge)) {
            return false;
        }
        if (!challenge.id().equals(challengeId) || Instant.now().isAfter(challenge.expiresAt())) {
            return false;
        }
        return Math.abs(challenge.targetX() - verificationX) <= TOLERANCE;
    }

    private String renderImage(int targetX) {
        int slotY = 62;
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">
                  <defs>
                    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0" stop-color="#eef6ff"/>
                      <stop offset="1" stop-color="#f8fbff"/>
                    </linearGradient>
                    <filter id="shadow" x="-20%%" y="-20%%" width="140%%" height="140%%">
                      <feDropShadow dx="0" dy="6" stdDeviation="5" flood-color="#1677ff" flood-opacity=".18"/>
                    </filter>
                  </defs>
                  <rect width="100%%" height="100%%" rx="12" fill="url(#bg)"/>
                  <path d="M24 86 C74 46 110 112 161 70 S247 43 296 78" fill="none" stroke="#bfd8ff" stroke-width="10" stroke-linecap="round" opacity=".8"/>
                  <path d="M28 35 H292" stroke="#dce9fb" stroke-width="2" stroke-dasharray="6 8"/>
                  <g filter="url(#shadow)">
                    <circle cx="%d" cy="%d" r="18" fill="#ffffff" stroke="#1677ff" stroke-width="3"/>
                    <path d="M%d %d h14 v14 h-14z" fill="#1677ff" opacity=".16"/>
                    <path d="M%d %d h14 v14 h-14z" fill="none" stroke="#1677ff" stroke-width="2"/>
                  </g>
                  <text x="18" y="108" fill="#71819a" font-size="13" font-family="Arial, sans-serif">slide to the highlighted block</text>
                </svg>
                """.formatted(WIDTH, HEIGHT, WIDTH, HEIGHT, targetX, slotY, targetX - 7, slotY - 7, targetX - 7, slotY - 7);
        String encoded = Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return "data:image/svg+xml;base64," + encoded;
    }

    private record Challenge(String id, int targetX, Instant expiresAt) {
    }
}
