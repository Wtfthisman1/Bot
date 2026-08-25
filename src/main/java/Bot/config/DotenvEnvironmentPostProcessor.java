package Bot.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Загрузка переменных из локального файла {@code .env} на самой ранней стадии запуска.
 *
 * <p>Заменяет прежние {@code EnvConfig} и {@code EnvLoader}, которые читали
 * {@code .env} в {@code @PostConstruct} — то есть уже ПОСЛЕ того, как Spring
 * резолвил {@code @Value} в других бинах. Из-за этого значения из {@code .env}
 * могли не подхватиться. {@link EnvironmentPostProcessor} выполняется до создания
 * контекста, поэтому все {@code @Value} и {@code Environment}-чтения видят эти
 * значения.</p>
 *
 * <p>Источник добавляется сразу <b>после</b> {@code systemEnvironment}: реальные
 * переменные окружения и системные свойства имеют приоритет над {@code .env},
 * но {@code .env} перекрывает {@code application.properties}.</p>
 *
 * <p>Регистрируется через {@code META-INF/spring.factories} (см. сопроводительный
 * файл). На этом этапе обычный логгер ещё не инициализирован, поэтому диагностика
 * идёт в {@code System.out}/{@code System.err}.</p>
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String DOTENV_FILE = ".env";
    private static final String PROPERTY_SOURCE_NAME = "dotenvProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        File envFile = new File(DOTENV_FILE);
        if (!envFile.exists()) {
            System.out.println("[dotenv] .env не найден — используются переменные окружения и application.properties");
            return;
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(envFile)) {
            props.load(fis);
        } catch (IOException e) {
            System.err.println("[dotenv] Не удалось загрузить .env: " + e.getMessage());
            return;
        }

        environment.getPropertySources().addAfter(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new PropertiesPropertySource(PROPERTY_SOURCE_NAME, props));

        System.out.println("[dotenv] Загружено переменных из .env: " + props.size());
    }
}
