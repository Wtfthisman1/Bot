package Bot.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Пропускает только события, относящиеся к конкретной задаче.
 *
 * <p>Нужен для пофайлового лога задачи: без него {@code SiftingAppender} завёл бы
 * файл-помойку для всего, что происходит вне обработки задач (старт приложения,
 * HTTP-запросы, планировщик). Реализован классом, а не выражением в XML, чтобы
 * не тащить janino ради одного условия.</p>
 */
public class JobIdFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        String jobId = event.getMDCPropertyMap().get("jobId");
        return (jobId == null || jobId.isEmpty()) ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}
