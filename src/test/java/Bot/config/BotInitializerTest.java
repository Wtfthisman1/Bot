package Bot.config;

/**
 * Бот регистрируется ровно один раз.
 *
 * <p>Проверка не теоретическая: actuator на отдельном порту поднимает второй
 * контекст, событие которого доходит до слушателей родителя. Пока ловился
 * {@code ContextRefreshedEvent}, регистрация шла дважды, и два цикла
 * {@code getUpdates} внутри одного процесса отбирали апдейты друг у друга —
 * Telegram отвечал 409 Conflict.</p>
 */
import Bot.telegram.MessageSender;
import Bot.telegram.TelegramBot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotInitializerTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<TelegramBot> providerOf(TelegramBot bot) {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private BotConfig config() {
        BotConfig config = new BotConfig();
        config.setAdminChatId("");
        config.setBotToken("111:TEST");
        return config;
    }

    @Test
    void secondEventDoesNotRegisterTheBotAgain() {
        ObjectProvider<TelegramBot> provider = providerOf(mock(TelegramBot.class));
        BotInitializer initializer =
                new BotInitializer(provider, mock(MessageSender.class), config());

        initializer.init();
        initializer.init();

        // За ботом сходили один раз — значит, и регистрация была одна
        verify(provider, times(1)).getIfAvailable();
    }

    /** Дом апдейты не читает, и это не сбой: отправка настраивается всё равно. */
    @Test
    void missingPollingBotIsNotAFailure() {
        BotInitializer initializer =
                new BotInitializer(providerOf(null), mock(MessageSender.class), config());

        initializer.init();
    }
}
