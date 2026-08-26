/*
  Единственный скрипт на сайте — и он про одно: связать текст с записью.

  Всё остальное здесь работает и без него: реплики правятся обычной формой,
  кнопки времени просто ничего не делают. Поэтому ни проверок доступности, ни
  запасных путей: если скрипт не загрузился, страница остаётся рабочей.
*/
(function () {
    const player = document.getElementById('player');
    if (!player) return;

    const lines = Array.from(document.querySelectorAll('.line'));

    // Клик по времени — перемотка. Полсекунды назад: whisper ставит начало
    // реплики по первому звуку, и точное попадание срезает первый слог
    lines.forEach(line => {
        const at = line.querySelector('.at');
        if (!at) return;
        at.addEventListener('click', () => {
            player.currentTime = Math.max(0, parseFloat(at.dataset.seconds) - 0.5);
            player.play();
        });
    });

    // Переход из поиска: «#t=123.4» в адресе значит «начни отсюда». Ждём
    // метаданных — до них currentTime у плеера ещё не принимается
    const wanted = parseFloat((location.hash.match(/^#t=([\d.]+)$/) || [])[1]);
    if (!isNaN(wanted)) {
        const seek = () => { player.currentTime = Math.max(0, wanted - 0.5); };
        if (player.readyState > 0) seek();
        else player.addEventListener('loadedmetadata', seek, { once: true });
    }

    // Подсветка реплики, которая звучит сейчас. Пересчёт на timeupdate (раз в
    // четверть секунды) — линейным поиском с последней позиции, а не по всему
    // списку: реплик бывают тысячи
    let current = -1;
    player.addEventListener('timeupdate', () => {
        const now = player.currentTime;
        let index = lines.findIndex((line, i) => {
            const start = seconds(line);
            const next = i + 1 < lines.length ? seconds(lines[i + 1]) : Infinity;
            return now >= start && now < next;
        });
        if (index === current) return;

        if (current >= 0) lines[current].classList.remove('now');
        if (index >= 0) lines[index].classList.add('now');
        current = index;
    });

    function seconds(line) {
        const at = line.querySelector('.at');
        return at ? parseFloat(at.dataset.seconds) : 0;
    }
})();
