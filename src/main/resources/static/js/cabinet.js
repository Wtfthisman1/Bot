/*
 * Обратная связь на формах кабинета.
 *
 * Загрузка записи идёт минутами: без единого признака работы страница выглядит
 * зависшей, и человек жмёт кнопку второй раз — то есть ставит ту же задачу
 * дважды. Прогресс браузер по обычной форме не показывает, поэтому кнопка
 * говорит хотя бы «идёт», а повторное нажатие блокируется.
 */
document.querySelectorAll('form.card').forEach(form => {
    form.addEventListener('submit', () => {
        const button = form.querySelector('button[type="submit"]');
        if (!button || button.disabled) {
            return;
        }

        const file = form.querySelector('input[type="file"]');
        const uploading = file && file.files.length > 0;

        button.disabled = true;
        button.textContent = uploading ? 'Загружаю файл…' : 'Отправляю…';

        if (uploading) {
            // Размер полезен именно здесь: он объясняет, почему это не мгновенно
            const megabytes = Math.round(file.files[0].size / (1024 * 1024));
            const note = document.createElement('p');
            note.className = 'hint';
            note.textContent = megabytes >= 1
                ? `Файл ${megabytes} МБ уходит на домашнюю машину — не закрывайте вкладку.`
                : 'Файл уходит на домашнюю машину — не закрывайте вкладку.';
            button.insertAdjacentElement('afterend', note);
        }
    });
});
