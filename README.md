# Торговая система

Сделал: Сухарев Сергей Александрович.

Небольшое бэкенд приложение для тестового задания СПБВБ: можно создать пользователей, пополнить им деньги или бумаги, выставить заявки на покупку и продажу и посмотреть сделки.

- Java 21+, Maven.
- Все данные хранятся локально: после остановки сервера пользователи, заявки и сделки удаляются.
- GUI нет. Работать с сервером нужно через `curl`, Postman или другой HTTP-клиент. Ответы приходят в JSON.

## 1. Запуск

### Что должно быть установлено

Нужны JDK 21+ и `curl`. Maven отдельно устанавливать не обязательно: в проекте есть Maven Wrapper.

### Сборка и старт

#### На семействе UNIX (Linux, macOS):

Сначала убедитесь, что команда `java` использует JDK 21 или новее:

```bash
java -version
```

Примерный вывод:
```bash
user@Mac trading-system % java -version                     
openjdk version "25.0.3" 2026-04-21 LTS
OpenJDK Runtime Environment Temurin-25.0.3+9 (build 25.0.3+9-LTS)
OpenJDK 64-Bit Server VM Temurin-25.0.3+9 (build 25.0.3+9-LTS, mixed mode, sharing)
```

Если JDK установлена в другом месте, укажите свой путь в `JAVA_HOME`.

Затем из папки проекта выполните:

```bash
sh ./mvnw clean package
java -jar target/trading-system.jar
```

После запуска появится строка:

```text
Trading system: http://localhost:8080
```

Сервер работает, пока открыт этот терминал. **Команды из примеров ниже выполняйте во втором терминале.** Остановить сервер можно сочетанием `Ctrl+C`.

Проверка из второго терминала:

```bash
curl http://localhost:8080/instruments
```

Если сервер отвечает списком инструментов, всё запущено правильно. Адрес `http://localhost:8080/` без пути возвращает 404 (page not found) — это нормально, стартовой веб-страницы у приложения нет.

Другой порт задаётся переменной `PORT`:

```bash
PORT=9090 java -jar target/trading-system.jar
```

#### На Windows:
На Windows: `mvnw.cmd clean package`, затем `java -jar target/trading-system.jar` с настроенной JDK 21+.

## 2. Первый сценарий: от пользователей до сделки

Ниже - полный пример с ожидаемыми ответами. Он рассчитан на **только что запущенный сервер**, поэтому пользователи получат ID `1` и `2`, а заявки — ID `1` и `2`. Если сервер уже использовался, подставьте ID из своих ответов.

Выполните во втором терминале:

```bash
BASE=http://localhost:8080
```

### Шаг 1. Посмотреть доступные инструменты

```bash
curl "$BASE/instruments"
```

Сервер вернёт пять инструментов: `SBER`, `GAZP`, `LKOH`, `SU26238RMFS4`, `CNYRUB_TOM`. Например, начало ответа:

```json
[
  {
    "seccode": "SBER",
    "type": "STOCK",
    "name": "Сбербанк ао",
    "lotSize": 10,
    "minStep": 0.01,
    "currency": "RUB"
  }
]
```

Здесь `lotSize: 10` означает: **1 лот SBER = 10 акций**, а `minStep: 0.01` — минимальный шаг цены.

### Шаг 2. Создать двух пользователей

Покупатель:

```bash
curl -X POST "$BASE/users" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Анна"}'
```

Ответ с HTTP-статусом `201`:

```json
{"id":1,"name":"Анна"}
```

Продавец:

```bash
curl -X POST "$BASE/users" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Иван"}'
```

```json
{"id":2,"name":"Иван"}
```

### Шаг 3. Пополнить счета

Дадим Анне 100 000 RUB:

```bash
curl -X POST "$BASE/users/1/deposit" \
  -H 'Content-Type: application/json' \
  -d '{"asset":"RUB","amount":100000}'
```

```json
{"userId":1,"asset":"RUB","balance":100000,"reserved":0,"available":100000}
```

Дадим Ивану 20 акций SBER:

```bash
curl -X POST "$BASE/users/2/deposit" \
  -H 'Content-Type: application/json' \
  -d '{"asset":"SBER","amount":20}'
```

```json
{"userId":2,"asset":"SBER","balance":20,"reserved":0,"available":20}
```

Важно: при пополнении `amount` для бумаг задаётся **в штуках**, а не в лотах. В заявках количество, наоборот, указывается в лотах.

### Шаг 4. Анна выставляет заявку на покупку

Покупаем 2 лота SBER по 280.5 RUB за акцию:

```bash
curl -X POST "$BASE/orders" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 1' \
  -d '{"seccode":"SBER","side":"BUY","price":280.5,"quantity":2}'
```

`X-User-Id` обязателен для операций с заявками: так сервер понимает, от чьего имени выполняется команда.

