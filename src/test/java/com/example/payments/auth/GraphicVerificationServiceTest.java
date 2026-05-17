package com.example.payments.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GraphicVerificationServiceTest {

    @Test
    void verifiesSessionBoundGraphicChallengeOnce() {
        GraphicVerificationService service = new GraphicVerificationService();
        MockHttpServletRequest request = new MockHttpServletRequest();

        GraphicChallengeView challenge = service.createChallenge(request);
        int targetX = targetX(challenge);

        assertThat(service.verify(request, challenge.challengeId(), targetX)).isTrue();
        assertThat(service.verify(request, challenge.challengeId(), targetX)).isFalse();
    }

    private static int targetX(GraphicChallengeView challenge) {
        String prefix = "data:image/svg+xml;base64,";
        assertThat(challenge.image()).startsWith(prefix);
        String svg = new String(Base64.getDecoder().decode(challenge.image().substring(prefix.length())), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("<circle cx=\"(\\d+)\"").matcher(svg);
        assertThat(matcher.find()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
