package Bot.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор базового адреса: рассогласованием считается только локальный адрес
 * с чужим портом. Внешний домен — обычная схема с обратным прокси, и ругаться
 * на неё при каждом старте значит обесценить предупреждение.
 */
class StartupLoggerTest {

    private StartupLogger logger;

    @BeforeEach
    void setUp() {
        logger = new StartupLogger(new org.springframework.mock.env.MockEnvironment());
        ReflectionTestUtils.setField(logger, "serverPort", 8080);
    }

    @Test
    void externalHttpsAddressIsNormal() {
        assertThat(logger.check("https://transcribot.site"))
                .isEqualTo(StartupLogger.Verdict.BEHIND_PROXY);
    }

    @Test
    void localhostWithoutPortIsAMistake() {
        assertThat(logger.check("http://localhost"))
                .isEqualTo(StartupLogger.Verdict.LOCAL_PORT_MISMATCH);
    }

    @Test
    void localhostWithRightPortIsFine() {
        assertThat(logger.check("http://localhost:8080"))
                .isEqualTo(StartupLogger.Verdict.DIRECT);
    }

    @Test
    void loopbackAddressIsCheckedToo() {
        assertThat(logger.check("http://127.0.0.1:9090"))
                .isEqualTo(StartupLogger.Verdict.LOCAL_PORT_MISMATCH);
    }

    @Test
    void emptyAddressIsReported() {
        assertThat(logger.check("  ")).isEqualTo(StartupLogger.Verdict.MISSING);
        assertThat(logger.check(null)).isEqualTo(StartupLogger.Verdict.MISSING);
    }

    @Test
    void garbageIsReportedAsInvalid() {
        assertThat(logger.check("не-url")).isEqualTo(StartupLogger.Verdict.INVALID);
    }
}
