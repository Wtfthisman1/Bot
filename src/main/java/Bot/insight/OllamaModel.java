package Bot.insight;

/**
 * Клиент к Ollama, поднятой на домашней машине.
 *
 * <p>Ответственность: сходить в {@code /api/generate} и вернуть текст. Ollama
 * выбрана потому, что уже умеет то, ради чего иначе пришлось бы держать свой
 * питоновский процесс рядом с Whisper: держит веса в видеопамяти между
 * запросами, сама решает, что выгрузить, и говорит по HTTP.</p>
 *
 * <p>Слушает только localhost: наружу смотрит один nginx на VPS, и открытая в
 * сеть модель — это чужие запросы на моей видеокарте.</p>
 *
 * <p>{@code keep_alive} задаётся в каждом запросе. Внутри обработки — минуты,
 * чтобы куски одного разговора не оплачивали загрузку весов по очереди; после
 * неё {@link #unload()} ставит ноль и память освобождается сразу. Значение по
 * умолчанию (пять минут) означало бы, что следующая расшифровка ждёт впустую
 * или падает на нехватке видеопамяти.</p>
 */
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class OllamaModel implements LanguageModel {

    /** Служба либо на этой же машине, либо её нет: ждать соединения долго незачем. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient client;
    private final String model;
    private final int contextTokens;
    private final Duration keepAlive;

    public OllamaModel(String baseUrl, String model, int contextTokens,
                       Duration readTimeout, Duration keepAlive) {
        this.model = model;
        this.contextTokens = contextTokens;
        this.keepAlive = keepAlive;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        log.info("Обработка текста: модель {} на {}", model, baseUrl);
    }

    /**
     * Поднята ли служба и скачана ли модель.
     *
     * <p>Проверяются оба условия: {@code ollama} без скачанных весов отвечает на
     * {@code /api/tags} успехом, а на первый же вопрос — ошибкой. Кнопки на
     * странице показываются по этому ответу, и обещать то, чего нет, нельзя.</p>
     */
    @Override
    public boolean available() {
        try {
            Tags tags = client.get().uri("/api/tags").retrieve().body(Tags.class);
            if (tags == null || tags.models() == null) {
                return false;
            }
            // Ollama хранит теги вида «qwen2.5:7b»; заказ без тега означает «latest»
            String wanted = model.contains(":") ? model : model + ":latest";
            return tags.models().stream().anyMatch(m -> wanted.equals(m.name()));
        } catch (Exception e) {
            log.debug("Модель недоступна: {}", e.toString());
            return false;
        }
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public String ask(String instruction, String prompt) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("system", instruction);
        request.put("prompt", prompt);
        // Ответ нужен целиком: показывать его по мере набора некому — человек
        // в это время уже ушёл со страницы
        request.put("stream", false);
        // Думающие модели (qwen3 и подобные) иначе сначала рассуждают вслух —
        // на пересказе это лишние минуты счёта и ничего сверх. Моделям, которые
        // так не умеют, флаг безобиден: Ollama его просто игнорирует
        request.put("think", false);
        request.put("keep_alive", keepAlive.toSeconds() + "s");
        request.put("options", Map.of(
                "num_ctx", contextTokens,
                // Пересказ не место для выдумок: чем ниже температура, тем ближе
                // модель держится сказанного в записи
                "temperature", 0.2));

        Generated answer = client.post().uri("/api/generate")
                .body(request)
                .retrieve()
                .body(Generated.class);

        String text = answer == null || answer.response() == null ? "" : answer.response().strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("Модель вернула пустой ответ");
        }
        return text;
    }

    @Override
    public void unload() {
        try {
            // Запрос без подсказки и с нулевым keep_alive Ollama понимает как
            // «выгрузи»: считать при этом нечего, ответ приходит сразу
            client.post().uri("/api/generate")
                    .body(Map.of("model", model, "keep_alive", 0))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Модель {} выгружена из видеопамяти", model);
        } catch (Exception e) {
            // Не беда: через keep_alive Ollama выгрузит её и сама, просто позже
            log.warn("Не удалось выгрузить модель {}: {}", model, e.toString());
        }
    }

    /* ───────── ответы Ollama: только нужные поля ───────── */

    private record Generated(String response) {}

    private record Tags(List<Model> models) {}

    private record Model(String name) {}
}
