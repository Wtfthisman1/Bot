package Bot.config;

/**
 * Сводка фактической конфигурации при старте приложения.
 *
 * <p>Ответственность: один раз, по готовности контекста, вывести в лог реальные
 * значения, от которых зависят выдаваемые пользователю ссылки — порт сервера и
 * базовые URL. Отдельно предупреждает о рассогласовании: если base-url
 * указывает на эту же машину, но на другой порт, все выданные ссылки ведут
 * в никуда.</p>
 *
 * <p>Внешний адрес с другим портом рассогласованием не считается: бот стоит за
 * обратным прокси, который слушает 443 и ходит сюда на 8080 — это штатная
 * схема, а не ошибка. Раньше предупреждение здесь срабатывало при каждом
 * старте, и от него научились отмахиваться — ровно то, ради чего WARN
 * и заводят, переставало работать.</p>
 */
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupLogger {

    /** Хосты, за которыми не может стоять прокси: это и есть текущая машина. */
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]");

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${upload.base-url:}")
    private String uploadBaseUrl;

    @Value("${download.base-url:}")
    private String downloadBaseUrl;

    @Value("${worker.pool-size:2}")
    private int poolSize;

    /** На каком адресе слушаем. Пусто — на всех интерфейсах сразу. */
    @Value("${server.address:}")
    private String serverAddress;

    private final org.springframework.core.env.Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void logConfiguration() {
        log.info("Приложение готово: порт={}, воркеров={}", serverPort, poolSize);
        log.info("Базовые URL: upload={}, download={}", uploadBaseUrl, downloadBaseUrl);

        report("upload.base-url", uploadBaseUrl);
        report("download.base-url", downloadBaseUrl);
        reportBinding();
    }

    /**
     * Дом, открытый всей домашней сети, — это обход всего, что стоит снаружи.
     *
     * <p>Наружу сайт смотрит через nginx: там ограничение частоты на дверях
     * входа, там HTTPS, там же перезаписывается {@code X-Real-IP}, по которому
     * считаются неудачные попытки. Приложение, слушающее все интерфейсы,
     * принимает запросы и мимо него — с любого устройства домашней сети, по
     * открытому http и с каким угодно заголовком адреса. Лечится не файрволом
     * (он рубит трафик через sing-box), а привязкой к адресу туннеля.</p>
     */
    private void reportBinding() {
        if (!environment.acceptsProfiles(
                org.springframework.core.env.Profiles.of(Bot.config.Profiles.HOME))) {
            return;
        }
        if (serverAddress == null || serverAddress.isBlank()) {
            log.warn("Приложение слушает все интерфейсы: сайт и /internal видны любому "
                    + "устройству домашней сети — мимо nginx, мимо HTTPS и мимо ограничения "
                    + "попыток входа. Задайте SERVER_ADDRESS (адрес в туннеле, обычно 10.8.0.2)");
            return;
        }
        log.info("Слушаем только {}", serverAddress);
    }

    /** Что не так с базовым адресом — решение отделено от логирования ради тестов. */
    enum Verdict {
        /** Адрес не задан: ссылки будут строиться от localhost. */
        MISSING,
        /** Строку не удалось разобрать как URL. */
        INVALID,
        /** Указывает на эту же машину, но на порт, где никто не слушает. */
        LOCAL_PORT_MISMATCH,
        /** Внешний адрес: порт может отличаться, впереди обратный прокси. */
        BEHIND_PROXY,
        /** Адрес совпадает с тем, что слушает приложение. */
        DIRECT
    }

    /**
     * Ссылки строятся из base-url, а не из порта сервера, поэтому расхождение
     * тихо ломает выдачу: пользователь получает ссылку на порт, где никто
     * не слушает. Проверяем именно этот случай — локальный адрес с чужим портом.
     */
    Verdict check(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Verdict.MISSING;
        }

        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            return Verdict.INVALID;
        }

        String host = uri.getHost();
        if (host == null) {
            return Verdict.INVALID;
        }

        int urlPort = uri.getPort() != -1
                ? uri.getPort()
                : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);

        if (urlPort == serverPort) {
            return Verdict.DIRECT;
        }
        return LOCAL_HOSTS.contains(host.toLowerCase()) ? Verdict.LOCAL_PORT_MISMATCH : Verdict.BEHIND_PROXY;
    }

    private void report(String property, String baseUrl) {
        switch (check(baseUrl)) {
            case MISSING -> log.warn(
                    "{} не задан — ссылки будут строиться от localhost и не откроются извне", property);
            case INVALID -> log.error("{} содержит некорректный URL: {}", property, baseUrl);
            case LOCAL_PORT_MISMATCH -> log.warn(
                    "{}={} указывает на эту же машину, но не на порт {}. Ссылки не откроются — "
                            + "добавьте :{} в {}.",
                    property, baseUrl, serverPort, serverPort, property);
            case BEHIND_PROXY -> log.info(
                    "{}={} — внешний адрес, ссылки идут через обратный прокси на порт {}",
                    property, baseUrl, serverPort);
            case DIRECT -> log.debug("{}={} совпадает с портом приложения", property, baseUrl);
        }
    }
}
