package Bot.site;

/**
 * Приложение Google — только если для него заданы ключи.
 *
 * <p>Регистрация собирается кодом, а не свойствами
 * {@code spring.security.oauth2.client.*}, ради одного: пустой ключ должен
 * означать «входа через Google нет», а не падение при старте. Свойства с
 * пустым {@code client-id} автоконфигурация считает настроенным клиентом и
 * роняет контекст на проверке — а дом обязан подниматься и без Google.</p>
 *
 * <p>Адрес возврата остаётся стандартным:
 * {@code {baseUrl}/login/oauth2/code/google}. Именно его надо вписать в
 * консоли Google Cloud.</p>
 */
import Bot.config.Profiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Profile(Profiles.HOME)
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${google.client-id:}'.isBlank()")
@Slf4j
public class GoogleClientConfig {

    @Bean
    ClientRegistrationRepository googleClients(@Value("${google.client-id}") String clientId,
                                               @Value("${google.client-secret}") String secret) {
        ClientRegistration google = CommonOAuth2Provider.GOOGLE
                .getBuilder("google")
                .clientId(clientId)
                .clientSecret(secret)
                .build();
        log.info("Вход через Google настроен: приложение {}", clientId);
        return new InMemoryClientRegistrationRepository(google);
    }
}