Ответ:

```json
{
  "order": {
    "id": 1,
    "userId": 1,
    "seccode": "SBER",
    "side": "BUY",
    "price": 280.5,
    "quantity": 2,
    "filledQuantity": 0,
    "reserved": 5610.00,
    "status": "ACTIVE",
    "cancelReason": null,
    "createdAt": "...",
    "remainingQuantity": 2
  },
  "trades": []
}
```

Пока продавцов нет, сделок нет (`trades: []`), а заявка стоит в стакане. Под неё заблокировано:

```text
280.5 × 10 акций в лоте × 2 лота = 5610 RUB
```

### Шаг 5. Иван продаёт 1 лот — проходит сделка

```bash
curl -X POST "$BASE/orders" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 2' \
  -d '{"seccode":"SBER","side":"SELL","price":280,"quantity":1}'
```

Ответ:

```json
{
  "order": {
    "id": 2,
    "userId": 2,
    "seccode": "SBER",
    "side": "SELL",
    "price": 280,
    "quantity": 1,
    "filledQuantity": 1,
    "reserved": 0,
    "status": "FILLED",
    "cancelReason": null,
    "createdAt": "...",
    "remainingQuantity": 0
  },
  "trades": [
    {
      "id": 1,
      "seccode": "SBER",
      "price": 280.5,
      "quantity": 1,
      "amount": 2805.00,
      "buyOrderId": 1,
      "sellOrderId": 2,
      "buyerId": 1,
      "sellerId": 2,
      "aggressor": "SELL",
      "createdAt": "..."
    }
  ]
}
```

Что произошло:

- заявка Ивана исполнена полностью: `status: FILLED`;
- продан 1 лот, то есть 10 акций;
- сделка прошла по цене заявки Анны, которая уже стояла в стакане: **280.5**, а не по 280;
- сумма сделки: `280.5 × 10 × 1 = 2805 RUB`;
- у Анны остаётся активный 1 лот из первоначальных двух.

### Шаг 6. Проверить портфели

```bash
curl "$BASE/users/1/portfolio"
curl "$BASE/users/2/portfolio"
```

Портфель Анны:

```json
[
  {"userId":1,"asset":"RUB","balance":97195.00,"reserved":2805.00,"available":94390.00},
  {"userId":1,"asset":"SBER","balance":10,"reserved":0,"available":10}
]
```

Портфель Ивана:

```json
[
  {"userId":2,"asset":"RUB","balance":2805.00,"reserved":0,"available":2805.00},
  {"userId":2,"asset":"SBER","balance":10,"reserved":0,"available":10}
]
```

Как читать остатки:

- `balance` — сколько актива всего;
- `reserved` — сколько заблокировано под активные заявки;
- `available = balance - reserved` — сколько можно использовать прямо сейчас.

У Анны ещё 2805 RUB зарезервировано под оставшийся лот покупки.

### Шаг 7. Посмотреть котировки, стакан и сделки

Лучшие цены по всем инструментам:

```bash
curl "$BASE/quotes"
```

Стакан SBER с пятью уровнями цен:

```bash
curl "$BASE/quotes/SBER?depth=5"
```

Ответ после сделки из примера:

```json
{
  "seccode": "SBER",
  "bestBid": 280.5,
  "bestAsk": null,
  "lastPrice": 280.5,
  "bids": [
    {"price": 280.5, "quantity": 1, "orders": 1}
  ],
  "asks": []
}
```

- `bestBid` — лучшая цена покупки;
- `bestAsk` — лучшая цена продажи; `null`, если продавцов нет;
- `lastPrice` — цена последней сделки;
- `bids` — очередь покупок, от лучшей цены вниз;
- `asks` — очередь продаж, от лучшей цены вверх;
- `quantity` в стакане — суммарный остаток **в лотах**, `orders` — число заявок на этом уровне.

Все сделки или только сделки конкретного пользователя/инструмента:

```bash
curl "$BASE/trades"
curl "$BASE/trades?userId=1&seccode=SBER"
```

Реестр заявок с необязательными фильтрами:

```bash
curl "$BASE/orders"
curl "$BASE/orders?userId=1&seccode=SBER&side=BUY&status=PARTIALLY_FILLED"
curl "$BASE/orders/1"
```

### Шаг 8. Изменить и снять заявку

Изменим цену оставшейся заявки Анны на 281:

```bash
curl -X PATCH "$BASE/orders/1" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 1' \
  -d '{"price":281}'
```

Можно передать `price`, `quantity` или оба поля. Ответ имеет тот же формат `{"order": ...,"trades": [...]}`: после изменения цены или увеличения объёма сервер сразу пробует провести новые сделки.

Снимем заявку:

```bash
curl -X DELETE "$BASE/orders/1" -H 'X-User-Id: 1'
```

