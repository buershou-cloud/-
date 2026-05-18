package com.example.payments.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.lang.reflect.Method;
import java.util.Enumeration;

import static org.assertj.core.api.Assertions.assertThat;

class GraphicVerificationServiceTest {

    @Test
    void verifiesSessionBoundGraphicChallengeOnce() throws Exception {
        GraphicVerificationService service = new GraphicVerificationService();
        MockHttpServletRequest request = new MockHttpServletRequest();

        GraphicChallengeView challenge = service.createChallenge(request);
        int targetX = targetX(request);

        assertThat(challenge.image()).startsWith("data:image/png;base64,");
        assertThat(challenge.pieceImage()).startsWith("data:image/png;base64,");
        assertThat(challenge.pieceSize()).isGreaterThan(0);
        assertThat(service.verify(request, challenge.challengeId(), targetX)).isTrue();
        assertThat(service.verify(request, challenge.challengeId(), targetX)).isFalse();
    }

    private static int targetX(MockHttpServletRequest request) throws Exception {
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session).isNotNull();
        Enumeration<String> names = session.getAttributeNames();
        assertThat(names.hasMoreElements()).isTrue();
        Object state = session.getAttribute(names.nextElement());
        Method method = state.getClass().getDeclaredMethod("targetX");
        method.setAccessible(true);
        return (Integer) method.invoke(state);
    }
}
