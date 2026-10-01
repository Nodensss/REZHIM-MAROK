"""Run the local server in this console and open the default browser."""
import json
import threading
import urllib.error
import urllib.request
import webbrowser

from werkzeug.serving import make_server

from .app import create_app

URL = 'http://127.0.0.1:5000/'


def already_running():
    try:
        with urllib.request.urlopen(URL + 'api/state', timeout=2) as response:
            data = json.load(response)
        return isinstance(data, dict) and all(isinstance(data.get(k), list) for k in ('grade', 'observation', 'measurement', 'templates'))
    except (OSError, ValueError, urllib.error.URLError):
        return False


def open_browser():
    try:
        if webbrowser.open(URL, new=2):
            return
    except OSError:
        pass
    print('Не удалось открыть браузер автоматически. Откройте адрес: ' + URL, flush=True)


def main():
    if already_running():
        print('Рабочее место уже запущено. Открываю браузер.', flush=True)
        print('Сервер продолжает работать в окне первого запуска.', flush=True)
        open_browser()
        return 0

    app = create_app()
    try:
        server = make_server('127.0.0.1', 5000, app, threaded=True)
    except (OSError, SystemExit):
        if already_running():
            open_browser()
            return 0
        print('Не удалось запустить сервер: порт 5000 занят другим приложением.', flush=True)
        return 1

    print('\nРЕЖИМЫ МАРОК УПЭ — РАБОЧЕЕ МЕСТО', flush=True)
    print('Сервер работает: ' + URL, flush=True)
    print('Оставьте это окно открытым во время работы.', flush=True)
    print('Закрытие этого окна останавливает сервер. Также можно нажать Ctrl+C.\n', flush=True)
    # The server owns the console. Only browser opening is delegated to a thread.
    threading.Thread(target=open_browser, daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\nСервер остановлен.', flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