Ответ:

```json
{
  "id": 1,
  "userId": 1,
  "seccode": "SBER",
  "side": "BUY",
  "price": 281,
  "quantity": 2,
  "filledQuantity": 1,
  "reserved": 0,
  "status": "CANCELLED",
  "cancelReason": "USER",
  "createdAt": "...",
  "remainingQuantity": 1
}
```

Блокировка под неисполненный остаток снимается. Исполненная часть заявки не отменяется.

## 3. Статусы заявки

| Статус | Что означает |
|---|---|
| `ACTIVE` | Сделок ещё не было, заявка находится в стакане |
| `PARTIALLY_FILLED` | Часть исполнена, остаток ещё активен |
| `FILLED` | Заявка исполнена полностью |
| `CANCELLED` | Заявка снята пользователем или системой |

`filledQuantity` — исполнено лотов, `remainingQuantity` — осталось исполнить лотов. `cancelReason: USER` означает снятие пользователем; `SELF_TRADE` — отмену остатка из-за попытки сделки с собственной встречной заявкой.

## 4. Инструменты и как указывать цену

| Код | Что это | 1 лот | Как задаётся `price` | Шаг цены |
|---|---|---:|---|---:|
| `SBER`, `GAZP` | Акции | 10 акций | RUB за 1 акцию | 0.01 |
| `LKOH` | Акции | 1 акция | RUB за 1 акцию | 0.5 |
| `SU26238RMFS4` | Облигация ОФЗ, номинал 1000 RUB | 1 бумага | Процент от номинала | 0.001 |
| `CNYRUB_TOM` | Валютная пара | 1000 CNY | RUB за 1 CNY | 0.0001 |

Например, цена `99.5` по облигации означает 99.5% от номинала, а не 99.5 RUB. Сумма сделки за 1 бумагу будет `99.5 × 1000 / 100 = 995 RUB`.

Для валютной пары покупателю нужны RUB, а продавцу — CNY. Пополнение юаней делается так же: `{"asset":"CNY","amount":10000}`.

Цена должна быть кратна шагу: например, `280.5` подходит для SBER, а `280.505` — нет.

## 5. Все HTTP-методы

| Метод и путь | Что делает | Тело / параметры |
|---|---|---|
| `GET /instruments` | Список инструментов | — |
| `POST /users` | Создать пользователя | `{"name":"Анна"}` |
| `GET /users/{id}` | Получить пользователя | — |
| `GET /users/{id}/portfolio` | Остатки по всем активам | — |
| `POST /users/{id}/deposit` | Пополнить валюту или бумаги | `{"asset":"RUB","amount":100000}` |
| `POST /orders` | Выставить заявку | Заголовок `X-User-Id`; `{"seccode":"SBER","side":"BUY","price":280.5,"quantity":2}` |
| `GET /orders/{id}` | Получить заявку | — |
| `GET /orders` | Список заявок | Необязательные `userId`, `seccode`, `side`, `status` |
| `PATCH /orders/{id}` | Изменить свою активную заявку | Заголовок `X-User-Id`; `price` и/или `quantity` |
| `DELETE /orders/{id}` | Снять свою активную заявку | Заголовок `X-User-Id` |
| `GET /trades` | Список сделок | Необязательные `userId`, `seccode` |
| `GET /quotes` | Лучшие цены по всем инструментам | — |
| `GET /quotes/{seccode}` | Стакан инструмента | Необязательный `depth` от 0 до 100, по умолчанию 10 |

Создание пользователя и заявки возвращает HTTP `201`, остальные успешные запросы — `200`.

## 6. Ошибки

Ошибка всегда возвращается в виде JSON:

```json
{"error":"UNAUTHORIZED","message":"Не указан заголовок X-User-Id"}
```

| HTTP | Типичная причина |
|---:|---|
| `400` | Неверный JSON, пропущено поле, цена не кратна шагу, неправильный параметр |
| `401` | В `POST /orders`, `PATCH` или `DELETE /orders/{id}` нет заголовка `X-User-Id` |
| `403` | Попытка изменить или снять чужую заявку |
| `404` | Нет такого пользователя, заявки, инструмента или пути |
| `409` | Заявка уже исполнена или отменена, поэтому изменить/снять её нельзя |
| `422` | Не хватает свободных денег или бумаг |

Пример ответа при недостатке средств:

```json
{
  "error": "INSUFFICIENT_FUNDS",
  "message": "Недостаточно RUB: нужно 280500.00, свободно 2805.00"
}
```

Для просмотра HTTP-статуса добавьте к `curl` опцию `-i`:

```bash
curl -i -X POST "$BASE/orders" \
  -H 'Content-Type: application/json' \
  -d '{"seccode":"SBER","side":"BUY","price":280.5,"quantity":1}'
```
