package com.sqlteacher.infrastructure.system;

import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.system.GeneralSoftwareSettings;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/** Builds bounded HTTP clients from the persisted non-secret proxy policy. */
public final class ConfiguredHttpClient {
    private ConfiguredHttpClient() { }

    public static HttpClient create(GeneralSoftwareService service, HttpClient.Redirect redirects) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(redirects);
        GeneralSoftwareSettings settings = service.settings();
        if (settings.proxyMode() == GeneralSoftwareSettings.ProxyMode.SYSTEM) builder.proxy(ProxySelector.getDefault());
        if (settings.proxyMode() == GeneralSoftwareSettings.ProxyMode.MANUAL) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(settings.proxyHost(), settings.proxyPort())));
        }
        return builder.build();
    }
}
