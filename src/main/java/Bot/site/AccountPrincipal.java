package Bot.site;

/**
 * Кто вошёл на сайт — то, что лежит в сессии.
 *
 * <p>Ответственность: минимум, который нужен странице и контроллеру, — кому
 * принадлежат задачи и как назвать человека в шапке. Ни почты, ни хеша пароля
 * здесь нет сознательно: сессия живёт долго, а всё остальное берётся из базы
 * в тот момент, когда действительно нужно.</p>
 */
import java.util.UUID;

public record AccountPrincipal(UUID accountId, String title) {

    @Override
    public String toString() {
        return title;
    }
}
