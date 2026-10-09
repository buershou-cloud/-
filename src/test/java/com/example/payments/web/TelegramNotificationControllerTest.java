package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.auth.AdminSession;
import com.example.payments.notification.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TelegramNotificationControllerTest {
    private static final String URL = "/api/v1/admin-notifications/telegram";
    private final TelegramNotificationService service = mock(TelegramNotificationService.class);
    private final AdminAuthService auth = mock(AdminAuthService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TelegramNotificationController(service, auth)).build();
        when(auth.hasUsername("root")).thenReturn(true);
        when(auth.isSuperAdministrator("root")).thenReturn(true);
        when(auth.hasUsername("operator")).thenReturn(true);
    }

    @Test
    void allOperationsRequireValidSuperAdministratorSession() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isForbidden());
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"chatId\":\"\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL + "/test").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        for (String name : new String[]{"operator", "deleted-user"}) {
            mvc.perform(get(URL).sessionAttr(AdminSession.USERNAME_ATTRIBUTE, name)).andExpect(status().isForbidden());
            mvc.perform(post(URL + "/test").sessionAttr(AdminSession.USERNAME_ATTRIBUTE, name)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUPER_ADMIN_REQUIRED"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void getAndSaveExposeOnlySafeConfigurationView() throws Exception {
        var view = new TelegramNotificationService.SettingsView(false, true, "-100123", "");
        when(service.settings()).thenReturn(view);
        when(service.saveSettings(any())).thenReturn(view);
        mvc.perform(get(URL).sessionAttr(AdminSession.USERNAME_ATTRIBUTE, "root"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.tokenConfigured").value(true)).andExpect(jsonPath("$.botToken").doesNotExist());
        mvc.perform(put(URL).sessionAttr(AdminSession.USERNAME_ATTRIBUTE, "root").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"chatId\":\"-100123\",\"botToken\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chatId").value("-100123"));
        verify(service).saveSettings(new TelegramNotificationService.SettingsUpdate(false, "-100123", ""));
    }

    @Test
    void mutationsRejectHtmlFormsAndTestRequiresJsonBody() throws Exception {
        mvc.perform(put(URL).sessionAttr(AdminSession.USERNAME_ATTRIBUTE, "root")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("enabled=true"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(URL + "/test").sessionAttr(AdminSession.USERNAME_ATTRIBUTE, "root")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("test=true"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(URL + "/test").sessionAttr(AdminSession.USERNAME_ATTRIBUTE, "root")
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void testUsesSavedConfigurationAndMapsConfirmedAndUnconfirmedDelivery() throws Exception {
        when(service.sendTest()).thenReturn(new TelegramNotificationService.TestResult(true, "TELEGRAM_SENT", "已发送"),
                new TelegramNotificationService.TestResult(false, "TELEGRAM_NETWORK_ERROR", "无法连接 Telegram"));
        mvc.perform(post(URL + "/test").sessionAttr(AdminSession.USERNAME_ATTRIBUTE, "root")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post(URL + "/test").sessionAttr(AdminSession.USERNAME_ATTRIBUTE, "root")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TELEGRAM_NETWORK_ERROR"));
    }
}
