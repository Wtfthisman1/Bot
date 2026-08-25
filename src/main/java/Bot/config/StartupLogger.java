package Bot.config;

/**
 * Сводка фактической конфигурации при старте приложения.
 *
 * <p>Ответственность: один раз, по готовности контекста, вывести в лог реальные
 * значения, от которых зависят выдаваемые пользователю ссылки — порт сервера и
 * базовые URL. Отдельно предупреждает о рассогласовании: если в
 * {@code UPLOAD_BASE_URL}/{@code DOWNLOAD_BASE_URL} не указан порт, а приложение
 * слушает не 80-й, все выданные ссылки будут вести в никуда.</p>
 */
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupLogger {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${upload.base-url:}")
    private String uploadBaseUrl;

    @Value("${download.base-url:}")
    private String downloadBaseUrl;

    @Value("${worker.pool-size:2}")
    private int poolSize;

    @EventListener(ApplicationReadyEvent.class)
    public void logConfiguration() {
        log.info("Приложение готово: порт={}, воркеров={}", serverPort, poolSize);
        log.info("Базовые URL: upload={}, download={}", uploadBaseUrl, downloadBaseUrl);

        warnIfPortMismatch("upload.base-url", uploadBaseUrl);
        warnIfPortMismatch("download.base-url", downloadBaseUrl);
    }

    /**
     * Ссылки строятся из base-url, а не из порта сервера, поэтому расхождение
     * тихо ломает выдачу: пользователь получает ссылку на порт, где никто не слушает.
     */
    private void warnIfPortMismatch(String property, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("{} не задан — ссылки будут строиться от localhost и не откроются извне", property);
            return;
        }

        int urlPort;
        try {
            URI uri = URI.create(baseUrl);
            urlPort = uri.getPort() != -1
                    ? uri.getPort()
                    : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        } catch (IllegalArgumentException e) {
            log.error("{} содержит некорректный URL: {}", property, baseUrl, e);
            return;
        }

        if (urlPort != serverPort) {
            log.warn("{}={} указывает на порт {}, а приложение слушает {}. "
                            + "Ссылки откроются, только если перед ботом стоит обратный прокси; "
                            + "иначе добавьте :{} в {}.",
                    property, baseUrl, urlPort, serverPort, serverPort, property);
        }
    }
}
