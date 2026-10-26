package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ChannelUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChannelCredentialPrivacyTest {
    @Test
    void publicViewNeverReturnsAuthorizationTokenAndEditingBlankPreservesIt() throws Exception {
        var channel = new PaymentGatewayProperties.Channel();
        channel.setId("public-cashier");
        channel.setProvider("ALIPAY");
        channel.getAlipay().setAppAuthToken("synthetic-private-app-token");
        channel.getAlipay().setMerchantPrivateKey("synthetic-private-key");
        ChannelRegistry registry = mock(ChannelRegistry.class);
        when(registry.all()).thenReturn(List.of(channel));
        when(registry.find(channel.getId())).thenReturn(Optional.of(channel));
        when(registry.save(any())).thenAnswer(call -> call.getArgument(0));
        var controller = new ChannelController(registry);
        ObjectMapper mapper = new ObjectMapper();
        var listed = controller.list().getFirst();
        assertThat(listed.appAuthToken()).isNull();
        assertThat(listed.hasAppAuthToken()).isTrue();
        assertThat(mapper.writeValueAsString(listed)).doesNotContain("synthetic-private-app-token", "synthetic-private-key");

        ChannelUpdateRequest unchanged = mapper.readValue("{\"appAuthToken\":\" \"}", ChannelUpdateRequest.class);
        var edited = controller.update(channel.getId(), unchanged, new MockHttpServletRequest());
        assertThat(channel.getAlipay().getAppAuthToken()).isEqualTo("synthetic-private-app-token");
        assertThat(edited.appAuthToken()).isNull();
        ChannelUpdateRequest rotated = mapper.readValue("{\"appAuthToken\":\"synthetic-replacement\"}", ChannelUpdateRequest.class);
        assertThat(controller.update(channel.getId(), rotated, new MockHttpServletRequest()).appAuthToken()).isNull();
        assertThat(channel.getAlipay().getAppAuthToken()).isEqualTo("synthetic-replacement");
    }
}
