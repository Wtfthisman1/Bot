package Bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа Spring Boot-приложения бота.
 *
 * <p>Включает планировщик задач и поднимает все компоненты контекста.</p>
 */
@SpringBootApplication(exclude = { R2dbcAutoConfiguration.class })
@EnableScheduling

public class SpringBotApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringBotApplication.class, args);
	}

}
